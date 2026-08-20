package com.ecommerce.product.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 三级评论实体，映射 {@code comment} 表全字段。
 *
 * <p>存量的 {@code Review} 实体只映射了部分列，且语义停留在"一维评价"，
 * 因此新建本实体承载三级结构，{@code Review} 降级为兼容层使用。
 *
 * <h3>层级不变式（由 {@code CommentService} 在服务端强制，永不信任前端传值）</h3>
 * <table border="1">
 *   <caption>三层结构约束</caption>
 *   <tr><th>层级</th><th>type</th><th>parentId</th><th>rootId</th><th>rating</th><th>orderId</th></tr>
 *   <tr><td>L1 买家评价</td><td>1</td><td>0</td><td>自身 id</td><td>1-5 必填</td><td>非空</td></tr>
 *   <tr><td>L2 卖家回复</td><td>2</td><td>L1.id</td><td>L1.id</td><td>null</td><td>null</td></tr>
 *   <tr><td>L3 第三方追问</td><td>3</td><td><b>L1.id</b></td><td>L1.id</td><td>null</td><td>null</td></tr>
 * </table>
 *
 * <p><b>永远不产生第 4 层</b>：L3 回复某个具体的人只体现在 {@code replyToUserId} 上，
 * DB 结构上依然平铺挂在 L1 之下。
 */
@TableName("comment")
public class Comment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** L1 买家评价。 */
    public static final int TYPE_L1_REVIEW = 1;

    /** L2 卖家回复。 */
    public static final int TYPE_L2_REPLY = 2;

    /** L3 第三方追问。 */
    public static final int TYPE_L3_ASK = 3;

    /** 状态：显示。 */
    public static final int STATUS_VISIBLE = 1;

    /** 状态：隐藏。 */
    public static final int STATUS_HIDDEN = 0;

    /** L1 的 parentId 恒为 0。 */
    public static final long NO_PARENT = 0L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发表者用户 ID。 */
    private Long userId;

    /** 发表者用户名快照，写入时落库，避免读取评论树时 N+1 跨服务调用。 */
    private String username;

    /** 商品 ID。 */
    private Long productId;

    /** 订单 ID：仅 L1 非空，参与唯一约束 {@code uk_order_product_user}。 */
    private Long orderId;

    /** 订单明细 ID：仅 L1 有值，用于定位具体购买的那一行。 */
    private Long orderItemId;

    /** 父评论 ID：L1 为 0，L2/L3 均为所属 L1 的 id。 */
    private Long parentId;

    /** 根评论 ID：L1 为自身 id，L2/L3 为所属 L1 的 id。 */
    private Long rootId;

    /** 类型，见 {@link #TYPE_L1_REVIEW} / {@link #TYPE_L2_REPLY} / {@link #TYPE_L3_ASK}。 */
    private Integer type;

    /** 被回复用户 ID，仅 L3 使用，用于渲染 {@code @昵称}。 */
    private Long replyToUserId;

    /** 被回复用户名快照，仅 L3 使用。 */
    private String replyToUsername;

    /** 商品所属卖家 ID（冗余列），用于鉴权与卖家维度查询。 */
    private Long sellerId;

    /** 子级（L2 + L3）数量，仅 L1 维护。 */
    private Integer replyCount;

    /** 评分 1-5，仅 L1 有值。 */
    private Integer rating;

    /** 正文。 */
    private String content;

    /** 图片 JSON 数组，本期前端不暴露入口，接口保留。 */
    private String images;

    /** 状态：0-隐藏，1-显示。 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    /**
     * @return 是否为 L1 买家评价
     */
    public boolean isL1() {
        return type != null && type == TYPE_L1_REVIEW;
    }

    /**
     * @return 是否为 L2 卖家回复
     */
    public boolean isL2() {
        return type != null && type == TYPE_L2_REPLY;
    }

    /**
     * @return 是否为 L3 第三方追问
     */
    public boolean isL3() {
        return type != null && type == TYPE_L3_ASK;
    }

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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getOrderItemId() {
        return orderItemId;
    }

    public void setOrderItemId(Long orderItemId) {
        this.orderItemId = orderItemId;
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

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Long getReplyToUserId() {
        return replyToUserId;
    }

    public void setReplyToUserId(Long replyToUserId) {
        this.replyToUserId = replyToUserId;
    }

    public String getReplyToUsername() {
        return replyToUsername;
    }

    public void setReplyToUsername(String replyToUsername) {
        this.replyToUsername = replyToUsername;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public void setSellerId(Long sellerId) {
        this.sellerId = sellerId;
    }

    public Integer getReplyCount() {
        return replyCount;
    }

    public void setReplyCount(Integer replyCount) {
        this.replyCount = replyCount;
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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
