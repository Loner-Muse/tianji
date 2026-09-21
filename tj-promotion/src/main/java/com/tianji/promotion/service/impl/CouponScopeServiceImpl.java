package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.mapper.CouponScopeMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 优惠券作用范围信息 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-20
 */
@Service
public class CouponScopeServiceImpl extends ServiceImpl<CouponScopeMapper, CouponScope> implements ICouponScopeService {

    @Override
    public void removeByCouponId(Long couponId) {
        baseMapper.delete(new LambdaQueryWrapper<CouponScope>().eq(CouponScope::getCouponId, couponId));
    }
}
