package com.ecommerce.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.security.GatewayUserContext;
import com.ecommerce.product.client.OrderClient;
import com.ecommerce.product.dto.CommentAskDTO;
import com.ecommerce.product.dto.CommentCreateDTO;
import com.ecommerce.product.dto.CommentReplyDTO;
import com.ecommerce.product.dto.CommentUpdateDTO;
import com.ecommerce.product.entity.Comment;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.mapper.CommentMapper;
import com.ecommerce.product.mapper.ProductMapper;
import com.ecommerce.product.util.MaskUtil;
import com.ecommerce.product.vo.CanReviewVO;
import com.ecommerce.product.vo.CommentTreeVO;
import com.ecommerce.product.vo.CommentVO;
import com.ecommerce.product.vo.RatingSummaryVO;
import com.ecommerce.product.vo.ViewerContextVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 三级评论服务。
 *
 * <h3>为什么是"重做"而不是"扩展 ReviewService"</h3>
 * 存量 {@code ReviewService.addReview()} 直接 {@code insert}，<b>零校验</b>——
 * 任何登录用户都能给任何商品刷评价、给自己的商品刷五星、对同一订单无限刷。
 * 这是安全缺陷不是功能缺失，补丁式加判断很容易漏，所以整条写入链路在这里重建，
 * {@code ReviewService} 降级为只读兼容层。
 *
 * <h3>层级不变式（服务端强制，永不信任前端传值）</h3>
 * <pre>
 *   L1 买家评价  type=1  parentId=0        rootId=自身id   rating必填  orderId非空
 *   L2 卖家回复  type=2  parentId=L1.id    rootId=L1.id    rating=null orderId=null
 *   L3 第三方追问 type=3  parentId=L1.id    rootId=L1.id    rating=null orderId=null
 * </pre>
 * <b>L3 的 parentId 恒为 L1 的 id，而不是"被回复那条"的 id</b>——
 * 这是"永不出现第 4 层"的结构性保证：树深被钉死在 2，
 * 「回复某个人」只落在 {@code replyToUserId} 上，是展示信息而非结构信息。
 *
 * <h3>事务与锁</h3>
 * 写路径统一用 {@link TransactionTemplate} 显式控制事务边界，而不是 {@code @Transactional}：
 * <ul>
 *   <li>Feign 调用（购买校验）留在事务<b>外</b>，避免网络 IO 长时间占着数据库连接；</li>
 *   <li>L2 的 Redis 互斥锁能做到<b>先提交、后释放</b>，
 *       用 {@code @Transactional} 则会在提交前就放锁，留下并发双回复的窗口。</li>
 * </ul>
 */
