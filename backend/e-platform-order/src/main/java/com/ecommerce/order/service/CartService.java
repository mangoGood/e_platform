package com.ecommerce.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.result.Result;
import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.entity.Cart;
import com.ecommerce.order.mapper.CartMapper;
import feign.FeignException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CartService {
    @Autowired
    private CartMapper cartMapper;

    @Autowired
    private ProductClient productClient;

    public void addToCart(Long userId, Long productId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException("商品数量必须大于0");
        }

        // 校验商品是否存在及库存是否充足
        ProductClient.ProductInfo product = requireProductInfo(productId);

        // 查询当前购物车中该商品数量
        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Cart::getUserId, userId)
               .eq(Cart::getProductId, productId);

        Cart cart = cartMapper.selectOne(wrapper);

        int totalQuantity = (cart != null ? cart.getQuantity() : 0) + quantity;
        if (product.getStock() != null && totalQuantity > product.getStock()) {
            // 与下单链路同语义的库存冲突，一并对齐 409，避免「加购返回 200、下单返回 409」的割裂。
            throw new BusinessException(ErrorCode.CONFLICT,
                    "商品库存不足，当前库存：" + product.getStock());
        }

        if (cart != null) {
            cart.setQuantity(totalQuantity);
            cartMapper.updateById(cart);
        } else {
            cart = new Cart();
            cart.setUserId(userId);
            cart.setProductId(productId);
            cart.setQuantity(quantity);
            cart.setSelected(1);
            cartMapper.insert(cart);
        }
    }

    public void updateCartQuantity(Long userId, Long productId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException("商品数量必须大于0");
        }

        // 校验商品是否存在及库存是否充足
        ProductClient.ProductInfo product = requireProductInfo(productId);
        if (product.getStock() != null && quantity > product.getStock()) {
            // 同上：修改购物车数量超出库存同样是状态冲突 → 409。
            throw new BusinessException(ErrorCode.CONFLICT,
                    "商品库存不足，当前库存：" + product.getStock());
        }

        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Cart::getUserId, userId)
               .eq(Cart::getProductId, productId);

        Cart cart = cartMapper.selectOne(wrapper);
        if (cart != null) {
            cart.setQuantity(quantity);
            cartMapper.updateById(cart);
        }
    }

    /**
     * 远程取商品信息，不存在即抛「商品不存在」。
     *
     * <h3>为什么必须显式捕获 {@link FeignException.NotFound}</h3>
     * product 服务的 {@code GET /product/{id}} 本轮已从
     * 「{@code 200 + data:null}」改为真实 <b>404</b>。而本模块的 Feign 客户端
     * <b>没有配置任何 {@code ErrorDecoder}，也没有开 {@code dismiss404}</b>，
     * 走的是 {@code ErrorDecoder.Default}——非 2xx 一律抛
     * {@link FeignException}，<b>根本不会返回 {@code Result}</b>。
     * 也就是说原来的 {@code productResult.getData() == null} 判空分支
     * 会直接变成<b>死代码</b>，异常穿透到 {@code GlobalExceptionHandler}
     * 的兜底分支，调用方收到的是「系统异常，请联系管理员」——
     * 一个纯粹的客户端输入错误被伪装成服务端故障。
     *
     * <p>这里把 404 翻译回原有的业务语义，保证 order 对外的文案与状态
     * <b>与改动前逐字一致</b>（HTTP 200 + code 500 +「商品不存在」）。
     * 同时保留 {@code null}/{@code data == null} 的判空，
     * 以兼容未来若引入 {@code dismiss404} 或自定义解码器的情形。
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
            throw new BusinessException("商品不存在");
        }
        if (productResult == null || productResult.getData() == null) {
            throw new BusinessException("商品不存在");
        }
        return productResult.getData();
    }

    public void removeFromCart(Long userId, Long productId) {
        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Cart::getUserId, userId)
               .eq(Cart::getProductId, productId);
        cartMapper.delete(wrapper);
    }

    public void clearCart(Long userId) {
        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Cart::getUserId, userId);
        cartMapper.delete(wrapper);
    }

    public List<Cart> getCartByUserId(Long userId) {
        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Cart::getUserId, userId)
               .orderByDesc(Cart::getCreateTime)
               .orderByDesc(Cart::getId);
        return cartMapper.selectList(wrapper);
    }
}
