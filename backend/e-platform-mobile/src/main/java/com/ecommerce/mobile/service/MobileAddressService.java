package com.ecommerce.mobile.service;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.client.OrderClient;
import com.ecommerce.mobile.context.UserContext;
import com.ecommerce.mobile.dto.AddressInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 收货地址服务
 */
@Service
public class MobileAddressService {

    @Autowired
    private OrderClient orderClient;

    /**
     * 地址列表
     */
    public List<AddressInfo> getAddresses() {
        Long userId = UserContext.getUserId();
        Result<List<AddressInfo>> result = orderClient.getAddresses(userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "获取地址列表失败");
        }
        return result.getData();
    }

    /**
     * 新增地址
     */
    public AddressInfo addAddress(AddressInfo address) {
        Long userId = UserContext.getUserId();
        Result<AddressInfo> result = orderClient.addAddress(address, userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "新增地址失败");
        }
        return result.getData();
    }

    /**
     * 修改地址
     */
    public AddressInfo updateAddress(AddressInfo address) {
        Long userId = UserContext.getUserId();
        Result<AddressInfo> result = orderClient.updateAddress(address, userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "修改地址失败");
        }
        return result.getData();
    }

    /**
     * 删除地址
     */
    public void deleteAddress(Long id) {
        Long userId = UserContext.getUserId();
        Result<Void> result = orderClient.deleteAddress(id, userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "删除地址失败");
        }
    }

    /**
     * 设为默认地址
     */
    public void setDefault(Long id) {
        Long userId = UserContext.getUserId();
        Result<Void> result = orderClient.setDefaultAddress(id, userId);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "设置默认地址失败");
        }
    }
}
