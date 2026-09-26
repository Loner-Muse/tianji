package com.tianji.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;

/**
 * <p>
 * 用户领取优惠券的记录 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-21
 */
public interface IUserCouponService extends IService<UserCoupon> {

    /**
     * 领取优惠券
     *
     * @param couponId 优惠券id
     */
    void receiveCoupon(Long couponId);

    /**
     * 校验之后，生成一张用户券（手动领取和兑换码兑换共用的核心逻辑）
     * <p>
     * 三步：校验每人限领数量 → 扣减券库存 → 新增 user_coupon 记录。
     * <p>
     * 抽成公共方法的原因：两条领券路径（{@link #receiveCoupon(Long)} 和
     * {@link #exchangeCoupon(String)}）在这一段完全一致，
     * 而后面并发安全改造（乐观锁、加锁、事务边界）也全部落在这里，
     * 只有一份才能保证改一处即两边生效。
     * <p>
     * 之所以要放到接口上，是因为事务注解要求方法必须是被 Spring 代理的 public 方法。
     *
     * @param coupon    优惠券（调用方已经查好并校验过状态、库存）
     * @param userId    领取人
     * @param serialNum 兑换码序列号。手动领取传 {@code null}；
     *                  兑换码兑换传序列号，用来到时候把该兑换码标记为已使用
     */
    void checkAndCreateUserCoupon(Coupon coupon, Long userId, Long serialNum);

    /**
     * 使用兑换码兑换优惠券
     *
     * @param code 兑换码
     */
    void exchangeCoupon(String code);

    /**
     * 分页查询我的优惠券
     *
     * @param query 分页参数 + 券状态过滤条件
     */
    PageDTO<CouponVO> queryMyCouponPage(UserCouponQuery query);
}
