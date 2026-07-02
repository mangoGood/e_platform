package com.ecommerce.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ecommerce.order.entity.Address;
import com.ecommerce.order.mapper.AddressMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AddressService {

    @Autowired
    private AddressMapper addressMapper;

    public List<Address> getAddressesByUserId(Long userId) {
        LambdaQueryWrapper<Address> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Address::getUserId, userId)
               .orderByDesc(Address::getIsDefault)
               .orderByDesc(Address::getUpdateTime);
        return addressMapper.selectList(wrapper);
    }

    public Address getAddressById(Long id) {
        return addressMapper.selectById(id);
    }

    public Address getAddressByIdAndUserId(Long id, Long userId) {
        LambdaQueryWrapper<Address> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Address::getId, id).eq(Address::getUserId, userId);
        return addressMapper.selectOne(wrapper);
    }

    @Transactional
    public Address addAddress(Address address) {
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            clearDefault(address.getUserId());
        }
        if (address.getIsDefault() == null) {
            address.setIsDefault(0);
        }
        addressMapper.insert(address);
        return address;
    }

    @Transactional
    public Address updateAddress(Address address) {
        Address existing = addressMapper.selectById(address.getId());
        if (existing == null || !existing.getUserId().equals(address.getUserId())) {
            throw new com.ecommerce.common.exception.BusinessException("地址不存在或无权限修改");
        }
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            clearDefault(address.getUserId());
        }
        addressMapper.updateById(address);
        return address;
    }

    @Transactional
    public void deleteAddress(Long id, Long userId) {
        LambdaQueryWrapper<Address> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Address::getId, id).eq(Address::getUserId, userId);
        addressMapper.delete(wrapper);
    }

    @Transactional
    public void setDefault(Long id, Long userId) {
        clearDefault(userId);
        LambdaUpdateWrapper<Address> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Address::getId, id)
                     .eq(Address::getUserId, userId)
                     .set(Address::getIsDefault, 1);
        addressMapper.update(null, updateWrapper);
    }

    private void clearDefault(Long userId) {
        LambdaUpdateWrapper<Address> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Address::getUserId, userId)
                     .eq(Address::getIsDefault, 1)
                     .set(Address::getIsDefault, 0);
        addressMapper.update(null, updateWrapper);
    }
}
