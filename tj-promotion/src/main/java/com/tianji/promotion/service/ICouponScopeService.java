package com.tianji.promotion.service;

import com.tianji.promotion.domain.po.CouponScope;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 优惠券作用范围信息 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-20
 */
public interface ICouponScopeService extends IService<CouponScope> {

    /**
     * 删除某张优惠券下的全部使用范围记录
     *
     * @param couponId 优惠券id（注意：不是 coupon_scope 表自己的主键）
     */
    void removeByCouponId(Long couponId);
}
