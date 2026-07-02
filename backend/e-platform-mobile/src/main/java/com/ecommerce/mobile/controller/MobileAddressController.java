package com.ecommerce.mobile.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.dto.AddressInfo;
import com.ecommerce.mobile.service.MobileAddressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 移动端收货地址接口
 */
@RestController
@RequestMapping("/mobile/addresses")
public class MobileAddressController {

    @Autowired
    private MobileAddressService addressService;

    /**
     * 地址列表
     */
    @GetMapping
    public Result<List<AddressInfo>> list() {
        return Result.success(addressService.getAddresses());
    }

    /**
     * 新增地址
     */
    @PostMapping
    public Result<AddressInfo> add(@Valid @RequestBody AddressInfo address) {
        return Result.success(addressService.addAddress(address));
    }

    /**
     * 修改地址
     */
    @PutMapping
    public Result<AddressInfo> update(@Valid @RequestBody AddressInfo address) {
        return Result.success(addressService.updateAddress(address));
    }

    /**
     * 删除地址
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        addressService.deleteAddress(id);
        return Result.success();
    }

    /**
     * 设为默认地址
     */
    @PutMapping("/default/{id}")
    public Result<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(id);
        return Result.success();
    }
}
