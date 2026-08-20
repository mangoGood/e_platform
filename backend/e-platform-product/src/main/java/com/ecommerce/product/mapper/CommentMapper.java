package com.ecommerce.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.product.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 评论 Mapper。
 *
 * <p><b>关于级联可见性</b>：L2/L3 的查询一律通过 {@code root_id IN (已通过可见性筛选的 L1 id 集合)}
 * 完成。由于传入的 L1 集合本身已满足 {@code deleted=0 AND status=1}，
 * L1 被删除或隐藏时其子级自然查不出来——无需在 DB 里做物理级联更新。
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 按 rootId 集合批量查询子级（L2 + L3）。
     *
     * <p>排序规则：先按 {@code root_id} 聚簇，再 <b>L2 在前、L3 在后</b>，
     * 同层按创建时间<b>正序</b>（对话感）。
     *
     * @param rootIds 已确认可见的 L1 id 集合，不可为空
     * @return 子级评论列表，永不为 null
     */
    @Select("<script>"
            + "SELECT * FROM comment "
            + "WHERE deleted = 0 AND status = 1 AND type IN (2, 3) "
            + "AND root_id IN "
            + "<foreach collection='rootIds' item='rid' open='(' separator=',' close=')'>#{rid}</foreach> "
            + "ORDER BY root_id ASC, type ASC, create_time ASC, id ASC"
            + "</script>")
    List<Comment> selectChildrenByRootIds(@Param("rootIds") List<Long> rootIds);

    /**
     * 判定某条 L1 是否已存在有效的 L2 卖家回复。
     *
     * <p>MySQL 不支持部分唯一索引，"每条 L1 最多 1 条有效 L2"只能由
     * 「Redis 互斥锁 + 本查询」共同保证。逻辑删除后本查询返回 0，因此<b>允许重新回复</b>。
     *
     * @param parentId L1 评论 id
     * @return 有效 L2 条数
     */
    @Select("SELECT COUNT(*) FROM comment "
            + "WHERE parent_id = #{parentId} AND type = 2 AND deleted = 0 AND status = 1")
    int countValidReply(@Param("parentId") Long parentId);

    /**
     * 查询某条 L1 已有的有效 L2（供 409 响应携带既有回复 id）。
     *
     * @param parentId L1 评论 id
     * @return 有效 L2 列表，通常 0 或 1 条
     */
    @Select("SELECT * FROM comment "
            + "WHERE parent_id = #{parentId} AND type = 2 AND deleted = 0 AND status = 1 "
            + "ORDER BY id ASC LIMIT 1")
    Comment selectValidReply(@Param("parentId") Long parentId);

    /**
     * 统计某商品各星级的 L1 数量，用于 {@code summary.distribution}。
     *
     * @param productId 商品 id
     * @return 每行含 {@code rating} 与 {@code cnt} 两列
     */
    @Select("SELECT rating AS rating, COUNT(*) AS cnt FROM comment "
            + "WHERE product_id = #{productId} AND type = 1 AND deleted = 0 AND status = 1 "
            + "AND rating IS NOT NULL GROUP BY rating")
    List<Map<String, Object>> selectRatingDistribution(@Param("productId") Long productId);

    /**
     * 统计某卖家名下「尚未回复」的 L1 数量，供卖家中心红点使用。
     *
     * @param sellerId 卖家 id
     * @return 待回复条数
     */
    @Select("SELECT COUNT(*) FROM comment l1 "
            + "WHERE l1.seller_id = #{sellerId} AND l1.type = 1 AND l1.deleted = 0 AND l1.status = 1 "
            + "AND NOT EXISTS (SELECT 1 FROM comment l2 WHERE l2.parent_id = l1.id "
            + "  AND l2.type = 2 AND l2.deleted = 0 AND l2.status = 1)")
    int countPendingReply(@Param("sellerId") Long sellerId);

    /**
     * 把新插入 L1 的 {@code root_id} 回写为自身 id（自增主键在 insert 前不可知）。
     *
     * @param id L1 评论 id
     * @return 受影响行数
     */
    @Update("UPDATE comment SET root_id = id WHERE id = #{id} AND type = 1")
    int fixRootIdSelf(@Param("id") Long id);

    /**
     * 统计某条 L1 之下的有效子级（L2 + L3）条数。
     *
     * @param rootId L1 评论 id
     * @return 有效子级条数
     */
    @Select("SELECT COUNT(*) FROM comment "
            + "WHERE root_id = #{rootId} AND type IN (2, 3) AND deleted = 0 AND status = 1")
    int countChildren(@Param("rootId") Long rootId);

    /**
     * 把某条 L1 的 {@code reply_count} 覆盖为给定值。
     *
     * <p>刻意采用「先 count 再 set」而非 {@code reply_count = reply_count + 1}：
     * 自增在并发/回滚场景下会漂移，重算写入永远收敛到真值。
     * 同时避免 MySQL "You can't specify target table for update in FROM clause" 限制。
     *
     * @param rootId L1 评论 id
     * @param count  重算得到的子级条数
     * @return 受影响行数
     */
    @Update("UPDATE comment SET reply_count = #{count} WHERE id = #{rootId} AND type = 1")
    int updateReplyCount(@Param("rootId") Long rootId, @Param("count") int count);
}
