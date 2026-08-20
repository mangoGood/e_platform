package com.ecommerce.product.controller;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.common.security.GatewayUserContext;
import com.ecommerce.product.dto.CommentAskDTO;
import com.ecommerce.product.dto.CommentCreateDTO;
import com.ecommerce.product.dto.CommentReplyDTO;
import com.ecommerce.product.dto.CommentUpdateDTO;
import com.ecommerce.product.service.CommentService;
import com.ecommerce.product.vo.CanReviewVO;
import com.ecommerce.product.vo.CommentTreeVO;
import com.ecommerce.product.vo.CommentVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 三级评论接口。
 *
 * <h3>路径为什么必须是这几个</h3>
 * 网关的 {@code RoutePermissionRegistry} 已经<b>逐条登记</b>了评论域的访问策略，
 * 且本轮任务禁止改动 gateway 模块，因此控制器路径必须与登记项严格对齐：
 * <pre>
 *   GET    /comment/product/**    PUBLIC                 游客可读评论树
 *   GET    /comment/&#42;/replies      PUBLIC                 游客可展开追问
 *   POST   /comment               需 comment:create       L1 买家评价
 *   POST   /comment/reply         需 comment:reply        L2 卖家回复
 *   POST   /comment/ask           需 comment:ask          L3 第三方追问
 *   PUT    /comment/**            需登录                  编辑
 *   DELETE /comment/**            需登录                  删除
 * </pre>
 * 网关只做"粗粒度门禁"（有没有这个权限码），
 * <b>归属校验（是不是本店、是不是本人、买没买过）一律在服务端做</b>——
 * 权限码人人都有，归属关系才是真正的边界。
 *
 * <h3>身份从哪来</h3>
 * 优先读 {@link GatewayUserContext}（网关签名校验通过后写入，不可伪造），
 * 取不到再退回 {@code X-User-Id} 头以兼容存量链路。游客一律为 0。
 *
 * <h3>为什么控制器上没有 {@code @Valid}</h3>
 * common 模块的 {@code GlobalExceptionHandler} 处理 {@code MethodArgumentNotValidException}
 * 时返回的是裸 {@code Result}，<b>HTTP 状态码仍为 200</b>，只有 body 里的 code 是 400。
 * 那样"参数非法"就无法用状态码断言，与本轮要修的 Bug-2 属于同一类问题；
 * 而 common 模块本轮不允许改动。
 *
 * <p>所以参数校验整体下沉到 {@link CommentService}，
 * 用 {@code BusinessException(BAD_REQUEST, ...)} 抛出，映射为<b>真实 HTTP 400</b>。
 * 这同时消除了"换个入口就能绕过校验"的风险——
 * 存量的 {@code POST /review} 复用的正是同一个服务方法。
 */
@RestController
@RequestMapping("/comment")
public class CommentController {

    @Autowired
    private CommentService commentService;

    /**
     * 查询商品评论树（游客可读）。
     *
     * @param productId   商品 id
     * @param pageNum     L1 页码，默认 1
     * @param pageSize    L1 页大小，默认 10
     * @param sort        排序：{@code latest}（默认）/ {@code rating}
     * @param askSize     每条 L1 展示的追问条数，默认 3
     * @param headerUser  网关下发的用户 id，游客为空
     * @return 评论树 + 评分汇总 + 浏览者上下文
     */
    @GetMapping("/product/{productId}")
    public Result<CommentTreeVO> getProductComments(
            @PathVariable Long productId,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false, defaultValue = "latest") String sort,
            @RequestParam(required = false) Integer askSize,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
        long viewerId = currentUserId(headerUser);
        CommentTreeVO tree = commentService.getProductComments(
                productId, pageNum, pageSize, sort, askSize, viewerId);
        return Result.success(tree);
    }

    /**
     * 展开某条 L1 之下的全部追问（游客可读）。
     *
     * @param rootId     L1 评论 id
     * @param pageNum    页码，默认 1
     * @param pageSize   页大小，默认 10
     * @param headerUser 网关下发的用户 id，游客为空
     * @return 追问分页（时间正序）
     */
    @GetMapping("/{rootId}/replies")
    public Result<PageResult<CommentVO>> getReplies(
            @PathVariable Long rootId,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
        long viewerId = currentUserId(headerUser);
        PageResult<CommentVO> result = commentService.getAsks(rootId, pageNum, pageSize, viewerId);
        return Result.success(result);
    }

    /**
     * 查询「我能否评价该商品」。
     *
     * @param productId  商品 id
     * @param headerUser 网关下发的用户 id
     * @return 判定结果（可评价 / 不可评价 + 原因）
     */
    @GetMapping("/can-review")
    public Result<CanReviewVO> canReview(
            @RequestParam Long productId,
            @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
        long userId = requireLogin(headerUser);
        return Result.success(commentService.canReview(userId, productId));
    }

    /**
     * 发表 L1 买家评价。
     *
     * @param dto        入参
     * @param headerUser 网关下发的用户 id
     * @return 新建评价
     */
    @PostMapping
    public Result<CommentVO> createComment(@RequestBody CommentCreateDTO dto,
                                           @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
        long userId = requireLogin(headerUser);
        return Result.success(commentService.createL1(userId, dto));
    }

    /**
     * 发表 L2 卖家回复。
     *
     * @param dto        入参
     * @param headerUser 网关下发的用户 id
     * @return 新建回复
     */
    @PostMapping("/reply")
    public Result<CommentVO> replyComment(@RequestBody CommentReplyDTO dto,
                                          @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
        long userId = requireLogin(headerUser);
        return Result.success(commentService.replyL2(userId, dto));
    }

    /**
     * 发表 L3 第三方追问。
     *
     * @param dto        入参
     * @param headerUser 网关下发的用户 id
     * @return 新建追问
     */
    @PostMapping("/ask")
    public Result<CommentVO> askComment(@RequestBody CommentAskDTO dto,
                                        @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
        long userId = requireLogin(headerUser);
        return Result.success(commentService.askL3(userId, dto));
    }

    /**
     * 编辑评论（24 小时内，仅作者本人）。
     *
     * @param id         评论 id
     * @param dto        入参
     * @param headerUser 网关下发的用户 id
     * @return 编辑后的评论
     */
    @PutMapping("/{id}")
    public Result<CommentVO> updateComment(@PathVariable Long id,
                                           @RequestBody CommentUpdateDTO dto,
                                           @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
        long userId = requireLogin(headerUser);
        return Result.success(commentService.updateComment(userId, id, dto));
    }

    /**
     * 删除评论（逻辑删除，仅作者本人或管理员）。
     *
     * @param id         评论 id
     * @param headerUser 网关下发的用户 id
     * @return 空结果
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteComment(@PathVariable Long id,
                                      @RequestHeader(value = "X-User-Id", required = false) Long headerUser) {
        long userId = requireLogin(headerUser);
        commentService.deleteComment(userId, id);
        return Result.success();
    }

    /**
     * 解析当前用户 id。
     *
     * @param headerUserId {@code X-User-Id} 头
     * @return 用户 id，游客为 0
     */
    private long currentUserId(Long headerUserId) {
        long fromContext = GatewayUserContext.getUserId();
        if (fromContext > 0L) {
            return fromContext;
        }
        return headerUserId == null ? 0L : headerUserId;
    }

    /**
     * 解析当前用户 id，未登录直接 401。
     *
     * @param headerUserId {@code X-User-Id} 头
     * @return 用户 id，必然大于 0
     */
    private long requireLogin(Long headerUserId) {
        long userId = currentUserId(headerUserId);
        if (userId <= 0L) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return userId;
    }
}
