package com.ecommerce.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.Result;
import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.entity.Cart;
import com.ecommerce.order.mapper.CartMapper;
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
        Result<ProductClient.ProductInfo> productResult = productClient.getProductById(productId);
        if (productResult == null || productResult.getData() == null) {
            throw new BusinessException("商品不存在");
        }
        ProductClient.ProductInfo product = productResult.getData();

        // 查询当前购物车中该商品数量
        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Cart::getUserId, userId)
               .eq(Cart::getProductId, productId);

        Cart cart = cartMapper.selectOne(wrapper);

        int totalQuantity = (cart != null ? cart.getQuantity() : 0) + quantity;
        if (product.getStock() != null && totalQuantity > product.getStock()) {
            throw new BusinessException("商品库存不足，当前库存：" + product.getStock());
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
        Result<ProductClient.ProductInfo> productResult = productClient.getProductById(productId);
        if (productResult == null || productResult.getData() == null) {
            throw new BusinessException("商品不存在");
        }
        ProductClient.ProductInfo product = productResult.getData();
        if (product.getStock() != null && quantity > product.getStock()) {
            throw new BusinessException("商品库存不足，当前库存：" + product.getStock());
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
               .orderByDesc(Cart::getCreateTime);
        return cartMapper.selectList(wrapper);
    }
}
