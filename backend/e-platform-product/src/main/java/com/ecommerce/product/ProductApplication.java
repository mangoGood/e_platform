package com.ecommerce.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 商品服务启动类。
 *
 * <p>{@code @EnableFeignClients} 用于装配 {@code OrderClient}（购买校验）与
 * {@code UserClient}（昵称兜底回源）；
 * {@code @EnableScheduling} 用于启用 {@code RatingSyncJob} 的评分全量校准。
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.ecommerce"})
@MapperScan("com.ecommerce.product.mapper")
@EnableFeignClients(basePackages = "com.ecommerce.product.client")
@EnableScheduling
public class ProductApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
