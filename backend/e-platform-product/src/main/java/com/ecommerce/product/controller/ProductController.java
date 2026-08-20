package com.ecommerce.product.controller;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.result.PageResult;
import com.ecommerce.common.result.Result;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 商品接口。
 *
 * <h3>本轮修复的三个存量问题</h3>
 * <ol>
 *   <li><b>越权响应码不真实</b>：原先 5 处写的是 {@code return Result.error(403, ...)}，
 *       HTTP 状态仍然是 200，只有 body 里的 code 是 403。
 *       结果就是 {@code curl -w '%{http_code}'} 打出来永远是 200，越权测试形同虚设，
 *       前端的 axios 拦截器也捕获不到。现已全部改为抛
 *       {@link BusinessException}，由 {@code GlobalExceptionHandler} 映射为<b>真实的 HTTP 403</b>；</li>
 *   <li><b>内部令牌硬编码弱口令</b>：原先 {@code @Value("${internal.token:ePlatformInternalSecret2026}")}
 *       把一个默认密码提交进了仓库——任何人读一眼源码就能直接调用扣减库存接口。
 *       现改为无默认值，<b>未配置即全量拒绝</b>（见 {@link #assertInternalToken}）；</li>
 *   <li><b>分页参数名不兼容</b>：{@code /product/list} 只认 {@code current/size}，
 *       而前端与文档普遍用 {@code pageNum/pageSize}，传了等于没传，于是"翻页无效"。
 *       现在两套名称都接受（见 {@link #getProductList}）。</li>
 * </ol>
 */
@RestController
@RequestMapping("/product")
public class ProductController {
    @Autowired
    private ProductService productService;

    /**
     * 服务间直连令牌。
     *
     * <p><b>刻意不设默认值。</b>写死一个 {@code ePlatformInternalSecret2026} 之类的兜底值，
     * 等于把生产环境的内部接口钥匙印在了公开仓库里。
     * 未配置时本字段为空串，所有内部接口一律拒绝——宁可"配错了不能用"，
     * 也不要"没配置却能用，而且用的是全世界都知道的密码"。
     */
    @Value("${internal.token:}")
    private String internalToken = "";

    @PostMapping("/add")
    public Result<Void> addProduct(@Valid @RequestBody ProductRequest request,
                                   @RequestHeader("X-User-Id") Long sellerId,
                                   @RequestHeader(value = "X-User-Type", required = false) Integer userType) {
        assertSeller(userType, "只有卖家才能添加商品");
        productService.addProduct(request, sellerId);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result<Void> updateProduct(@PathVariable Long id,
                                     @Valid @RequestBody ProductRequest request,
                                     @RequestHeader("X-User-Id") Long sellerId,
                                     @RequestHeader(value = "X-User-Type", required = false) Integer userType) {
        assertSeller(userType, "只有卖家才能修改商品");
        productService.updateProduct(id, request, sellerId);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteProduct(@PathVariable Long id,
                                     @RequestHeader("X-User-Id") Long sellerId,
                                     @RequestHeader(value = "X-User-Type", required = false) Integer userType) {
        assertSeller(userType, "只有卖家才能删除商品");
        productService.deleteProduct(id, sellerId);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<Product> getProductById(@PathVariable Long id) {
        Product product = productService.getProductById(id);
        return Result.success(product);
    }

    /**
     * 商品分页列表。
     *
     * <p>同时接受 {@code current/size}（存量移动端在用）与 {@code pageNum/pageSize}（文档与 Web 端在用），
     * 后者优先。两套名称并存是为了不打断任何一方的调用方。
     *
     * @param current    页码（旧名）
     * @param size       页大小（旧名）
     * @param pageNum    页码（新名，优先）
     * @param pageSize   页大小（新名，优先）
     * @param categoryId 分类过滤
     * @param keyword    关键词过滤
     * @return 分页结果
     */
    @GetMapping("/list")
    public Result<PageResult<Product>> getProductList(
            @RequestParam(required = false) Integer current,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword) {
        Integer resolvedPage = pageNum != null ? pageNum : current;
        Integer resolvedSize = pageSize != null ? pageSize : size;
        PageResult<Product> result = productService.getProductList(resolvedPage, resolvedSize, categoryId, keyword);
        return Result.success(result);
    }

    @GetMapping("/seller/{sellerId}")
    public Result<List<Product>> getProductsBySellerId(@PathVariable Long sellerId) {
        List<Product> products = productService.getProductsBySellerId(sellerId);
        return Result.success(products);
    }

    @GetMapping("/batch")
    public Result<List<Product>> getProductsByIds(@RequestParam List<Long> ids) {
        List<Product> products = productService.getProductsByIds(ids);
        return Result.success(products);
    }

    @PutMapping("/{id}/deduct")
    public Result<Void> deductStock(@PathVariable Long id, @RequestParam Integer quantity,
                                    @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        assertInternalToken(token);
        productService.deductStock(id, quantity);
        return Result.success();
    }

    @PutMapping("/{id}/restore")
    public Result<Void> restoreStock(@PathVariable Long id, @RequestParam Integer quantity,
                                     @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        assertInternalToken(token);
        productService.restoreStock(id, quantity);
        return Result.success();
    }

    /**
     * 校验卖家身份，不通过抛真实 403。
     *
     * @param userType 网关下发的用户类型，2 为卖家
     * @param message  拒绝文案
     */
    private void assertSeller(Integer userType, String message) {
        if (userType == null || userType != 2) {
            throw new BusinessException(ErrorCode.FORBIDDEN, message);
        }
    }

    /**
     * 校验内部服务令牌，不通过抛真实 403。
     *
     * <p>令牌未配置（空串）时<b>无条件拒绝</b>：这种情况下没有任何调用方能提供"正确"的令牌，
     * 与其静默放行，不如让内部调用明确地失败，逼迫部署方把 {@code INTERNAL_TOKEN} 配上。
     *
     * @param token 请求头中的 {@code X-Internal-Token}
     */
    private void assertInternalToken(String token) {
        if (internalToken == null || internalToken.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口未启用（服务端未配置 INTERNAL_TOKEN）");
        }
        if (token == null || !internalToken.equals(token)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限访问该接口");
        }
    }
}
