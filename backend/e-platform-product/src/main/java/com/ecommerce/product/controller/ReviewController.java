package com.ecommerce.product.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.product.entity.Review;
import com.ecommerce.product.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/review")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @GetMapping("/product/{productId}")
    public Result<List<Review>> getReviewsByProductId(@PathVariable Long productId) {
        List<Review> reviews = reviewService.getReviewsByProductId(productId);
        return Result.success(reviews);
    }

    @PostMapping
    public Result<Review> addReview(@Valid @RequestBody Review review,
                                     @RequestHeader("X-User-Id") Long userId) {
        review.setUserId(userId);
        Review created = reviewService.addReview(review);
        return Result.success(created);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteReview(@PathVariable Long id,
                                      @RequestHeader("X-User-Id") Long userId) {
        reviewService.deleteReview(id, userId);
        return Result.success(null);
    }
}
