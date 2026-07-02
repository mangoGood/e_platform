package com.ecommerce.mobile.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分类信息
 */
@Data
public class CategoryInfo {
    private Long id;
    private String name;
    private Long parentId;
    private Integer level;
    private Integer sort;
    private String icon;
    private Integer status;
    private LocalDateTime createTime;
    private List<CategoryInfo> children;
}
