package com.ecommerce.product.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配。
 *
 * <p><b>修复 Bug-1（商品列表分页完全失效）</b>：MyBatis-Plus 3.4+ 起，分页能力由
 * {@link PaginationInnerInterceptor} 提供，且<b>必须显式注册</b>。此前 product 模块
 * 缺少本配置类，导致 {@code selectPage(page, wrapper)} 退化为「不带 LIMIT 的全表查询」，
 * 表现为：
 * <ul>
 *   <li>{@code size} 参数完全不生效，永远返回全部记录；</li>
 *   <li>{@code total} 恒为 0（count 语句根本没被执行）。</li>
 * </ul>
 *
 * <p>order 模块早已有同名配置，本类与之保持一致，避免同一套代码在不同服务里行为不同。
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页最大条数上限，防止 {@code size=999999} 拖垮数据库。 */
    private static final long MAX_PAGE_SIZE = 100L;

    /**
     * 注册分页内部拦截器。
     *
     * @return MyBatis-Plus 主拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        // 请求页码超过总页数时返回空列表，而不是回到第一页（避免前端翻页出现"鬼打墙"）。
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
