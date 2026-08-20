package com.ecommerce.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.common.result.PageResult;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.entity.Category;
import com.ecommerce.product.entity.Product;
import com.ecommerce.product.mapper.CategoryMapper;
import com.ecommerce.product.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ProductService {

    /** 默认页码。 */
    private static final long DEFAULT_PAGE_NUM = 1L;

    /** 默认页大小。 */
    private static final long DEFAULT_PAGE_SIZE = 10L;

    /** 单页最大条数，与 {@code MybatisPlusConfig} 的 maxLimit 保持一致。 */
    private static final long MAX_PAGE_SIZE = 100L;

    @Autowired
    private ProductMapper productMapper;
    
    @Autowired
    private CategoryMapper categoryMapper;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    public void addProduct(ProductRequest request, Long sellerId) {
        Category category = categoryMapper.selectById(request.getCategoryId());
        if (category == null) {
            throw new BusinessException("分类不存在");
        }
        
        Product product = new Product();
        product.setCategoryId(request.getCategoryId());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        product.setStock(request.getStock());
        product.setMainImage(request.getMainImage());
        product.setImages(request.getImages());
        product.setSellerId(sellerId);
        product.setStatus(1);
        product.setSales(0);
        
        productMapper.insert(product);
    }

    /**
     * 卖家修改自己名下的商品。
     *
     * <p><b>归属校验必须落真实 HTTP 状态码</b>：这两处此前用的是单参
     * {@code BusinessException(String)}，会把 code 置成
     * {@link ErrorCode#BUSINESS_FAILED}（500 软失败，HTTP 仍返回 200）——
     * 于是"卖家 B 改卖家 A 的商品"这种越权，对外表现成
     * {@code HTTP 200 + code 500}，{@code tests/security_test.sh} 按 HTTP 码
     * 断言 403 时必然失败。越权是<b>拒绝</b>，不是"业务软失败"。
     *
     * @param id       商品 id
     * @param request  修改内容
     * @param sellerId 操作人 id（必须是商品所属卖家）
     * @throws BusinessException 商品不存在时 404；非本人商品时 403
     */
    public void updateProduct(Long id, ProductRequest request, Long sellerId) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        
        if (!product.getSellerId().equals(sellerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限修改该商品");
        }
        
        product.setCategoryId(request.getCategoryId());
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setOriginalPrice(request.getOriginalPrice());
        product.setStock(request.getStock());
        product.setMainImage(request.getMainImage());
        product.setImages(request.getImages());
        productMapper.updateById(product);
        
        String cacheKey = "product:" + id;
        redisTemplate.delete(cacheKey);
    }

    /**
     * 卖家删除自己名下的商品。
     *
     * <p>与 {@link #updateProduct} 同源的问题与同样的修法：归属校验失败必须是
     * {@link ErrorCode#FORBIDDEN}(403)，商品不存在必须是
     * {@link ErrorCode#NOT_FOUND}(404)，都不能落成 500 软失败。
     *
     * @param id       商品 id
     * @param sellerId 操作人 id（必须是商品所属卖家）
     * @throws BusinessException 商品不存在时 404；非本人商品时 403
     */
    public void deleteProduct(Long id, Long sellerId) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        
        if (!product.getSellerId().equals(sellerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限删除该商品");
        }
        
        productMapper.deleteById(id);
        
        String cacheKey = "product:" + id;
        redisTemplate.delete(cacheKey);
    }

    /**
     * 按 id 查商品详情。
     *
     * <h3>本轮修复：{@code success:true} + {@code data:null} 的假成功</h3>
     * 原实现查不到直接 {@code return null}，Controller 原样包成
     * {@code Result.success(null)}，对外表现为
     * {@code HTTP 200 {"code":200,"message":"操作成功","data":null,"success":true}}。
     * 调用方<b>无法区分</b>「商品不存在」与「商品存在但字段为空」——
     * 任何按 {@code success} 判断的客户端都会认为这次读取合法，
     * 与 {@code getOrderByIdAndUserId} 此前的 null 兼并问题是同一类。
     * 现改为抛 {@link ErrorCode#NOT_FOUND}(404)，与
     * {@link #updateProduct}、{@link #deleteProduct} 的「商品不存在」口径一致。
     *
     * <h3>关于 Redis 缓存分支</h3>
     * 缓存<b>从不写入 null 或空对象</b>（只有 {@code product != null} 才 set），
     * 因此不存在「缓存了不存在」的负缓存，本次改动与缓存无交互：
     * 命中缓存说明商品必然存在，未命中才回源 DB，回源为空即 404。
     * 副作用是不存在的 id 每次都会穿透到 DB——这是<b>改动前就有</b>的行为，
     * 本轮不引入负缓存（那会让「新建商品后立刻可见」出现窗口期）。
     *
     * <h3>不加 {@code id <= 0} 的 400 分支</h3>
     * 刻意与 {@code OrderService.requireOrder} 有别：非正数 id 经
     * {@code selectById} 自然返回 null，落到同一个 404「商品不存在」即可，
     * 语义已经正确，多开一个 400 分支只会扩大调用方需要处理的状态码集合。
     *
     * @param id 商品 id
     * @return 商品，<b>永不为 null</b>
     * @throws BusinessException 商品不存在时 404
     */
    public Product getProductById(Long id) {
        String cacheKey = "product:" + id;
        Product product = (Product) redisTemplate.opsForValue().get(cacheKey);
        
        if (product == null) {
            product = productMapper.selectById(id);
            if (product == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
            }
            redisTemplate.opsForValue().set(cacheKey, product, 1, TimeUnit.HOURS);
        }
        
        return product;
    }

    /**
     * 商品分页列表。
     *
     * <p><b>分页失效的根因不在这里</b>：本方法一直正确地构造了 {@code Page} 并调用 {@code selectPage}，
     * 但 product 模块此前<b>缺少 {@code PaginationInnerInterceptor}</b>（order 模块有），
     * MyBatis-Plus 于是既不拼 {@code LIMIT} 也不发 count 查询——
     * 表现就是"传 pageSize=3 却返回 10 条，且 total 恒为 0"。
     * 拦截器已在 {@code MybatisPlusConfig} 中补齐，这里只负责参数兜底。
     *
     * @param current    页码，null 视为 1
     * @param size       页大小，null 视为 10，上限 100（由拦截器 maxLimit 再兜一层）
     * @param categoryId 分类过滤，可为 null
     * @param keyword    关键词过滤，可为空
     * @return 分页结果
     */
    public PageResult<Product> getProductList(Integer current, Integer size, Long categoryId, String keyword) {
        long pageNum = (current == null || current < 1) ? DEFAULT_PAGE_NUM : current;
        long pageSize = (size == null || size < 1) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        Page<Product> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        
        wrapper.eq(Product::getStatus, 1);
        
        if (categoryId != null) {
            wrapper.eq(Product::getCategoryId, categoryId);
        }
        
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Product::getName, keyword);
        }
        
        // 与订单列表同款隐患：这是分页查询，createTime 并列时 LIMIT/OFFSET 没有确定行序，
        // 会导致商品在翻页时重复出现或被永久跳过。主键兜底键是分页正确性的硬要求。
        wrapper.orderByDesc(Product::getCreateTime)
               .orderByDesc(Product::getId);
        
        Page<Product> productPage = productMapper.selectPage(page, wrapper);
        
        return new PageResult<>(productPage.getRecords(), productPage.getTotal(), 
                               productPage.getSize(), productPage.getCurrent());
    }

    public List<Product> getProductsBySellerId(Long sellerId) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getSellerId, sellerId)
               .orderByDesc(Product::getCreateTime)
               .orderByDesc(Product::getId);
        return productMapper.selectList(wrapper);
    }

    public List<Product> getProductsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return productMapper.selectBatchIds(ids);
    }

    public void deductStock(Long productId, Integer quantity) {
        int rows = productMapper.deductStock(productId, quantity);
        if (rows == 0) {
            // 扣减失败说明并发下库存已被抢空，属于状态冲突 → 409，
            // 与 OrderService 下单前置校验的库存不足口径保持一致。
            throw new BusinessException(ErrorCode.CONFLICT, "库存不足");
        }
        redisTemplate.delete("product:" + productId);
    }

    public void restoreStock(Long productId, Integer quantity) {
        productMapper.restoreStock(productId, quantity);
        redisTemplate.delete("product:" + productId);
    }
}
