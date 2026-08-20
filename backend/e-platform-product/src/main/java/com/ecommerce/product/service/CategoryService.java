package com.ecommerce.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.product.entity.Category;
import com.ecommerce.product.mapper.CategoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CategoryService {
    @Autowired
    private CategoryMapper categoryMapper;

    public List<Category> getCategoryTree() {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        // sort 是人工维护的排序权重，大量并列是常态（默认值往往都是 0），
        // 不带主键兜底时同级分类的展示顺序在每次查询之间都可能变化。
        wrapper.eq(Category::getStatus, 1)
               .orderByAsc(Category::getSort)
               .orderByAsc(Category::getId);
        
        List<Category> allCategories = categoryMapper.selectList(wrapper);
        
        Map<Long, List<Category>> parentMap = allCategories.stream()
                .collect(Collectors.groupingBy(Category::getParentId));
        
        allCategories.forEach(c -> c.setChildren(parentMap.getOrDefault(c.getId(), new ArrayList<>())));
        
        return parentMap.getOrDefault(0L, new ArrayList<>());
    }

    public List<Category> getAllCategories() {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Category::getStatus, 1)
               .orderByAsc(Category::getSort)
               .orderByAsc(Category::getId);
        return categoryMapper.selectList(wrapper);
    }
}
