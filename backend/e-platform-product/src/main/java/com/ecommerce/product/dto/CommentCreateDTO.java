package com.ecommerce.product.dto;

import java.io.Serializable;

/**
 * L1 买家评价入参。
 *
 * <p><b>刻意不接收 {@code orderId}</b>：订单归属只能由服务端向 order 服务核实，
 * 一旦允许前端传，攻击者随便编一个 orderId 就能绕过购买校验。
 * 服务端会自行查出「该用户对该商品最近一笔已完成订单」并落库。
 * {@code type / parentId / rootId / sellerId} 同理，一律服务端计算，前端传了也被忽略。
 *
 * <h3>为什么不用 {@code @NotNull / @Size} 这类 Bean Validation</h3>
 * common 模块的 {@code GlobalExceptionHandler} 对 {@code MethodArgumentNotValidException}
 * 返回的是裸 {@code Result}，<b>HTTP 状态码仍然是 200</b>，只有 body 里的 code 是 400——
 * 这与本轮要修的 Bug-2（{@code Result.error(403,...)} 让越权测试失真）是同一类问题。
 * 而 common 模块本轮不允许改动。
 *
 * <p>因此校验统一下沉到 {@code CommentService}，用 {@code BusinessException(BAD_REQUEST, ...)}
 * 抛出，由 {@code GlobalExceptionHandler} 映射成<b>真实的 HTTP 400</b>。
 * 附带好处：{@code /comment} 和存量 {@code /review} 两个入口共用同一段校验，
 * 不存在"从某个入口进来就能绕过"的可能。
 */
public class CommentCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品 id，必填，服务端校验存在性。 */
    private Long productId;

    /** 评分，必填，服务端校验取值 1-5。 */
    private Integer rating;

    /** 评价正文，服务端校验 5-500 字（按码点计数）。 */
    private String content;

    /** 图片 JSON 数组，可为空。 */
    private String images;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
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
}
