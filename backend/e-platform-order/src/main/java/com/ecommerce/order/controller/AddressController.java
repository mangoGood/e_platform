package com.ecommerce.order.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.order.entity.Address;
import com.ecommerce.order.service.AddressService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/address")
public class AddressController {

    @Autowired
    private AddressService addressService;

    @GetMapping("/list")
    public Result<List<Address>> getAddresses(@RequestHeader("X-User-Id") Long userId) {
        List<Address> addresses = addressService.getAddressesByUserId(userId);
        return Result.success(addresses);
    }

    @GetMapping("/{id}")
    public Result<Address> getAddress(@PathVariable Long id,
                                      @RequestHeader("X-User-Id") Long userId) {
        Address address = addressService.getAddressByIdAndUserId(id, userId);
        return Result.success(address);
    }

    @PostMapping
    public Result<Address> addAddress(@RequestBody Address address,
                                       @RequestHeader("X-User-Id") Long userId) {
        address.setUserId(userId);
        Address created = addressService.addAddress(address);
        return Result.success(created);
    }

    @PutMapping
    public Result<Address> updateAddress(@RequestBody Address address,
                                          @RequestHeader("X-User-Id") Long userId) {
        address.setUserId(userId);
        Address updated = addressService.updateAddress(address);
        return Result.success(updated);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteAddress(@PathVariable Long id,
                                       @RequestHeader("X-User-Id") Long userId) {
        addressService.deleteAddress(id, userId);
        return Result.success(null);
    }

    @PutMapping("/default/{id}")
    public Result<Void> setDefault(@PathVariable Long id,
                                    @RequestHeader("X-User-Id") Long userId) {
        addressService.setDefault(id, userId);
        return Result.success(null);
    }
}
