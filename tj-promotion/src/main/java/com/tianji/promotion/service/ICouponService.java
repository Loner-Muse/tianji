package com.tianji.promotion.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponDetailVO;
import com.tianji.promotion.domain.vo.CouponPageVO;

import javax.validation.Valid;

/**
 * <p>
 * 优惠券的规则信息 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-20
 */
public interface ICouponService extends IService<Coupon> {


    void addCoupon(@Valid CouponFormDTO couponFormDTO);

    PageDTO<CouponPageVO> queryCouponByPage(CouponQuery query);

    void beginIssue(Long id, @Valid CouponIssueFormDTO dto);

    void updateCoupon(Long id, @Valid CouponFormDTO dto);

    /**
     * 删除优惠券（只有待发放状态可删，同时会清掉它的使用范围记录）
     */
    void deleteCoupon(Long id);

    /**
     * 根据id查询优惠券详情，若券限定了使用范围，会一并返回分类id和分类名称
     */
    CouponDetailVO queryCouponById(Long id);

    /**
     * 暂停发放优惠券（只有「发放中」的券可以暂停）
     */
    void pauseIssue(Long id);
}
