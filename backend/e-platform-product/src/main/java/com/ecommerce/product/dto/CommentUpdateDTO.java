package com.ecommerce.product.dto;

import java.io.Serializable;

/**
 * 评论编辑入参（发表后 24 小时内，仅作者本人）。
 *
 * <p>{@code rating} 只对 L1 生效；L2/L3 传了会被忽略（而不是报错），
 * 避免前端复用同一份表单时被无谓地拦下。
 *
 * <p>正文长度按层级在服务端校验：L3 上限 200 字，L1/L2 上限 500 字，L1 下限 5 字。
 * 校验为何不用 Bean Validation：见 {@link CommentCreateDTO} 的类注释。
 */
public class CommentUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 新正文。 */
    private String content;

    /** 新评分，仅 L1 生效；为 null 表示不修改评分。 */
    private Integer rating;

    /** 新图片 JSON；为 null 表示不修改。 */
    private String images;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }
}
