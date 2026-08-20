package com.ecommerce.order.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.dto.OrderVO;
import com.ecommerce.order.dto.PurchaseCheckVO;
import com.ecommerce.order.entity.Cart;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.mapper.CartMapper;
import com.ecommerce.order.mapper.OrderItemMapper;
import com.ecommerce.order.mapper.OrderMapper;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 订单状态：待付款。 */
    private static final int STATUS_PENDING_PAYMENT = 0;

    /** 订单状态：待发货。 */
    private static final int STATUS_PENDING_DELIVERY = 1;

    /** 订单状态：待收货。 */
    private static final int STATUS_PENDING_RECEIPT = 2;

    /** 订单状态：已完成（评价资格的前提）。 */
    private static final int STATUS_COMPLETED = 3;

    /** 订单状态：已取消。 */
    private static final int STATUS_CANCELLED = 4;

    /** 订单状态：已退款。 */
    private static final int STATUS_REFUNDED = 5;

    /** 时间格式化器。 */
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 是否允许「未付款直接发货」。
     *
     * <p>本项目没有接真实支付网关，订单会永远停在待付款；开着它，
     * 卖家发货 + 买家确认收货这条链路才能真正跑通，评价功能才有数据可用。
     * 生产环境接入真实支付后应设为 {@code false}。
     */
    @Value("${order.ship.allow-unpaid:true}")
    private boolean allowUnpaidShip = true;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private CartMapper cartMapper;

    @Autowired
    private ProductClient productClient;

    /**
     * 创建订单 - 按卖家分组，每个卖家生成一个独立订单
     */
    @Transactional(rollbackFor = Exception.class)
    public List<OrderVO> createOrder(CreateOrderRequest request, Long userId) {
        // 1. 一次性查询所有商品信息并缓存，避免重复查询
        Map<Long, ProductClient.ProductInfo> productMap = new HashMap<>();
        for (CreateOrderRequest.OrderItemRequest itemRequest : request.getItems()) {
            if (!productMap.containsKey(itemRequest.getProductId())) {
                productMap.put(itemRequest.getProductId(),
                        requireProductInfo(itemRequest.getProductId()));
            }
        }

        // 2. 校验库存
        for (CreateOrderRequest.OrderItemRequest itemRequest : request.getItems()) {
            ProductClient.ProductInfo product = productMap.get(itemRequest.getProductId());
            if (product.getStock() < itemRequest.getQuantity()) {
                // 库存不足属于「资源当前状态与请求冲突」，语义上就是 409：
                // 请求本身合法（参数没错、商品也存在），只是此刻的库存状态不允许成交。
                // 此前走单参构造 → code 500 软失败 → HTTP 恒为 200，
                // 客户端只能靠 body.success 兜底，与同一接口里 400/404 的口径自相矛盾。
                throw new BusinessException(ErrorCode.CONFLICT, "商品库存不足: " + product.getName());
            }
        }

        // 3. 按卖家分组
        Map<Long, List<CreateOrderRequest.OrderItemRequest>> sellerItemsMap = new LinkedHashMap<>();
        for (CreateOrderRequest.OrderItemRequest itemRequest : request.getItems()) {
            Long sellerId = productMap.get(itemRequest.getProductId()).getSellerId();
            sellerItemsMap.computeIfAbsent(sellerId, k -> new ArrayList<>()).add(itemRequest);
        }

        // 4. 为每个卖家创建订单
        List<OrderVO> createdOrders = new ArrayList<>();
        Map<Long, Integer> deductedStock = new LinkedHashMap<>();

        try {
            for (Map.Entry<Long, List<CreateOrderRequest.OrderItemRequest>> entry : sellerItemsMap.entrySet()) {
                Long sellerId = entry.getKey();
                List<CreateOrderRequest.OrderItemRequest> items = entry.getValue();

                Order order = new Order();
                order.setOrderNo(IdUtil.getSnowflakeNextIdStr());
                order.setUserId(userId);
                order.setSellerId(sellerId);
                order.setStatus(0);
                order.setReceiverName(request.getReceiverName());
                order.setReceiverPhone(request.getReceiverPhone());
                order.setReceiverAddress(request.getReceiverAddress());
                order.setFreightAmount(BigDecimal.ZERO);

                BigDecimal totalAmount = BigDecimal.ZERO;
                List<OrderItem> orderItems = new ArrayList<>();

                for (CreateOrderRequest.OrderItemRequest itemRequest : items) {
                    ProductClient.ProductInfo product = productMap.get(itemRequest.getProductId());

                    OrderItem orderItem = new OrderItem();
                    orderItem.setProductId(itemRequest.getProductId());
                    orderItem.setQuantity(itemRequest.getQuantity());
                    orderItem.setPrice(product.getPrice());
                    orderItem.setTotalAmount(product.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity())));
                    orderItem.setProductName(product.getName());
                    orderItem.setProductImage(product.getMainImage());

                    totalAmount = totalAmount.add(orderItem.getTotalAmount());
                    orderItems.add(orderItem);
                }

                order.setTotalAmount(totalAmount);
                order.setPayAmount(totalAmount.add(order.getFreightAmount()));

                orderMapper.insert(order);

                for (OrderItem orderItem : orderItems) {
                    orderItem.setOrderId(order.getId());
                    orderItemMapper.insert(orderItem);

                    // 扣减库存
                    productClient.deductStock(orderItem.getProductId(), orderItem.getQuantity());
                    deductedStock.put(orderItem.getProductId(),
                            deductedStock.getOrDefault(orderItem.getProductId(), 0) + orderItem.getQuantity());
                }

                OrderVO vo = new OrderVO();
                org.springframework.beans.BeanUtils.copyProperties(order, vo);
                vo.setItems(orderItems);
                createdOrders.add(vo);
            }
        } catch (Exception e) {
            // 补偿：回滚已扣减的库存
            for (Map.Entry<Long, Integer> stockEntry : deductedStock.entrySet()) {
                try {
                    productClient.restoreStock(stockEntry.getKey(), stockEntry.getValue());
                } catch (Exception ex) {
                    log.error("库存回滚失败，productId={}, quantity={}", stockEntry.getKey(), stockEntry.getValue(), ex);
                }
            }
            throw e;
        }

        return createdOrders;
    }

    /**
     * 买家支付订单：{@code 待付款(0) → 待发货(1)}。
     *
     * <h3>本轮修复的「半迁移」</h3>
     * 上一轮把归属校验改成了真实 403，却漏掉紧邻的「订单不存在」分支——
     * 它仍是单参 {@code BusinessException("订单不存在")}
     * （code = {@link ErrorCode#BUSINESS_FAILED}，HTTP 恒为 200）。
     * 于是<b>同一个方法里两套口径</b>：越权 403，不存在 200；
     * 而 {@code GET /order/{id}} 的「不存在」早已是 404。
     * 现改为复用 {@link #requireOrder(Long)}，与 {@link #shipOrder}、
     * {@link #confirmOrder}、{@link #getOrderByIdAndUserId} 完全同构。
     *
     * <p>归属比对同步换成 {@link Objects#equals}：原写法
     * {@code order.getUserId().equals(userId)} 在 {@code order.getUserId()}
     * 为 null 时 NPE → 500，与同类方法的写法也不一致。
     *
     * <p><b>刻意不动</b>「订单状态不正确」分支：它仍是 HTTP 200 + code 500 软失败。
     * 那属于状态机语义（真要改应是 409），不在本轮「不存在口径统一」的范围内，
     * 动它会牵连存量 e2e 断言。
     *
     * @param orderId 订单 id
     * @param userId  操作人 id（必须是下单买家）
     * @throws BusinessException 订单 id 不合法时 400；订单不存在时 404；非本人订单时 403
     */
    public void payOrder(Long orderId, Long userId) {
        Order order = requireOrder(orderId);

        if (!Objects.equals(order.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限操作该订单");
        }

        if (order.getStatus() != 0) {
            // 状态冲突（订单已支付/已取消等），与 shipOrder/confirmOrder 的 409 口径保持一致。
            throw new BusinessException(ErrorCode.CONFLICT, "订单状态不正确");
        }

        order.setStatus(1);
        order.setPayTime(LocalDateTime.now());
        orderMapper.updateById(order);
    }

    /**
     * 卖家发货（存量端点 {@code POST /order/deliver/{id}} 的实现，转调 {@link #shipOrder}）。
     *
     * @param orderId  订单 id
     * @param sellerId 操作人 id
     */
    public void deliverOrder(Long orderId, Long sellerId) {
        shipOrder(orderId, sellerId);
    }

    /**
     * 买家确认收货（存量端点 {@code POST /order/receive/{id}} 的实现，转调 {@link #confirmOrder}）。
     *
     * @param orderId 订单 id
     * @param userId  操作人 id
     */
    public void receiveOrder(Long orderId, Long userId) {
        confirmOrder(orderId, userId);
    }

    /**
     * 卖家发货：{@code 待发货(1) → 待收货(2)}。
     *
     * <h3>状态机约束</h3>
     * <ul>
     *   <li>只有<b>订单所属卖家</b>能发货，其他人（包括买家、其他店铺卖家）一律 <b>403</b>；</li>
     *   <li>已发货 / 已完成 / 已取消 / 已退款的订单再次发货，一律 <b>409</b>，不做幂等静默成功——
     *       重复发货往往意味着卖家后台出了问题，静默成功会把问题藏起来。</li>
     * </ul>
     *
     * <h3>关于「未付款直接发货」</h3>
     * 本项目没有接真实支付，绝大多数订单会永远停在 {@code 待付款(0)}，
     * 于是"必须已完成才能评价"这条规则就变成了死路。
     * 因此当 {@code allowUnpaidShip=true}（默认，见 {@code order.ship.allow-unpaid}）时，
     * 允许从 {@code 待付款(0)} 直接发货，并顺带补上支付时间——
     * 语义上等价于"货到付款/线下已收款"，生产环境可以关掉。
     *
     * <p>请注意这<b>不是</b>放宽状态机：{@code 0 → 3} 的跳跃仍然被禁止，
     * 确认收货依旧必须从 {@code 待收货(2)} 出发，重复发货依旧 409。
     *
     * @param orderId  订单 id
     * @param sellerId 操作人 id（必须是订单卖家）
     */
    @Transactional(rollbackFor = Exception.class)
    public void shipOrder(Long orderId, Long sellerId) {
        Order order = requireOrder(orderId);
        if (!Objects.equals(order.getSellerId(), sellerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有订单所属卖家才能发货");
        }

        int status = order.getStatus() == null ? STATUS_PENDING_PAYMENT : order.getStatus();
        if (status == STATUS_PENDING_PAYMENT) {
            if (!allowUnpaidShip) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "订单尚未付款，不能发货（当前状态：" + statusText(status) + "）");
            }
            log.warn("演示模式：订单 {} 未付款即发货，自动补记支付时间。生产环境请设置 order.ship.allow-unpaid=false",
                    orderId);
            order.setPayTime(LocalDateTime.now());
        } else if (status != STATUS_PENDING_DELIVERY) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "当前订单状态为「" + statusText(status) + "」，不能发货");
        }

        order.setStatus(STATUS_PENDING_RECEIPT);
        order.setDeliveryTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("订单发货成功：orderId={}, sellerId={}, {} -> {}",
                orderId, sellerId, statusText(status), statusText(STATUS_PENDING_RECEIPT));
    }

    /**
     * 买家确认收货：{@code 待收货(2) → 已完成(3)}。
     *
     * <p>这是评价资格的<b>唯一来源</b>——{@code status=3} 才允许写 L1 评价。
     *
     * <h3>状态机约束</h3>
     * <ul>
     *   <li>只有<b>下单买家</b>能确认收货，卖家替买家确认一律 <b>403</b>；</li>
     *   <li>未发货就确认收货（{@code 0/1 → 3}）一律 <b>409</b>，这正是"禁止 0→3 跳跃"；</li>
     *   <li>重复确认一律 <b>409</b>。</li>
     * </ul>
     *
     * @param orderId 订单 id
     * @param userId  操作人 id（必须是下单买家）
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmOrder(Long orderId, Long userId) {
        Order order = requireOrder(orderId);
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有下单买家本人才能确认收货");
        }

        int status = order.getStatus() == null ? STATUS_PENDING_PAYMENT : order.getStatus();
        if (status != STATUS_PENDING_RECEIPT) {
            String hint = (status == STATUS_PENDING_PAYMENT || status == STATUS_PENDING_DELIVERY)
                    ? "订单尚未发货，不能确认收货"
                    : "当前订单状态为「" + statusText(status) + "」，不能确认收货";
            throw new BusinessException(ErrorCode.CONFLICT, hint);
        }

        order.setStatus(STATUS_COMPLETED);
        order.setReceiveTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("订单确认收货成功：orderId={}, userId={}, {} -> {}",
                orderId, userId, statusText(status), statusText(STATUS_COMPLETED));
    }

    /**
     * 购买校验：该用户是否买过该商品，且订单已完成。
     *
     * <p>由 product 服务在写入 L1 评价前通过 {@code GET /order/internal/purchased} 调用。
     * 订单数据的所有权在 order 服务，product 服务<b>不应该也无法</b>直接查 orders 表——
     * 这条接口就是这层边界的具体形态。
     *
     * @param userId    买家 id
     * @param productId 商品 id
     * @return 校验结果，永不为 null
     */
    public PurchaseCheckVO checkPurchased(Long userId, Long productId) {
        PurchaseCheckVO vo = new PurchaseCheckVO();
        if (userId == null || userId <= 0L || productId == null || productId <= 0L) {
            return vo;
        }

        Map<String, Object> completed = orderMapper.selectCompletedPurchase(userId, productId);
        if (completed != null && !completed.isEmpty()) {
            vo.setPurchased(Boolean.TRUE);
            vo.setOrderId(toLong(completed.get("order_id")));
            vo.setOrderItemId(toLong(completed.get("order_item_id")));
            vo.setOrderStatus(toInteger(completed.get("order_status")));
            vo.setReceiveTime(formatTime(completed.get("receive_time")));
            return vo;
        }

        // 没有已完成订单时，再看一眼有没有进行中的订单，好让上游区分"没买过"与"买了没收货"。
        Map<String, Object> latest = orderMapper.selectLatestPurchase(userId, productId);
        if (latest != null && !latest.isEmpty()) {
            vo.setOrderId(toLong(latest.get("order_id")));
            vo.setOrderItemId(toLong(latest.get("order_item_id")));
            vo.setOrderStatus(toInteger(latest.get("order_status")));
        }
        return vo;
    }

    /**
     * 远程取商品信息，不存在即抛「商品不存在，商品ID: x」。
     *
     * <p>与 {@code CartService.requireProductInfo} 同源：product 服务的
     * {@code GET /product/{id}} 本轮已改为真实 <b>404</b>，而本模块 Feign
     * 未配置 {@code ErrorDecoder} 也未开 {@code dismiss404}，非 2xx 一律抛
     * {@link FeignException}，原来的判空分支会变成死代码。
     * 这里把 404 翻译回原文案，保证 {@code createOrder} 对外行为与改动前<b>逐字一致</b>。
     *
     * <p><b>批量语义说明</b>：本方法在 {@code createOrder} 的商品循环里被调用，
     * 单个商品不存在<b>本就应该</b>让整单失败（不能给用户下一张缺货的单），
     * 因此这里刻意<b>不做容错降级</b>。真正需要"缺一个不影响整体"的批量读取场景
     * 走的是 {@code GET /product/batch}（{@code selectBatchIds}，返回子集，不抛 404），
     * 与本方法互不影响。
     *
     * @param productId 商品 id
     * @return 商品信息，永不为 null
     * @throws BusinessException 商品不存在时抛出（软失败，保持存量口径）
     */
    private ProductClient.ProductInfo requireProductInfo(Long productId) {
        Result<ProductClient.ProductInfo> productResult;
        try {
            productResult = productClient.getProductById(productId);
        } catch (FeignException.NotFound e) {
            throw new BusinessException("商品不存在，商品ID: " + productId);
        }
        if (productResult == null || productResult.getData() == null) {
            throw new BusinessException("商品不存在，商品ID: " + productId);
        }
        return productResult.getData();
    }

    /**
     * 查询订单，不存在即 404。
     *
     * @param orderId 订单 id
     * @return 订单
     */
    private Order requireOrder(Long orderId) {
        if (orderId == null || orderId <= 0L) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单ID不合法");
        }
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    /**
     * 订单状态码转中文，取值与 {@code database/init.sql} 的字段注释严格一致。
     *
     * @param status 状态码
     * @return 中文描述
     */
    private String statusText(int status) {
        switch (status) {
            case STATUS_PENDING_PAYMENT:
                return "待付款";
            case STATUS_PENDING_DELIVERY:
                return "待发货";
            case STATUS_PENDING_RECEIPT:
                return "待收货";
            case STATUS_COMPLETED:
                return "已完成";
            case STATUS_CANCELLED:
                return "已取消";
            case STATUS_REFUNDED:
                return "已退款";
            default:
                return "未知(" + status + ")";
        }
    }

    /**
     * @param value 原始值
     * @return Long，无法转换时返回 null
     */
    private Long toLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    /**
     * @param value 原始值
     * @return Integer，无法转换时返回 null
     */
    private Integer toInteger(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    /**
     * 把 JDBC 取回的时间值格式化为 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * @param value 原始值，可能是 {@code LocalDateTime} 或 {@code java.sql.Timestamp}
     * @return 格式化字符串，无值时返回 null
     */
    private String formatTime(Object value) {
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(TIME_FORMATTER);
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime().format(TIME_FORMATTER);
        }
        return value == null ? null : value.toString();
    }

    /**
     * 买家取消订单：{@code 待付款(0) → 已取消(4)}，并回滚已扣减的库存。
     *
     * <p>与 {@link #payOrder} 同源的「半迁移」与同样的修法：不存在走
     * {@link #requireOrder(Long)}（400/404），越权保持 403。
     *
     * <p><b>刻意不动</b>「只能取消待付款的订单」分支（仍为 200 + code 500 软失败），
     * 理由同 {@link #payOrder}。
     *
     * @param orderId 订单 id
     * @param userId  操作人 id（必须是下单买家）
     * @throws BusinessException 订单 id 不合法时 400；订单不存在时 404；非本人订单时 403
     */
    public void cancelOrder(Long orderId, Long userId) {
        Order order = requireOrder(orderId);

        if (!Objects.equals(order.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限操作该订单");
        }

        if (order.getStatus() != 0) {
            // 状态冲突：订单已流转出「待付款」，取消请求与当前状态冲突 → 409。
            throw new BusinessException(ErrorCode.CONFLICT, "只能取消待付款的订单");
        }

        List<OrderItem> items = getOrderItemsByOrderId(orderId);
        for (OrderItem item : items) {
            productClient.restoreStock(item.getProductId(), item.getQuantity());
        }

        order.setStatus(4);
        orderMapper.updateById(order);
    }

    public Order getOrderById(Long orderId) {
        return orderMapper.selectById(orderId);
    }

    /**
     * 按 id 查订单，并做归属校验：调用方必须是<b>下单买家</b>或<b>订单所属卖家</b>。
     *
     * <h3>为什么不再用「id + 归属」拼成一条 SQL</h3>
     * 原实现把两个条件塞进同一个 {@code WHERE}：
     * {@code id = ? AND (user_id = ? OR seller_id = ?)}，
     * 于是「订单不存在」和「订单存在但不是你的」<b>都返回 null</b>，
     * 调用方拿到 null 无从区分，Controller 只能原样包成
     * {@code HTTP 200 + data:null + success:true}——
     * 越权访问被判定为"成功"，比返回 500 还糟：
     * 任何按 {@code success} 字段做判断的客户端都会认为这次读取是合法的。
     *
     * <p>现在拆成两步，语义各归各位：先按主键查，查不到即
     * {@link ErrorCode#NOT_FOUND}(404)；查到了再比对归属，不匹配即
     * {@link ErrorCode#FORBIDDEN}(403)。与同类中 {@link #shipOrder}、
     * {@link #confirmOrder} 的写法保持一致。
     *
     * <p><b>不存在与越权刻意返回不同状态码</b>：本接口在网关侧已要求登录并持有
     * {@code order:read}，调用方本就是可信身份，区分 404/403 带来的
     * 订单 id 存在性泄露可以接受，换来的是调用方能拿到可操作的错误语义。
     *
     * @param orderId 订单 id
     * @param userId  操作人 id（网关下发的 {@code X-User-Id}）
     * @return 订单，<b>永不为 null</b>
     * @throws BusinessException 订单不存在时 404；订单存在但不属于该用户时 403
     */
    public Order getOrderByIdAndUserId(Long orderId, Long userId) {
        Order order = requireOrder(orderId);
        if (!Objects.equals(order.getUserId(), userId)
                && !Objects.equals(order.getSellerId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限查看该订单");
        }
        return order;
    }

    public List<Order> getOrdersByUserId(Long userId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId)
               .orderByDesc(Order::getCreateTime)
               .orderByDesc(Order::getId);
        return orderMapper.selectList(wrapper);
    }

    public List<Order> getOrdersBySellerId(Long sellerId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getSellerId, sellerId)
               .orderByDesc(Order::getCreateTime)
               .orderByDesc(Order::getId);
        return orderMapper.selectList(wrapper);
    }

    public PageResult<OrderVO> getOrdersWithItemsByUserId(Long userId, Integer current, Integer size) {
        Page<Order> page = new Page<>(current, size);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        // 必须带上主键做兜底排序键：createTime 允许并列（历史数据甚至整列为 NULL），
        // 排序键一旦并列，LIMIT/OFFSET 在不同页之间就没有确定的行序，
        // 同一行可能重复出现在两页里、另一行则永远被跳过 —— 即「翻到底仍然丢单」。
        wrapper.eq(Order::getUserId, userId)
               .orderByDesc(Order::getCreateTime)
               .orderByDesc(Order::getId);
        Page<Order> orderPage = orderMapper.selectPage(page, wrapper);
        List<OrderVO> voList = convertToVOList(orderPage.getRecords());
        return new PageResult<>(voList, orderPage.getTotal(), orderPage.getSize(), orderPage.getCurrent());
    }

    public PageResult<OrderVO> getOrdersWithItemsBySellerId(Long sellerId, Integer current, Integer size) {
        Page<Order> page = new Page<>(current, size);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        // 同上：主键兜底是分页确定性的硬要求，缺了它卖家永远看不到约 20% 的订单。
        wrapper.eq(Order::getSellerId, sellerId)
               .orderByDesc(Order::getCreateTime)
               .orderByDesc(Order::getId);
        Page<Order> orderPage = orderMapper.selectPage(page, wrapper);
        List<OrderVO> voList = convertToVOList(orderPage.getRecords());
        return new PageResult<>(voList, orderPage.getTotal(), orderPage.getSize(), orderPage.getCurrent());
    }

    private List<OrderVO> convertToVOList(List<Order> orders) {
        return orders.stream().map(order -> {
            OrderVO vo = new OrderVO();
            org.springframework.beans.BeanUtils.copyProperties(order, vo);
            vo.setItems(getOrderItemsByOrderId(order.getId()));
            return vo;
        }).collect(Collectors.toList());
    }

    public List<OrderItem> getOrderItemsByOrderId(Long orderId) {
        LambdaQueryWrapper<OrderItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderItem::getOrderId, orderId);
        return orderItemMapper.selectList(wrapper);
    }
}
