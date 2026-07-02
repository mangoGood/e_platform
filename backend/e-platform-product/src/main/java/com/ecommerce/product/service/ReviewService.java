package com.ecommerce.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.product.entity.Review;
import com.ecommerce.product.mapper.ReviewMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReviewService {

    @Autowired
    private ReviewMapper reviewMapper;

    public List<Review> getReviewsByProductId(Long productId) {
        LambdaQueryWrapper<Review> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Review::getProductId, productId)
               .orderByDesc(Review::getCreateTime);
        return reviewMapper.selectList(wrapper);
    }

    public Review addReview(Review review) {
        reviewMapper.insert(review);
        return review;
    }

    public void deleteReview(Long id, Long userId) {
        LambdaQueryWrapper<Review> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Review::getId, id).eq(Review::getUserId, userId);
        reviewMapper.delete(wrapper);
    }
}