@Service
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    /** L1 列表默认页大小。 */
    private static final int L1_DEFAULT_PAGE_SIZE = 10;

    /** L1 列表最大页大小。 */
    private static final int L1_MAX_PAGE_SIZE = 50;

    /** 每条 L1 默认展示的追问条数。 */
    private static final int L3_DEFAULT_PREVIEW = 3;

    /** 追问预览的最大条数。 */
    private static final int L3_MAX_PREVIEW = 50;

    /** L1 正文长度下限。 */
    private static final int L1_CONTENT_MIN = 5;

    /** L1 / L2 正文长度上限。 */
    private static final int CONTENT_MAX = 500;

    /** L3 正文长度上限。 */
    private static final int L3_CONTENT_MAX = 200;

    /** 评论可编辑时长（小时）。 */
    private static final long EDIT_WINDOW_HOURS = 24L;

    /** L2 回复互斥锁前缀。 */
    private static final String REPLY_LOCK_PREFIX = "lock:comment:reply:";

    /** L2 回复互斥锁 TTL（秒）。 */
    private static final long REPLY_LOCK_TTL_SECONDS = 5L;

    /** 商品缓存键前缀，与 {@code ProductService} 保持一致。 */
    private static final String PRODUCT_CACHE_PREFIX = "product:";

    /** 订单状态：已完成。 */
    private static final int ORDER_STATUS_COMPLETED = 3;

    /** 排序方式：按评分从高到低。 */
    private static final String SORT_RATING = "rating";

    /** 管理员角色码，可删除任意评论。 */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** 未购买时的统一拒绝文案（任务书指定）。 */
    private static final String DENY_NOT_PURCHASED = "购买并确认收货后才能评价";

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderClient orderClient;

    @Autowired
    private UserNameResolver userNameResolver;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // ==================================================================================
    // 写路径
    // ==================================================================================

    /**
     * 发表 L1 买家评价。
     *
     * <p>校验链（任一不过即抛，顺序刻意由"便宜"到"昂贵"）：
     * <ol>
     *   <li>商品存在 → 否则 404；</li>
     *   <li>不是自己的商品 → 否则 403（防刷好评）；</li>
     *   <li>正文 ≥ 5 字、评分 1-5 → 否则 400；</li>
     *   <li>存在<b>已完成</b>（status=3）的购买记录 → 否则 403；</li>
     *   <li>该订单下的该商品尚未评价过 → 否则 409。</li>
     * </ol>
     *
     * @param userId 当前登录用户 id
     * @param dto    入参
     * @return 新建评价
     */
    public CommentVO createL1(long userId, CommentCreateDTO dto) {
        Product product = requireProduct(dto.getProductId());
        if (Objects.equals(product.getSellerId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能评价自己出售的商品");
        }

        String content = requireContent(dto.getContent(), L1_CONTENT_MIN, CONTENT_MAX,
                "评价内容需 " + L1_CONTENT_MIN + "-" + CONTENT_MAX + " 字");
        Integer rating = dto.getRating();
        if (rating == null || rating < 1 || rating > 5) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评分必须为 1-5 星");
        }

        // 购买校验：跨服务调用放在事务外。
        OrderClient.PurchaseCheck purchase = queryPurchase(userId, product.getId());
        if (!purchase.purchasedFlag()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, notPurchasedReason(purchase.getOrderStatus()));
        }
        if (hasReviewed(userId, product.getId(), purchase.getOrderId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "该订单的这件商品您已评价过，不能重复评价");
        }

        Comment comment = new Comment();
        comment.setUserId(userId);
        comment.setUsername(userNameResolver.resolveName(userId));
        comment.setProductId(product.getId());
        comment.setOrderId(purchase.getOrderId());
        comment.setOrderItemId(purchase.getOrderItemId());
        comment.setParentId(Comment.NO_PARENT);
        comment.setRootId(Comment.NO_PARENT);
        comment.setType(Comment.TYPE_L1_REVIEW);
        comment.setSellerId(product.getSellerId());
        comment.setReplyCount(0);
        comment.setRating(rating);
        comment.setContent(content);
        comment.setImages(dto.getImages());
        comment.setStatus(Comment.STATUS_VISIBLE);
        comment.setDeleted(0);

        transactionTemplate.executeWithoutResult(status -> {
            try {
                commentMapper.insert(comment);
            } catch (DuplicateKeyException e) {
                // uk_order_product_user 是最后一道闸：并发双写时前面的查询都可能同时放行。
                throw new BusinessException(ErrorCode.CONFLICT, "该订单的这件商品您已评价过，不能重复评价", e);
            }
            // 自增主键在 insert 之前不可知，rootId 只能事后回写为自身 id。
            commentMapper.fixRootIdSelf(comment.getId());
            productMapper.refreshRating(product.getId());
        });

        comment.setRootId(comment.getId());
        evictProductCache(product.getId());
        log.info("L1 评价创建成功：commentId={}, userId={}, productId={}, orderId={}",
                comment.getId(), userId, product.getId(), purchase.getOrderId());
        return toVO(comment, userId, Collections.emptyMap());
    }

    /**
     * 发表 L2 卖家回复。
     *
     * <p>"每条 L1 最多一条有效 L2"这个约束 MySQL 建不了部分唯一索引（{@code deleted} 参与条件），
     * 所以由 <b>Redis 互斥锁 + 存在性查询</b> 共同保证：
     * 锁挡住并发，查询挡住串行重复。逻辑删除后查询返回 0，因此<b>允许删了再回</b>。
     *
     * @param userId 当前登录用户 id（必须是商品所属卖家）
     * @param dto    入参
     * @return 新建回复
     */
    public CommentVO replyL2(long userId, CommentReplyDTO dto) {
        Comment parent = requireComment(dto.getParentId());
        if (!parent.isL1()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只能回复买家评价");
        }

        Product product = requireProduct(parent.getProductId());
        if (!Objects.equals(product.getSellerId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有该商品所属卖家才能回复这条评价");
        }

        String content = requireContent(dto.getContent(), 1, CONTENT_MAX, "回复内容需 1-" + CONTENT_MAX + " 字");

        String lockKey = REPLY_LOCK_PREFIX + parent.getId();
        String lockToken = UUID.randomUUID().toString();
        if (!tryLock(lockKey, lockToken)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该评价正在被回复，请稍后重试");
        }

        try {
            if (commentMapper.countValidReply(parent.getId()) > 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "该评价已回复过，不能重复回复");
            }

            Comment reply = new Comment();
            reply.setUserId(userId);
            reply.setUsername(userNameResolver.resolveName(userId));
            reply.setProductId(product.getId());
            reply.setOrderId(null);
            reply.setOrderItemId(null);
            reply.setParentId(parent.getId());
            reply.setRootId(parent.getId());
            reply.setType(Comment.TYPE_L2_REPLY);
            reply.setSellerId(product.getSellerId());
            reply.setReplyCount(0);
            reply.setRating(null);
            reply.setContent(content);
            reply.setStatus(Comment.STATUS_VISIBLE);
            reply.setDeleted(0);

            transactionTemplate.executeWithoutResult(status -> {
                commentMapper.insert(reply);
                commentMapper.updateReplyCount(parent.getId(), commentMapper.countChildren(parent.getId()));
            });

            log.info("L2 回复创建成功：commentId={}, sellerId={}, parentId={}",
                    reply.getId(), userId, parent.getId());
            return toVO(reply, userId, Collections.emptyMap());
        } finally {
            // 事务已在 executeWithoutResult 返回时提交，此刻放锁不留并发窗口。
            unlock(lockKey, lockToken);
        }
    }

    /**
     * 发表 L3 第三方追问。
     *
     * <p><b>层级归一</b>是这里唯一的关键动作：不论前端传来的 {@code commentId} 是 L1、L2 还是 L3，
     * 落库的 {@code parentId} 与 {@code rootId} 一律指向所属的那条 L1。
     * 被回复者只写进 {@code replyToUserId / replyToUsername} 供前端渲染 {@code @昵称}。
     *
     * @param userId 当前登录用户 id（任意登录用户均可）
     * @param dto    入参
     * @return 新建追问
     */
    public CommentVO askL3(long userId, CommentAskDTO dto) {
        Comment target = requireComment(dto.getCommentId());
        Comment root = target.isL1() ? target : requireComment(normalizeRootId(target));
        if (!root.isL1()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评论层级异常，无法追问");
        }

        Product product = requireProduct(root.getProductId());
        String content = requireContent(dto.getContent(), 1, L3_CONTENT_MAX,
                "追问内容需 1-" + L3_CONTENT_MAX + " 字");

        Comment ask = new Comment();
        ask.setUserId(userId);
        ask.setUsername(userNameResolver.resolveName(userId));
        ask.setProductId(product.getId());
        ask.setOrderId(null);
        ask.setOrderItemId(null);
        // 关键：parentId / rootId 都指向 L1，而不是 target。
        ask.setParentId(root.getId());
        ask.setRootId(root.getId());
        ask.setType(Comment.TYPE_L3_ASK);
        ask.setSellerId(product.getSellerId());
        ask.setReplyCount(0);
        ask.setRating(null);
        ask.setContent(content);
        ask.setStatus(Comment.STATUS_VISIBLE);
        ask.setDeleted(0);

        // 回复自己或回复 L1 作者时无需 @，避免出现"@自己"这种噪音。
        if (!target.isL1() && !Objects.equals(target.getUserId(), userId)) {
            ask.setReplyToUserId(target.getUserId());
            ask.setReplyToUsername(displayNameOf(target, Collections.emptyMap()));
        }

        transactionTemplate.executeWithoutResult(status -> {
            commentMapper.insert(ask);
            commentMapper.updateReplyCount(root.getId(), commentMapper.countChildren(root.getId()));
        });

        log.info("L3 追问创建成功：commentId={}, userId={}, rootId={}, targetId={}",
                ask.getId(), userId, root.getId(), target.getId());
        return toVO(ask, userId, Collections.emptyMap());
    }

    /**
     * 编辑评论（发表后 24 小时内，仅作者本人）。
     *
     * @param userId    当前登录用户 id
     * @param commentId 评论 id
     * @param dto       入参
     * @return 编辑后的评论
     */
    public CommentVO updateComment(long userId, Long commentId, CommentUpdateDTO dto) {
        Comment comment = requireComment(commentId);
        if (!Objects.equals(comment.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能编辑自己发表的内容");
        }
        if (isEditWindowExpired(comment.getCreateTime())) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "发表超过 " + EDIT_WINDOW_HOURS + " 小时的内容不可编辑");
        }

        int maxLength = comment.isL3() ? L3_CONTENT_MAX : CONTENT_MAX;
        int minLength = comment.isL1() ? L1_CONTENT_MIN : 1;
        String content = requireContent(dto.getContent(), minLength, maxLength,
                "内容需 " + minLength + "-" + maxLength + " 字");

        boolean ratingChanged = false;
        Comment patch = new Comment();
        patch.setId(comment.getId());
        patch.setContent(content);
        if (dto.getImages() != null) {
            patch.setImages(dto.getImages());
        }
        if (comment.isL1() && dto.getRating() != null) {
            if (dto.getRating() < 1 || dto.getRating() > 5) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "评分必须为 1-5 星");
            }
            patch.setRating(dto.getRating());
            ratingChanged = !Objects.equals(comment.getRating(), dto.getRating());
        }

        final boolean needRefresh = ratingChanged;
        transactionTemplate.executeWithoutResult(status -> {
            commentMapper.updateById(patch);
            if (needRefresh) {
                productMapper.refreshRating(comment.getProductId());
            }
        });

        if (needRefresh) {
            evictProductCache(comment.getProductId());
        }
        Comment latest = commentMapper.selectById(commentId);
        return toVO(latest == null ? comment : latest, userId, Collections.emptyMap());
    }

    /**
     * 删除评论（逻辑删除）。
     *
     * <p>只有<b>作者本人</b>或管理员可删。卖家<b>不能</b>删买家的 L1 差评——
     * 这条规则不需要额外分支，"仅作者可删"已经天然覆盖。
     *
     * <p>L1 被删后，其下所有 L2/L3 会自动从查询结果中消失：
     * 子级一律通过 {@code root_id IN (可见 L1 集合)} 拉取，父级不在集合里，子级自然查不出来。
     * 无需物理级联更新，也就不存在"级联漏改"的可能。
     *
     * @param userId    当前登录用户 id
     * @param commentId 评论 id
     */
    public void deleteComment(long userId, Long commentId) {
        Comment comment = requireComment(commentId);
        boolean isAdmin = GatewayUserContext.hasRole(ROLE_ADMIN);
        if (!isAdmin && !Objects.equals(comment.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除自己发表的内容");
        }

        transactionTemplate.executeWithoutResult(status -> {
            commentMapper.deleteById(commentId);
            if (comment.isL1()) {
                productMapper.refreshRating(comment.getProductId());
            } else if (comment.getRootId() != null && comment.getRootId() > 0L) {
                commentMapper.updateReplyCount(comment.getRootId(),
                        commentMapper.countChildren(comment.getRootId()));
            }
        });

        if (comment.isL1()) {
            evictProductCache(comment.getProductId());
        }
        log.info("评论删除成功：commentId={}, operatorId={}, admin={}", commentId, userId, isAdmin);
    }

    // ==================================================================================
    // 读路径
    // ==================================================================================

    /**
     * 查询商品评论树（游客可读）。
     *
     * <p>查询过程固定 <b>2 次 SQL</b>（不含汇总）：一次分页取 L1，一次按 rootId 批量取全部子级。
     * 昵称走 {@code comment.username} 快照，正常情况下<b>不产生任何跨服务调用</b>。
     *
     * @param productId 商品 id
     * @param pageNum   L1 页码，从 1 开始
     * @param pageSize  L1 页大小，默认 10，上限 50
     * @param sort      排序：{@code rating} 按评分降序，其余按时间降序
     * @param askSize   每条 L1 展示的追问条数，默认 3
     * @param viewerId  浏览者 id，游客传 0
     * @return 评论树
     */
    public CommentTreeVO getProductComments(Long productId, Integer pageNum, Integer pageSize,
                                            String sort, Integer askSize, long viewerId) {
        Product product = requireProduct(productId);
        long page = normalize(pageNum, 1, 1, Integer.MAX_VALUE);
        long size = normalize(pageSize, L1_DEFAULT_PAGE_SIZE, 1, L1_MAX_PAGE_SIZE);
        int previewSize = (int) normalize(askSize, L3_DEFAULT_PREVIEW, 0, L3_MAX_PREVIEW);

        Page<Comment> l1Page = new Page<>(page, size);
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getProductId, productId)
                .eq(Comment::getType, Comment.TYPE_L1_REVIEW)
                .eq(Comment::getStatus, Comment.STATUS_VISIBLE);
        if (SORT_RATING.equalsIgnoreCase(sort)) {
            wrapper.orderByDesc(Comment::getRating).orderByDesc(Comment::getCreateTime).orderByDesc(Comment::getId);
        } else {
            wrapper.orderByDesc(Comment::getCreateTime).orderByDesc(Comment::getId);
        }
        Page<Comment> result = commentMapper.selectPage(l1Page, wrapper);
        List<Comment> roots = result.getRecords() == null ? new ArrayList<>() : result.getRecords();

        List<Comment> children = loadChildren(roots);
        Map<Long, String> nameFallback = resolveMissingNames(roots, children);
        List<CommentVO> voList = assemble(roots, children, previewSize, viewerId, nameFallback);

        CommentTreeVO tree = new CommentTreeVO();
        tree.setComments(new PageResult<>(voList, result.getTotal(), result.getSize(), result.getCurrent()));
        tree.setSummary(buildSummary(product));
        tree.setViewerContext(buildViewerContext(viewerId, product));
        return tree;
    }

    /**
     * 展开某条 L1 之下的全部追问（"查看更多追问"）。
     *
     * @param rootId   L1 评论 id
     * @param pageNum  页码，从 1 开始
     * @param pageSize 页大小，默认 10，上限 50
     * @param viewerId 浏览者 id，游客传 0
     * @return 追问分页（时间正序，保持对话感）
     */
    public PageResult<CommentVO> getAsks(Long rootId, Integer pageNum, Integer pageSize, long viewerId) {
        Comment root = requireComment(rootId);
        if (!root.isL1()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只能展开买家评价下的追问");
        }
        if (root.getStatus() == null || root.getStatus() != Comment.STATUS_VISIBLE) {
            // 父级不可见时，子级一并不可见，对外表现为"不存在"。
            throw new BusinessException(ErrorCode.NOT_FOUND, "评论不存在或已被删除");
        }

        long page = normalize(pageNum, 1, 1, Integer.MAX_VALUE);
        long size = normalize(pageSize, L1_DEFAULT_PAGE_SIZE, 1, L1_MAX_PAGE_SIZE);
        Page<Comment> askPage = new Page<>(page, size);
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getRootId, rootId)
                .eq(Comment::getType, Comment.TYPE_L3_ASK)
                .eq(Comment::getStatus, Comment.STATUS_VISIBLE)
                .orderByAsc(Comment::getCreateTime)
                .orderByAsc(Comment::getId);
        Page<Comment> result = commentMapper.selectPage(askPage, wrapper);
        List<Comment> records = result.getRecords() == null ? new ArrayList<>() : result.getRecords();

        Map<Long, String> nameFallback = resolveMissingNames(records, Collections.emptyList());
        List<CommentVO> voList = new ArrayList<>(records.size());
        for (Comment record : records) {
            voList.add(toVO(record, viewerId, nameFallback));
        }
        return new PageResult<>(voList, result.getTotal(), result.getSize(), result.getCurrent());
    }

    /**
     * 判定当前用户能否评价某商品，供前端提前置灰入口。
     *
     * <p>与 {@link #createL1} 走<b>同一段判定逻辑</b>，不会出现"按钮亮着但点了报错"。
     *
     * @param userId    当前登录用户 id
     * @param productId 商品 id
     * @return 判定结果
     */
    public CanReviewVO canReview(long userId, Long productId) {
        Product product = requireProduct(productId);
        return evaluateReviewEligibility(userId, product);
    }

    // ==================================================================================
    // 内部实现
    // ==================================================================================

    /**
     * 执行完整的"能否评价"判定。
     *
     * @param userId  用户 id，0 表示游客
     * @param product 商品
     * @return 判定结果，永不为 null
     */
    private CanReviewVO evaluateReviewEligibility(long userId, Product product) {
        CanReviewVO vo = new CanReviewVO();
        if (userId <= 0L) {
            vo.setReason("登录后才能评价");
            return vo;
        }
        if (Objects.equals(product.getSellerId(), userId)) {
            vo.setReason("不能评价自己出售的商品");
            return vo;
        }

        OrderClient.PurchaseCheck purchase;
        try {
            purchase = queryPurchase(userId, product.getId());
        } catch (BusinessException e) {
            // 判定接口是"体验优化"，下游抖动时降级为不可评价 + 明确原因，而不是让整个页面 500。
            vo.setReason(e.getMessage());
            return vo;
        }
        vo.setOrderStatus(purchase.getOrderStatus());
        if (!purchase.purchasedFlag()) {
            vo.setReason(notPurchasedReason(purchase.getOrderStatus()));
            return vo;
        }

        vo.setOrderId(purchase.getOrderId());
        vo.setOrderItemId(purchase.getOrderItemId());
        if (hasReviewed(userId, product.getId(), purchase.getOrderId())) {
            vo.setReason("该订单的这件商品您已评价过");
            return vo;
        }

        vo.setCanReview(true);
        return vo;
    }

    /**
     * 组装浏览者上下文。
     *
     * @param viewerId 浏览者 id，游客为 0
     * @param product  商品
     * @return 上下文
     */
    private ViewerContextVO buildViewerContext(long viewerId, Product product) {
        ViewerContextVO context = new ViewerContextVO();
        boolean loggedIn = viewerId > 0L;
        context.setLoggedIn(loggedIn);
        context.setUserId(loggedIn ? viewerId : null);

        if (!loggedIn) {
            context.setSeller(false);
            context.setCanReview(false);
            context.setReviewDeniedReason("登录后才能评价");
            context.setCanReply(false);
            context.setReplyDeniedReason("登录后才能回复");
            context.setCanAsk(false);
            context.setAskDeniedReason("登录后才能追问");
            return context;
        }

        boolean isSeller = Objects.equals(product.getSellerId(), viewerId);
        context.setSeller(isSeller);

        context.setCanAsk(true);
        context.setCanReply(isSeller);
        if (!isSeller) {
            context.setReplyDeniedReason("只有该商品所属卖家才能回复评价");
        }

        CanReviewVO eligibility = evaluateReviewEligibility(viewerId, product);
        context.setCanReview(eligibility.isCanReview());
        context.setReviewDeniedReason(eligibility.getReason());
        return context;
    }

    /**
     * 组装 L1 → (L2, L3 预览) 的树形结构。
     *
     * @param roots        L1 列表
     * @param children     这些 L1 之下的全部子级
     * @param previewSize  每条 L1 展示的追问条数
     * @param viewerId     浏览者 id
     * @param nameFallback 昵称兜底映射
     * @return L1 展示对象列表
     */
    private List<CommentVO> assemble(List<Comment> roots, List<Comment> children, int previewSize,
                                     long viewerId, Map<Long, String> nameFallback) {
        Map<Long, Comment> replyByRoot = new HashMap<>();
        Map<Long, List<Comment>> asksByRoot = new LinkedHashMap<>();
        for (Comment child : children) {
            Long rootId = child.getRootId();
            if (rootId == null) {
                continue;
            }
            if (child.isL2()) {
                // Mapper 已按 create_time ASC 排序，putIfAbsent 保证取到最早的那条。
                replyByRoot.putIfAbsent(rootId, child);
            } else if (child.isL3()) {
                asksByRoot.computeIfAbsent(rootId, key -> new ArrayList<>()).add(child);
            }
        }

        List<CommentVO> voList = new ArrayList<>(roots.size());
        for (Comment root : roots) {
            CommentVO vo = toVO(root, viewerId, nameFallback);

            Comment reply = replyByRoot.get(root.getId());
            if (reply != null) {
                vo.setReply(toVO(reply, viewerId, nameFallback));
            }

            List<Comment> asks = asksByRoot.getOrDefault(root.getId(), Collections.emptyList());
            int total = asks.size();
            int preview = Math.min(previewSize, total);
            List<CommentVO> askVos = new ArrayList<>(preview);
            for (int i = 0; i < preview; i++) {
                askVos.add(toVO(asks.get(i), viewerId, nameFallback));
            }
            vo.setAsks(askVos);
            vo.setAskTotal(total);
            vo.setAskHasMore(total > preview);
            // 以实查条数为准，屏蔽存量脏数据里可能漂掉的 reply_count。
            vo.setReplyCount(total + (reply == null ? 0 : 1));

            voList.add(vo);
        }
        return voList;
    }

    /**
     * 批量拉取子级。
     *
     * @param roots L1 列表
     * @return 子级列表，永不为 null
     */
    private List<Comment> loadChildren(List<Comment> roots) {
        if (roots.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> rootIds = new ArrayList<>(roots.size());
        for (Comment root : roots) {
            rootIds.add(root.getId());
        }
        List<Comment> children = commentMapper.selectChildrenByRootIds(rootIds);
        return children == null ? Collections.emptyList() : children;
    }

    /**
     * 为快照缺失的记录批量回源昵称。
     *
     * <p>存量数据（migration-v3 之前写入的评论）没有 {@code username} 快照，
     * 这里只为<b>确实缺失</b>的那部分 id 回源，正常数据一次 RPC 都不会发。
     *
     * @param roots    L1 列表
     * @param children 子级列表
     * @return {@code userId → username} 兜底映射
     */
    private Map<Long, String> resolveMissingNames(List<Comment> roots, List<Comment> children) {
        Set<Long> missing = new HashSet<>();
        collectMissingNames(roots, missing);
        collectMissingNames(children, missing);
        if (missing.isEmpty()) {
            return Collections.emptyMap();
        }
        return userNameResolver.resolveNames(missing);
    }

    /**
     * 收集缺失昵称快照的用户 id。
     *
     * @param comments 评论列表
     * @param sink     收集容器
     */
    private void collectMissingNames(List<Comment> comments, Set<Long> sink) {
        for (Comment comment : comments) {
            if (isBlank(comment.getUsername()) && comment.getUserId() != null) {
                sink.add(comment.getUserId());
            }
            if (comment.getReplyToUserId() != null && isBlank(comment.getReplyToUsername())) {
                sink.add(comment.getReplyToUserId());
            }
        }
    }

    /**
     * 实体转展示对象（含脱敏）。
     *
     * @param comment      评论实体
     * @param viewerId     浏览者 id
     * @param nameFallback 昵称兜底映射
     * @return 展示对象
     */
    private CommentVO toVO(Comment comment, long viewerId, Map<Long, String> nameFallback) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setUserId(comment.getUserId());
        vo.setNickname(MaskUtil.maskUsername(displayNameOf(comment, nameFallback)));
        vo.setType(comment.getType());
        vo.setParentId(comment.getParentId());
        vo.setRootId(comment.getRootId());
        vo.setRating(comment.getRating());
        vo.setContent(comment.getContent());
        vo.setImages(comment.getImages());
        vo.setReplyToUserId(comment.getReplyToUserId());
        if (comment.getReplyToUserId() != null) {
            String replyToName = comment.getReplyToUsername();
            if (isBlank(replyToName)) {
                replyToName = nameFallback.get(comment.getReplyToUserId());
            }
            vo.setReplyToNickname(MaskUtil.maskUsername(replyToName));
        }
        vo.setReplyCount(comment.getReplyCount() == null ? 0 : comment.getReplyCount());
        vo.setCreateTime(comment.getCreateTime());
        vo.setSellerReply(comment.isL2());
        vo.setMine(viewerId > 0L && Objects.equals(comment.getUserId(), viewerId));
        return vo;
    }

    /**
     * 取评论作者的原始昵称（快照优先，其次兜底映射）。
     *
     * @param comment      评论
     * @param nameFallback 兜底映射
     * @return 原始昵称，取不到时为 null
     */
    private String displayNameOf(Comment comment, Map<Long, String> nameFallback) {
        if (!isBlank(comment.getUsername())) {
            return comment.getUsername();
        }
        return comment.getUserId() == null ? null : nameFallback.get(comment.getUserId());
    }

    /**
     * 组装评分汇总。
     *
     * @param product 商品
     * @return 汇总对象
     */
    private RatingSummaryVO buildSummary(Product product) {
        RatingSummaryVO summary = new RatingSummaryVO();
        summary.setRatingAvg(product.getRatingAvg() == null ? BigDecimal.ZERO : product.getRatingAvg());
        summary.setRatingCount(product.getRatingCount() == null ? 0 : product.getRatingCount());

        Map<Integer, Integer> distribution = new LinkedHashMap<>();
        for (int star = 1; star <= 5; star++) {
            distribution.put(star, 0);
        }
        List<Map<String, Object>> rows = commentMapper.selectRatingDistribution(product.getId());
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Integer star = toInt(row.get("rating"));
                Integer count = toInt(row.get("cnt"));
                if (star != null && star >= 1 && star <= 5) {
                    distribution.put(star, count == null ? 0 : count);
                }
            }
        }
        summary.setDistribution(distribution);
        return summary;
    }

    /**
     * 调用订单服务做购买校验。
     *
     * @param userId    用户 id
     * @param productId 商品 id
     * @return 校验结果，永不为 null
     */
    private OrderClient.PurchaseCheck queryPurchase(long userId, Long productId) {
        Result<OrderClient.PurchaseCheck> response;
        try {
            response = orderClient.checkPurchased(userId, productId);
        } catch (Exception e) {
            log.error("调用订单服务校验购买记录失败：userId={}, productId={}", userId, productId, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "订单服务暂时不可用，请稍后重试", e);
        }
        if (response == null || !response.isSuccess() || response.getData() == null) {
            String message = response == null ? "订单服务无响应" : response.getMessage();
            log.error("订单服务返回异常：userId={}, productId={}, message={}", userId, productId, message);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "购买记录校验失败，请稍后重试");
        }
        return response.getData();
    }

    /**
     * 判断该订单下的该商品是否已被该用户评价过。
     *
     * @param userId    用户 id
     * @param productId 商品 id
     * @param orderId   订单 id
     * @return 已评价返回 true
     */
    private boolean hasReviewed(long userId, Long productId, Long orderId) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getUserId, userId)
                .eq(Comment::getProductId, productId)
                .eq(Comment::getType, Comment.TYPE_L1_REVIEW);
        if (orderId == null) {
            wrapper.isNull(Comment::getOrderId);
        } else {
            wrapper.eq(Comment::getOrderId, orderId);
        }
        Long count = commentMapper.selectCount(wrapper);
        return count != null && count > 0L;
    }

    /**
     * 生成"未完成购买"的拒绝文案。
     *
     * @param orderStatus 订单状态，null 表示根本没买过
     * @return 拒绝文案
     */
    private String notPurchasedReason(Integer orderStatus) {
        if (orderStatus == null) {
            return DENY_NOT_PURCHASED;
        }
        if (orderStatus == ORDER_STATUS_COMPLETED) {
            return DENY_NOT_PURCHASED;
        }
        return DENY_NOT_PURCHASED + "（当前订单状态：" + orderStatusText(orderStatus) + "）";
    }

    /**
     * 订单状态码转中文，取值与 {@code database/init.sql} 的字段注释严格一致。
     *
     * @param status 状态码
     * @return 中文描述
     */
    private String orderStatusText(int status) {
        switch (status) {
            case 0:
                return "待付款";
            case 1:
                return "待发货";
            case 2:
                return "待收货";
            case 3:
                return "已完成";
            case 4:
                return "已取消";
            case 5:
                return "已退款";
            default:
                return "未知(" + status + ")";
        }
    }

    /**
     * 查询商品，不存在即 404。
     *
     * @param productId 商品 id
     * @return 商品
     */
    private Product requireProduct(Long productId) {
        if (productId == null || productId <= 0L) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "商品ID不合法");
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return product;
    }

    /**
     * 查询评论，不存在 / 已删除即 404。
     *
     * @param commentId 评论 id
     * @return 评论
     */
    private Comment requireComment(Long commentId) {
        if (commentId == null || commentId <= 0L) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评论ID不合法");
        }
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评论不存在或已被删除");
        }
        return comment;
    }

    /**
     * 取一条非 L1 评论所属的 L1 id；存量数据 {@code root_id} 缺失时退化为 {@code parent_id}。
     *
     * @param target 目标评论
     * @return 所属 L1 的 id
     */
    private Long normalizeRootId(Comment target) {
        if (target.getRootId() != null && target.getRootId() > 0L) {
            return target.getRootId();
        }
        if (target.getParentId() != null && target.getParentId() > 0L) {
            return target.getParentId();
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "评论层级异常，无法追问");
    }

    /**
     * 正文清洗与长度校验（按<b>码点</b>计数，emoji 不会被算成两个字）。
     *
     * @param raw     原始正文
     * @param min     最小长度
     * @param max     最大长度
     * @param message 越界提示
     * @return 清洗后的正文
     */
    private String requireContent(String raw, int min, int max, String message) {
        String content = raw == null ? "" : raw.trim();
        int length = content.codePointCount(0, content.length());
        if (length < min || length > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        return content;
    }

    /**
     * 判断是否超出可编辑时间窗。
     *
     * @param createTime 发表时间
     * @return 超窗返回 true
     */
    private boolean isEditWindowExpired(LocalDateTime createTime) {
        if (createTime == null) {
            return false;
        }
        return createTime.plusHours(EDIT_WINDOW_HOURS).isBefore(LocalDateTime.now());
    }

    /**
     * 尝试获取 Redis 互斥锁。
     *
     * <p>Redis 不可用时<b>放行</b>（fail-open）并告警：锁只是并发加速带，
     * 真正的正确性由后面的存在性查询与唯一索引兜住，
     * 不该因为缓存挂了就让卖家整体无法回复。
     *
     * @param key   锁键
     * @param token 锁值，解锁时校验，防止误删他人的锁
     * @return 是否可以继续
     */
    private boolean tryLock(String key, String token) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, token, REPLY_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("获取回复互斥锁失败，降级为无锁执行：key={}, 原因={}", key, e.toString());
            return true;
        }
    }

    /**
     * 释放 Redis 互斥锁（仅当锁值仍是自己写的那个）。
     *
     * @param key   锁键
     * @param token 锁值
     */
    private void unlock(String key, String token) {
        try {
            Object current = redisTemplate.opsForValue().get(key);
            if (token.equals(current)) {
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.warn("释放回复互斥锁失败（{}s 后自动过期）：key={}, 原因={}",
                    REPLY_LOCK_TTL_SECONDS, key, e.toString());
        }
    }

    /**
     * 清除商品缓存，让评分变化立刻可见。
     *
     * @param productId 商品 id
     */
    private void evictProductCache(Long productId) {
        try {
            redisTemplate.delete(PRODUCT_CACHE_PREFIX + productId);
        } catch (Exception e) {
            log.warn("清除商品缓存失败（最长 1 小时后自然过期）：productId={}, 原因={}", productId, e.toString());
        }
    }

    /**
     * 归一化分页参数。
     *
     * @param value        原始值，可为 null
     * @param defaultValue 缺省值
     * @param min          下界
     * @param max          上界
     * @return 落在 [min, max] 内的值
     */
    private long normalize(Integer value, int defaultValue, int min, int max) {
        int actual = value == null ? defaultValue : value;
        if (actual < min) {
            actual = min;
        }
        if (actual > max) {
            actual = max;
        }
        return actual;
    }

    /**
     * 宽松地把聚合结果转成 Integer。
     *
     * @param value 原始值
     * @return 整数，无法转换时返回 null
     */
    private Integer toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @param value 字符串
     * @return 是否为 null 或全空白
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
