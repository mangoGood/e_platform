package com.ecommerce.product.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.product.entity.Category;
import com.ecommerce.product.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/category")
public class CategoryController {
    @Autowired
    private CategoryService categoryService;

    @GetMapping("/tree")
    public Result<List<Category>> getCategoryTree() {
        List<Category> categories = categoryService.getCategoryTree();
        return Result.success(categories);
    }

    @GetMapping("/all")
    public Result<List<Category>> getAllCategories() {
        List<Category> categories = categoryService.getAllCategories();
        return Result.success(categories);
    }
}
