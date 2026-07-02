package com.ecommerce.order.client;

import com.ecommerce.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@FeignClient(name = "e-platform-product", url = "http://localhost:8086")
public interface ProductClient {

    @GetMapping("/product/{id}")
    Result<ProductInfo> getProductById(@PathVariable("id") Long id);

    @PutMapping("/product/{id}/deduct")
    Result<Void> deductStock(@PathVariable("id") Long id, @RequestParam("quantity") Integer quantity);

    @PutMapping("/product/{id}/restore")
    Result<Void> restoreStock(@PathVariable("id") Long id, @RequestParam("quantity") Integer quantity);

    class ProductInfo {
        private Long id;
        private Long sellerId;
        private String name;
        private BigDecimal price;
        private Integer stock;
        private String mainImage;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getSellerId() { return sellerId; }
        public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public Integer getStock() { return stock; }
        public void setStock(Integer stock) { this.stock = stock; }
        public String getMainImage() { return mainImage; }
        public void setMainImage(String mainImage) { this.mainImage = mainImage; }
    }
}
