package com.ecommerce.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.PageResult;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.entity.Category;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.mapper.CategoryMapper;
import com.ecommerce.product.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ProductService {
    @Autowired
    private ProductMapper productMapper;
    
    @Autowired
    private CategoryMapper categoryMapper;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    public void addProduct(ProductRequest request, Long sellerId) {
        Category category = categoryMapper.selectById(request.getCategoryId());
        if (category == null) {
            throw new BusinessException("分类不存在");
        }
        
        Product product = new Product();
        product.setCategoryId(request.getCategoryId());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        product.setStock(request.getStock());
        product.setMainImage(request.getMainImage());
        product.setImages(request.getImages());
        product.setSellerId(sellerId);
        product.setStatus(1);
        product.setSales(0);
        
        productMapper.insert(product);
    }

    public void updateProduct(Long id, ProductRequest request, Long sellerId) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        
        if (!product.getSellerId().equals(sellerId)) {
            throw new BusinessException("无权限修改该商品");
        }
        
        product.setCategoryId(request.getCategoryId());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        product.setStock(request.getStock());
        product.setMainImage(request.getMainImage());
        product.setImages(request.getImages());
        productMapper.updateById(product);
        
        String cacheKey = "product:" + id;
        redisTemplate.delete(cacheKey);
    }

    public void deleteProduct(Long id, Long sellerId) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        
        if (!product.getSellerId().equals(sellerId)) {
            throw new BusinessException("无权限删除该商品");
        }
        
        productMapper.deleteById(id);
        
        String cacheKey = "product:" + id;
        redisTemplate.delete(cacheKey);
    }

    public Product getProductById(Long id) {
        String cacheKey = "product:" + id;
        Product product = (Product) redisTemplate.opsForValue().get(cacheKey);
        
        if (product == null) {
            product = productMapper.selectById(id);
            if (product != null) {
                redisTemplate.opsForValue().set(cacheKey, product, 1, TimeUnit.HOURS);
            }
        }
        
        return product;
    }

    public PageResult<Product> getProductList(Integer current, Integer size, Long categoryId, String keyword) {
        Page<Product> page = new Page<>(current, size);
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        
        wrapper.eq(Product::getStatus, 1);
        
        if (categoryId != null) {
            wrapper.eq(Product::getCategoryId, categoryId);
        }
        
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Product::getName, keyword);
        }
        
        wrapper.orderByDesc(Product::getCreateTime);
        
        Page<Product> productPage = productMapper.selectPage(page, wrapper);
        
        return new PageResult<>(productPage.getRecords(), productPage.getTotal(), 
                               productPage.getSize(), productPage.getCurrent());
    }

    public List<Product> getProductsBySellerId(Long sellerId) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getSellerId, sellerId)
               .orderByDesc(Product::getCreateTime);
        return productMapper.selectList(wrapper);
    }

    public List<Product> getProductsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return productMapper.selectBatchIds(ids);
    }

    public void deductStock(Long productId, Integer quantity) {
        int rows = productMapper.deductStock(productId, quantity);
        if (rows == 0) {
            throw new BusinessException("库存不足");
        }
        redisTemplate.delete("product:" + productId);
    }

    public void restoreStock(Long productId, Integer quantity) {
        productMapper.restoreStock(productId, quantity);
        redisTemplate.delete("product:" + productId);
    }
}
