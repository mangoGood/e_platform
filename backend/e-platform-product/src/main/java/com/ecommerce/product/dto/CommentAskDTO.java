package com.ecommerce.product.dto;

import java.io.Serializable;

/**
 * L3 第三方追问入参。
 *
 * <p><b>{@code commentId} 是「我想回复谁」，不是「挂到哪里」。</b>
 * 它可以是 L1、L2 或另一条 L3 的 id；服务端一律做<b>层级归一</b>：
 * <ul>
 *   <li>入库的 {@code parentId} 与 {@code rootId} <b>恒为所属 L1 的 id</b>；</li>
 *   <li>被回复者仅体现在 {@code replyToUserId / replyToUsername} 上，用于渲染 {@code @昵称}。</li>
 * </ul>
 * 由此保证<b>永远不会出现第 4 层</b>——无论用户在界面上点了多深的"回复"。
 *
 * <p>校验为何不用 Bean Validation：见 {@link CommentCreateDTO} 的类注释。
 */
public class CommentAskDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 想要回复的目标评论 id（L1/L2/L3 皆可，服务端自动归一到 L1），必填。 */
    private Long commentId;

    /** 追问正文，服务端校验 1-200 字。 */
    private String content;

    public Long getCommentId() {
        return commentId;
    }

    public void setCommentId(Long commentId) {
        this.commentId = commentId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
