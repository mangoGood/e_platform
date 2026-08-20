package com.ecommerce.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.product.entity.Comment;
import com.ecommerce.product.entity.Review;
import com.ecommerce.product.mapper.ReviewMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 存量评价服务，现已降级为 <b>只读兼容层</b>。
 *
 * <h3>原来的问题</h3>
 * 旧版 {@code addReview()} 只有一行 {@code reviewMapper.insert(review)}——
 * 没有购买校验、没有归属校验、没有去重、没有自评拦截。
 * 任何登录用户都可以给任意商品刷任意条数的五星评价，包括给自己的商品刷。
 * 这是<b>安全缺陷</b>，因此写入能力被整体移除，全部改由
 * {@link CommentService} 承接（见 {@code CommentService#createL1}）。
 *
 * <h3>为什么不直接删掉这个类</h3>
 * 前端 {@code frontend/src/api/product.js} 仍在调 {@code GET /review/product/{id}}，
 * 返回结构是扁平的 {@code List<Review>}。保留只读方法可以让老页面继续工作，
 * 新的三级评论走 {@code /comment/**}，两边互不影响，前端可以按自己的节奏迁移。
 */
@Service
public class ReviewService {

    @Autowired
    private ReviewMapper reviewMapper;

    /**
     * 查询某商品的一级评价列表（扁平结构，兼容老页面）。
     *
     * <p>相比旧实现补了两个过滤条件：{@code type=1} 与 {@code status=1}。
     * 不加的话，三级评论上线后卖家回复和第三方追问会被当成"评价"一起吐给老页面，
     * 页面上会出现没有星级的诡异条目。
     *
     * @param productId 商品 id
     * @return 一级评价列表，永不为 null
     */
    public List<Review> getReviewsByProductId(Long productId) {
        LambdaQueryWrapper<Review> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Review::getProductId, productId)
                .eq(Review::getStatus, Comment.STATUS_VISIBLE)
                .apply("type = {0}", Comment.TYPE_L1_REVIEW)
                .orderByDesc(Review::getCreateTime)
                .orderByDesc(Review::getId);
        return reviewMapper.selectList(wrapper);
    }
}
