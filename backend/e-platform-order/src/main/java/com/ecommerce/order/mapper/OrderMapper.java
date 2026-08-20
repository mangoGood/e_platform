package com.ecommerce.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 查询某用户对某商品<b>最近一笔已完成</b>（status=3）的购买记录。
     *
     * <p>这是"购买才能评价"这条规则的唯一数据来源。
     * 命中多笔时取收货时间最新的一笔——用户凭最近一次购买体验来评价，符合直觉。
     *
     * @param userId    买家 id
     * @param productId 商品 id
     * @return 含 {@code order_id / order_item_id / order_status / receive_time} 的单行，未命中返回 null
     */
    @Select("SELECT o.id AS order_id, oi.id AS order_item_id, o.status AS order_status, "
            + "       o.receive_time AS receive_time "
            + "FROM orders o "
            + "JOIN order_item oi ON oi.order_id = o.id "
            + "WHERE o.user_id = #{userId} AND oi.product_id = #{productId} "
            + "  AND o.deleted = 0 AND o.status = 3 "
            + "ORDER BY o.receive_time DESC, o.id DESC "
            + "LIMIT 1")
    Map<String, Object> selectCompletedPurchase(@Param("userId") Long userId,
                                                @Param("productId") Long productId);

    /**
     * 查询某用户对某商品<b>最近一笔任意状态</b>的订单。
     *
     * <p>仅在 {@link #selectCompletedPurchase} 落空时调用，
     * 用来区分"没买过"和"买了但没收货"，好给出精准的拒绝文案。
     * 已取消(4)/已退款(5)的订单排除在外——它们等同于没买。
     *
     * @param userId    买家 id
     * @param productId 商品 id
     * @return 含 {@code order_id / order_item_id / order_status} 的单行，未命中返回 null
     */
    @Select("SELECT o.id AS order_id, oi.id AS order_item_id, o.status AS order_status "
            + "FROM orders o "
            + "JOIN order_item oi ON oi.order_id = o.id "
            + "WHERE o.user_id = #{userId} AND oi.product_id = #{productId} "
            + "  AND o.deleted = 0 AND o.status NOT IN (4, 5) "
            + "ORDER BY o.id DESC "
            + "LIMIT 1")
    Map<String, Object> selectLatestPurchase(@Param("userId") Long userId,
                                             @Param("productId") Long productId);
}
