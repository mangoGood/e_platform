package com.ecommerce.product.controller;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.security.GatewayUserContext;
import com.ecommerce.product.dto.CommentCreateDTO;
import com.ecommerce.product.entity.Review;
import com.ecommerce.product.service.CommentService;
import com.ecommerce.product.service.ReviewService;
import com.ecommerce.product.vo.CommentVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 存量评价接口，<b>兼容层</b>。新功能一律走 {@code /comment/**}（见 {@link CommentController}）。
 *
 * <p>三个端点的现状：
 * <ul>
 *   <li>{@code GET /review/product/{id}} —— 保留，返回扁平 {@code List<Review>}，老页面继续可用；</li>
 *   <li>{@code POST /review} —— <b>转发</b>到 {@link CommentService#createL1}。
 *       旧实现直接 insert 无任何校验，是刷评价的口子；现在走和 {@code POST /comment}
 *       完全相同的校验链（购买 / 自评 / 去重 / 长度），<b>没有旁路</b>；</li>
 *   <li>{@code DELETE /review/{id}} —— <b>转发</b>到 {@link CommentService#deleteComment}，
 *       补上"仅作者本人或管理员"的归属校验，并同步刷新商品评分。</li>
 * </ul>
 *
 * @deprecated 请改用 {@code /comment/**}，本控制器仅为前端平滑迁移而保留。
 */
@Deprecated
@RestController
@RequestMapping("/review")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private CommentService commentService;

    /**
     * 查询某商品的一级评价列表（游客可读）。
     *
     * @param productId 商品 id
     * @return 扁平评价列表
     */
    @GetMapping("/product/{productId}")
    public Result<List<Review>> getReviewsByProductId(@PathVariable Long productId) {
        return Result.success(reviewService.getReviewsByProductId(productId));
    }

    /**
     * 发表评价（转发至三级评论的 L1 写入链路）。
     *
     * @param review     旧版入参，仅取 {@code productId / rating / content / images}；
     *                   {@code userId / orderId} 一律忽略，由服务端自行核实
     * @param headerUser 网关下发的用户 id
     * @return 新建评价
     */
    @PostMapping
    public Result<CommentVO> addReview(@RequestBody Review review,
                                       @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
        long userId = requireLogin(headerUser);
        CommentCreateDTO dto = new CommentCreateDTO();
        dto.setProductId(review.getProductId());
        dto.setRating(review.getRating());
        dto.setContent(review.getContent());
        dto.setImages(review.getImages());
        return Result.success(commentService.createL1(userId, dto));
    }

    /**
     * 删除评价（转发至三级评论的删除链路）。
     *
     * @param id         评价 id
     * @param headerUser 网关下发的用户 id
     * @return 空结果
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteReview(@PathVariable Long id,
                                     @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
        long userId = requireLogin(headerUser);
        commentService.deleteComment(userId, id);
        return Result.success();
    }

    /**
     * 解析当前用户 id，未登录直接 401。
     *
     * @param headerUserId {@code X-User-Id} 头
     * @return 用户 id，必然大于 0
     */
    private long requireLogin(Long headerUserId) {
        long fromContext = GatewayUserContext.getUserId();
        long userId = fromContext > 0L ? fromContext : (headerUserId == null ? 0L : headerUserId);
        if (userId <= 0L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }
}
