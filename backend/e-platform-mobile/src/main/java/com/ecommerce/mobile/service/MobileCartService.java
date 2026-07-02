package com.ecommerce.mobile.service;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.client.OrderClient;
import com.ecommerce.mobile.client.ProductClient;
import com.ecommerce.mobile.context.UserContext;
import com.ecommerce.mobile.dto.CartInfo;
import com.ecommerce.mobile.dto.CartVO;
import com.ecommerce.mobile.dto.ProductInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 购物车聚合服务
 * <p>
 * 合并购物车项 + 商品详情，移动端一次拉取完整购物车。
 */
@Slf4j
@Service
public class MobileCartService {

    @Autowired
    private OrderClient orderClient;

    @Autowired
    private ProductClient productClient;

    /**
     * 获取购物车（含商品详情）
     */
    public CartVO getCart() {
        Long userId = UserContext.getUserId();
        Result<List<CartInfo>> cartResult = orderClient.getCart(userId);
        if (cartResult == null || !cartResult.isSuccess()) {
            throw new BusinessException("获取购物车失败");
        }

        List<CartInfo> cartItems = cartResult.getData();
        if (cartItems == null || cartItems.isEmpty()) {
            CartVO emptyVo = new CartVO();
            emptyVo.setItems(new ArrayList<>());
            emptyVo.setTotalCount(0);
            emptyVo.setTotalPrice(BigDecimal.ZERO);
            emptyVo.setAllSelected(true);
            return emptyVo;
        }

        // 批量查询商品信息
        List<Long> productIds = cartItems.stream()
                .map(CartInfo::getProductId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, ProductInfo> productMap = batchGetProducts(productIds);

        // 组装聚合 VO
        List<CartVO.CartItemVO> items = new ArrayList<>();
        int totalCount = 0;
        BigDecimal totalPrice = BigDecimal.ZERO;
        boolean allSelected = true;

        for (CartInfo cart : cartItems) {
            CartVO.CartItemVO item = new CartVO.CartItemVO();
            item.setCartId(cart.getId());
            item.setProductId(cart.getProductId());
            item.setQuantity(cart.getQuantity());
            item.setSelected(cart.getSelected() != null && cart.getSelected() == 1);

            if (!item.getSelected()) {
                allSelected = false;
            }

            ProductInfo product = productMap.get(cart.getProductId());
            if (product != null) {
                item.setProductName(product.getName());
                item.setProductImage(product.getMainImage());
                item.setPrice(product.getPrice());
                item.setStock(product.getStock());
                item.setStatus(product.getStatus());
                item.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity())));

                if (item.getSelected()) {
                    totalCount += cart.getQuantity();
                    totalPrice = totalPrice.add(item.getSubtotal());
                }
            }
            items.add(item);
        }

        CartVO vo = new CartVO();
        vo.setItems(items);
        vo.setTotalCount(totalCount);
        vo.setTotalPrice(totalPrice);
        vo.setAllSelected(allSelected);
        return vo;
    }

    /**
     * 加入购物车
     */
    public void addToCart(Long productId, Integer quantity) {
        Long userId = UserContext.getUserId();
        Result<Void> result = orderClient.addToCart(productId, quantity, userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "加入购物车失败");
        }
    }

    /**
     * 修改购物车数量
     */
    public void updateQuantity(Long productId, Integer quantity) {
        Long userId = UserContext.getUserId();
        Result<Void> result = orderClient.updateCartQuantity(productId, quantity, userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "修改数量失败");
        }
    }

    /**
     * 移除购物车项
     */
    public void removeFromCart(Long productId) {
        Long userId = UserContext.getUserId();
        Result<Void> result = orderClient.removeFromCart(productId, userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "移除商品失败");
        }
    }

    /**
     * 清空购物车
     */
    public void clearCart() {
        Long userId = UserContext.getUserId();
        Result<Void> result = orderClient.clearCart(userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "清空购物车失败");
        }
    }

    /**
     * 批量查询商品并转为 Map
     */
    private Map<Long, ProductInfo> batchGetProducts(List<Long> productIds) {
        try {
            Result<List<ProductInfo>> result = productClient.getProductsByIds(productIds);
            if (result != null && result.isSuccess() && result.getData() != null) {
                return result.getData().stream()
                        .collect(Collectors.toMap(ProductInfo::getId, p -> p, (a, b) -> a));
            }
        } catch (Exception e) {
            log.warn("批量查询商品失败：{}", e.getMessage());
        }
        return Map.of();
    }
}
