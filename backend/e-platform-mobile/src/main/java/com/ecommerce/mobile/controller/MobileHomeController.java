package com.ecommerce.mobile.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.dto.HomeDataVO;
import com.ecommerce.mobile.service.MobileHomeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 移动端首页接口
 * <p>
 * 聚合分类树 + 推荐商品 + 新品 + 轮播图，一次请求获取首页全量数据。
 */
@RestController
@RequestMapping("/mobile/home")
public class MobileHomeController {

    @Autowired
    private MobileHomeService homeService;

    @GetMapping
    public Result<HomeDataVO> getHomeData() {
        return Result.success(homeService.getHomeData());
    }
}
