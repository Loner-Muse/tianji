package com.tianji.promotion.service;

import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 兑换码 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-20
 */
public interface IExchangeCodeService extends IService<ExchangeCode> {

    /**
     * 异步生成优惠券的兑换码
     *
     * @param coupon 优惠券
     */
    void asyncGenerateCode(Coupon coupon);
}
