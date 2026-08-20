package com.ecommerce.product.dto;

import java.io.Serializable;

/**
 * L2 卖家回复入参。
 *
 * <p>{@code parentId} 必须是一条 <b>L1 买家评价</b> 的 id；
 * 服务端会校验「当前登录人 == 该 L1 所属商品的卖家」，跨店回复直接 403。
 *
 * <p>校验为何不用 Bean Validation：见 {@link CommentCreateDTO} 的类注释——
 * 那条路径的 HTTP 状态码恒为 200，无法用状态码断言。
 */
public class CommentReplyDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 被回复的 L1 评价 id，必填。 */
    private Long parentId;

    /** 回复正文，服务端校验 1-500 字。 */
    private String content;

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
