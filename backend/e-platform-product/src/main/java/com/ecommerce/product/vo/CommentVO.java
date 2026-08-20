package com.ecommerce.product.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 单条评论的展示对象。
 *
 * <p>同一个类承载三个层级，靠 {@code type} 区分：
 * <ul>
 *   <li><b>L1</b>：{@code rating} 有值，{@code reply} 挂唯一的卖家回复，{@code asks} 挂追问预览；</li>
 *   <li><b>L2</b>：{@code sellerReply=true}，{@code reply/asks} 恒为空；</li>
 *   <li><b>L3</b>：{@code replyToNickname} 有值时前端渲染 {@code 回复 @xxx}。</li>
 * </ul>
 *
 * <p><b>不下发原始用户名</b>：{@code nickname} 与 {@code replyToNickname} 均为脱敏结果，
 * {@code userId} 保留仅供前端做「是不是我自己」的判断（{@code mine} 字段已经算好了）。
 */
public class CommentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评论 id。 */
    private Long id;

    /** 发表者 id。 */
    private Long userId;

    /** 发表者脱敏昵称，永不为 null。 */
    private String nickname;

    /** 层级：1-买家评价，2-卖家回复，3-第三方追问。 */
    private Integer type;

    /** 父评论 id：L1 为 0，L2/L3 为所属 L1 的 id。 */
    private Long parentId;

    /** 根评论 id：L1 为自身 id，L2/L3 为所属 L1 的 id。 */
    private Long rootId;

    /** 评分，仅 L1 有值。 */
    private Integer rating;

    /** 正文。 */
    private String content;

    /** 图片 JSON。 */
    private String images;

    /** 被回复者 id，仅 L3 可能有值。 */
    private Long replyToUserId;

    /** 被回复者脱敏昵称，仅 L3 可能有值。 */
    private String replyToNickname;

    /** 子级总数（L2 + L3），仅 L1 维护。 */
    private Integer replyCount = 0;

    /** 发表时间。 */
    private LocalDateTime createTime;

    /** 是否为卖家回复（等价于 {@code type == 2}，冗余出来方便前端加"商家"标签）。 */
    private boolean sellerReply = false;

    /** 是否为当前浏览者本人发表。 */
    private boolean mine = false;

    /** 唯一的卖家回复，仅 L1 有值；无回复时为 null。 */
    private CommentVO reply;

    /** 追问预览列表（时间正序），仅 L1 有值。 */
    private List<CommentVO> asks = new ArrayList<>();

    /** 追问总数，仅 L1 有值。 */
    private Integer askTotal = 0;

    /** 追问是否还有更多未展示，仅 L1 有值。 */
    private boolean askHasMore = false;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Long getRootId() {
        return rootId;
    }

    public void setRootId(Long rootId) {
        this.rootId = rootId;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public Long getReplyToUserId() {
        return replyToUserId;
    }

    public void setReplyToUserId(Long replyToUserId) {
        this.replyToUserId = replyToUserId;
    }

    public String getReplyToNickname() {
        return replyToNickname;
    }

    public void setReplyToNickname(String replyToNickname) {
        this.replyToNickname = replyToNickname;
    }

    public Integer getReplyCount() {
        return replyCount;
    }

    public void setReplyCount(Integer replyCount) {
        this.replyCount = replyCount;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public boolean isSellerReply() {
        return sellerReply;
    }

    public void setSellerReply(boolean sellerReply) {
        this.sellerReply = sellerReply;
    }

    public boolean isMine() {
        return mine;
    }

    public void setMine(boolean mine) {
        this.mine = mine;
    }

    public CommentVO getReply() {
        return reply;
    }

    public void setReply(CommentVO reply) {
        this.reply = reply;
    }

    public List<CommentVO> getAsks() {
        return asks;
    }

    public void setAsks(List<CommentVO> asks) {
        this.asks = asks == null ? new ArrayList<>() : asks;
    }

    public Integer getAskTotal() {
        return askTotal;
    }

    public void setAskTotal(Integer askTotal) {
        this.askTotal = askTotal;
    }

    public boolean isAskHasMore() {
        return askHasMore;
    }

    public void setAskHasMore(boolean askHasMore) {
        this.askHasMore = askHasMore;
    }
}
