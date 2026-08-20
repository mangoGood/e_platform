package com.ecommerce.product.vo;

import java.io.Serializable;

/**
 * 浏览者上下文：告诉前端「当前这个人在这个商品下能干什么、不能干什么、为什么」。
 *
 * <p>存在的意义是<b>把"能不能"的判断收敛到服务端</b>。
 * 前端不需要自己拼「是不是卖家 / 有没有买过 / 是不是已经评过」的规则，
 * 只要照着 {@code canXxx} 决定输入框是否置灰、照着 {@code xxxDeniedReason} 显示提示文案即可。
 * 规则改了也只需改服务端一处。
 *
 * <p>注意这仅是<b>体验层</b>的前置提示，真正的拦截仍在写接口里做——
 * 前端置不置灰不影响后端一定会校验。
 */
public class ViewerContextVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否已登录。 */
    private boolean loggedIn = false;

    /** 当前用户 id，未登录为 null。 */
    private Long userId;

    /** 是否为本商品的卖家。 */
    private boolean seller = false;

    /** 能否发表 L1 买家评价。 */
    private boolean canReview = false;

    /** 不能评价的原因，可直接展示给用户；能评价时为 null。 */
    private String reviewDeniedReason;

    /** 能否发表 L2 卖家回复。 */
    private boolean canReply = false;

    /** 不能回复的原因；能回复时为 null。 */
    private String replyDeniedReason;

    /** 能否发表 L3 追问。 */
    private boolean canAsk = false;

    /** 不能追问的原因；能追问时为 null。 */
    private String askDeniedReason;

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public void setLoggedIn(boolean loggedIn) {
        this.loggedIn = loggedIn;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public boolean isSeller() {
        return seller;
    }

    public void setSeller(boolean seller) {
        this.seller = seller;
    }

    public boolean isCanReview() {
        return canReview;
    }

    public void setCanReview(boolean canReview) {
        this.canReview = canReview;
    }

    public String getReviewDeniedReason() {
        return reviewDeniedReason;
    }

    public void setReviewDeniedReason(String reviewDeniedReason) {
        this.reviewDeniedReason = reviewDeniedReason;
    }

    public boolean isCanReply() {
        return canReply;
    }

    public void setCanReply(boolean canReply) {
        this.canReply = canReply;
    }

    public String getReplyDeniedReason() {
        return replyDeniedReason;
    }

    public void setReplyDeniedReason(String replyDeniedReason) {
        this.replyDeniedReason = replyDeniedReason;
    }

    public boolean isCanAsk() {
        return canAsk;
    }

    public void setCanAsk(boolean canAsk) {
        this.canAsk = canAsk;
    }

    public String getAskDeniedReason() {
        return askDeniedReason;
    }

    public void setAskDeniedReason(String askDeniedReason) {
        this.askDeniedReason = askDeniedReason;
    }
}
