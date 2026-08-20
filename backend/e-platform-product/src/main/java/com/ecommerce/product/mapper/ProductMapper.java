package com.ecommerce.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    @Update("UPDATE product SET stock = stock - #{quantity}, sales = sales + #{quantity} WHERE id = #{id} AND stock >= #{quantity}")
    int deductStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    @Update("UPDATE product SET stock = stock + #{quantity} WHERE id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 重算单个商品的评分聚合。
     *
     * <p><b>为什么是"重算"而不是"增量"</b>：{@code rating_count = rating_count + 1} 这类写法
     * 在并发插入、事务回滚、逻辑删除后再恢复等场景下都会漂移，且一旦漂了就永远回不来。
     * 整体重算的成本只是一次带索引（{@code idx_product_type}）的聚合，
     * 却能保证<b>每次写入后数值都收敛到真值</b>。
     *
     * <p>由 L1 的新增 / 改分 / 删除三条路径在<b>同一事务</b>内调用。
     *
     * @param productId 商品 id
     * @return 受影响行数
     */
    @Update("UPDATE product p SET "
            + "p.rating_avg = IFNULL((SELECT ROUND(AVG(c.rating), 2) FROM comment c "
            + "    WHERE c.product_id = p.id AND c.type = 1 AND c.deleted = 0 "
            + "      AND c.status = 1 AND c.rating IS NOT NULL), 0.00), "
            + "p.rating_count = IFNULL((SELECT COUNT(*) FROM comment c "
            + "    WHERE c.product_id = p.id AND c.type = 1 AND c.deleted = 0 "
            + "      AND c.status = 1 AND c.rating IS NOT NULL), 0) "
            + "WHERE p.id = #{productId}")
    int refreshRating(@Param("productId") Long productId);

    /**
     * 全量重算所有商品的评分聚合，供 {@code RatingSyncJob} 定时兜底。
     *
     * <p>用一次 {@code LEFT JOIN} 派生表完成，避免逐商品 N 次 UPDATE。
     * 没有任何有效评价的商品会被归零，因此也能修正"评价全被删了但分数还挂着"的脏数据。
     *
     * @return 受影响行数
     */
    @Update("UPDATE product p "
            + "LEFT JOIN (SELECT product_id, ROUND(AVG(rating), 2) AS avg_r, COUNT(*) AS cnt "
            + "           FROM comment "
            + "           WHERE type = 1 AND deleted = 0 AND status = 1 AND rating IS NOT NULL "
            + "           GROUP BY product_id) c ON c.product_id = p.id "
            + "SET p.rating_avg = IFNULL(c.avg_r, 0.00), p.rating_count = IFNULL(c.cnt, 0)")
    int refreshAllRating();
}
