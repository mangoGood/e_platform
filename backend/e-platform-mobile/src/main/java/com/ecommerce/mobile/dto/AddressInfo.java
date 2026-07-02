package com.ecommerce.mobile.dto;

import lombok.Data;

/**
 * 收货地址
 */
@Data
public class AddressInfo {
    private Long id;
    private Long userId;
    private String receiverName;
    private String receiverPhone;
    private String province;
    private String city;
    private String district;
    private String detailAddress;
    private Integer isDefault;
}
