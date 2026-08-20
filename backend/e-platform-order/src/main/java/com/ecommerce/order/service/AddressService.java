package com.ecommerce.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ErrorCode;
import com.ecommerce.order.entity.Address;
import com.ecommerce.order.mapper.AddressMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class AddressService {

    @Autowired
    private AddressMapper addressMapper;

    public List<Address> getAddressesByUserId(Long userId) {
        LambdaQueryWrapper<Address> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Address::getUserId, userId)
               .orderByDesc(Address::getIsDefault)
               .orderByDesc(Address::getUpdateTime)
               .orderByDesc(Address::getId);
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

    /**
     * 修改收货地址，仅限地址所有者本人。
     *
     * <p><b>原实现把「不存在」与「越权」合并成一个 500 软失败</b>：
     * {@code existing == null || !existing.getUserId().equals(...)} 共用一条
     * {@code BusinessException("地址不存在或无权限修改")}，HTTP 恒为 200。
     * 这与 {@code ProductService} 的越权缺陷、{@code getOrderByIdAndUserId} 的
     * null 兼并问题是<b>同一类</b>：归属校验失败被降级成了业务软失败。
     *
     * <p>现拆成两条判定：地址不存在 → {@link ErrorCode#NOT_FOUND}(404)，
     * 地址存在但不属于调用方 → {@link ErrorCode#FORBIDDEN}(403)。
     *
     * @param address 待更新地址，{@code userId} 由 Controller 以网关下发的身份覆盖
     * @return 更新后的地址
     * @throws BusinessException 地址不存在时 404；非本人地址时 403
     */
    @Transactional
    public Address updateAddress(Address address) {
        Address existing = requireAddress(address.getId());
        if (!Objects.equals(existing.getUserId(), address.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限修改该地址");
        }
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            clearDefault(address.getUserId());
        }
        addressMapper.updateById(address);
        return address;
    }

    /**
     * 删除收货地址，仅限地址所有者本人。
     *
     * <h3>本轮修复：静默成功（对调用方撒谎）</h3>
     * 原实现只发一条 {@code DELETE ... WHERE id = ? AND user_id = ?}，
     * <b>完全不看影响行数</b>。于是删别人的地址、或删一个根本不存在的 id，
     * 受 {@code user_id} 作用域保护确实<b>没删到任何东西</b>，
     * 但接口照样返回 {@code HTTP 200 + success:true「操作成功」}。
     *
     * <p>这不是数据泄露（写入被挡住了），但<b>API 在对调用方撒谎</b>：
     * 客户端据此把该地址从列表里移除，刷新后它又回来了。
     * 而同一个 Service 里的 {@link #updateAddress} 早已是 404/403——
     * 两套口径。现统一：不存在 404，非本人 403。
     *
     * <p>校验通过后<b>仍保留 {@code user_id} 作用域的删除条件</b>作为纵深防御：
     * 校验与写入之间存在极小的竞态窗口，多一个 where 条件不花钱。
     *
     * @param id     地址 id
     * @param userId 操作人 id（网关下发的 {@code X-User-Id}）
     * @throws BusinessException 地址 id 不合法时 400；地址不存在时 404；非本人地址时 403
     */
    @Transactional
    public void deleteAddress(Long id, Long userId) {
        Address existing = requireAddress(id);
        if (!Objects.equals(existing.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限删除该地址");
        }
        LambdaQueryWrapper<Address> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Address::getId, id).eq(Address::getUserId, userId);
        addressMapper.delete(wrapper);
    }

    /**
     * 设置默认收货地址，仅限地址所有者本人。
     *
     * <h3>本轮修复之一：静默成功</h3>
     * 与 {@link #deleteAddress} 同源——原实现不校验存在性与归属，
     * 对不存在的 id 或别人的地址一律返回「操作成功」。
     *
     * <h3>本轮修复之二：先清后校验，误伤调用方自己的默认地址</h3>
     * 这是比"静默成功"更严重的一处：原实现<b>第一行</b>就无条件执行
     * {@code clearDefault(userId)}，把调用方名下<b>已有的默认地址清成 0</b>，
     * 之后那条带 {@code user_id} 作用域的 update 才因为不匹配而影响 0 行。
     * 净效果是——<b>「把别人的地址设为默认」这个非法操作，
     * 成功地清掉了你自己原本的默认地址，然后告诉你"操作成功"</b>。
     * 用户下次下单会发现默认收货地址凭空消失了。
     *
     * <p>现在把校验提到 {@code clearDefault} <b>之前</b>：非法请求在任何写入
     * 发生前就被拒绝，自己的默认地址不受影响。
     *
     * @param id     地址 id
     * @param userId 操作人 id（网关下发的 {@code X-User-Id}）
     * @throws BusinessException 地址 id 不合法时 400；地址不存在时 404；非本人地址时 403
     */
    @Transactional
    public void setDefault(Long id, Long userId) {
        Address existing = requireAddress(id);
        if (!Objects.equals(existing.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限设置该地址为默认");
        }

        clearDefault(userId);
        LambdaUpdateWrapper<Address> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Address::getId, id)
                     .eq(Address::getUserId, userId)
                     .set(Address::getIsDefault, 1);
        addressMapper.update(null, updateWrapper);
    }

    /**
     * 查询地址，不存在即 404。
     *
     * <p>与 {@code OrderService.requireOrder} 刻意保持同构：
     * id 不合法 400、查不到 404、查到了交给调用方做归属判定（403）。
     * 归属校验留在各方法内，是为了让 403 文案能贴合具体动作
     * （修改 / 删除 / 设为默认）。
     *
     * <p>{@code Address} 的 {@code deleted} 字段带 {@code @TableLogic}，
     * {@code selectById} 会自动过滤逻辑删除的行——已删除的地址再次操作
     * 会正确地得到 404 而不是"操作成功"。
     *
     * @param id 地址 id
     * @return 地址，<b>永不为 null</b>
     * @throws BusinessException id 不合法时 400；地址不存在时 404
     */
    private Address requireAddress(Long id) {
        if (id == null || id <= 0L) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "地址ID不合法");
        }
        Address existing = addressMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "地址不存在");
        }
        return existing;
    }

    private void clearDefault(Long userId) {
        LambdaUpdateWrapper<Address> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Address::getUserId, userId)
                     .eq(Address::getIsDefault, 1)
                     .set(Address::getIsDefault, 0);
        addressMapper.update(null, updateWrapper);
    }
}
