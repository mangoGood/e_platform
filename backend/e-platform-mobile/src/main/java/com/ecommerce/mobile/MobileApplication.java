package com.ecommerce.mobile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 移动端 BFF 聚合服务启动类
 * <p>
 * 聚合 user / product / order 三个微服务的数据，
 * 为 Android 客户端提供一次性获取页面全量数据的接口，减少移动端网络往返。
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableFeignClients
public class MobileApplication {
    public static void main(String[] args) {
        SpringApplication.run(MobileApplication.class, args);
    }
}
