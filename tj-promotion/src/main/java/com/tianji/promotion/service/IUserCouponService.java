package com.tianji.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;

import java.util.List;

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
     * 校验之后，生成一张用户券：三条路径共用的落库逻辑
     * <p>
     * 两步：扣减券库存 → 新增 user_coupon 记录
     * （消息里带了 serialNum 时，再把对应兑换码标记为已使用）。
     * <p>
     * ★ 这里<b>不再校验每人限领</b>：限领已由 {@link #receiveCoupon(Long)} 用 Redis
     * 的 {@code HINCRBY} 原子校验，Redis 计数是唯一权威。DB 侧再查一次 count(*)
     * 既拦不住（非原子），又会误判（DB 计数滞后于 Redis 计数 → 假阳性 → 消息无限重试）。
     * <p>
     * 抽成公共方法的原因：三条路径（手动领取 {@link #receiveCoupon(Long)}、
     * 兑换码兑换 {@link #exchangeCoupon(String)}、MQ 消费者）在这一段完全一致，
     * 而并发安全改造（乐观锁、事务边界）也全部落在这里，
     * 只有一份才能保证改一处即三边生效。
     * <p>
     * 之所以要放到接口上，是因为事务注解要求方法必须是被 Spring 代理的 public 方法。
     *
     * @param userCouponDTO 用户id + 优惠券id + 兑换码序列号。
     *                      手动领取时 serialNum 传 {@code null}；
     *                      兑换码兑换传序列号，用来把该兑换码标记为已使用
     */
    void checkAndCreateUserCoupon(UserCouponDTO userCouponDTO);

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

    void writeOffCoupon(List<Long> userCouponIds);

    void refundCoupon(List<Long> userCouponIds);

    /**
     * 查询优惠券的规则描述（用于订单详情页展示"这个订单用了哪些券"）
     * <p>
     * 订单表里只存了用过的【用户券id】，没有规则文案，
     * 所以查订单详情时要拿着这批 id 回来查规则。
     *
     * @param userCouponIds 用户优惠券 id 集合（可为空 —— 订单没用券时就是空）
     * @return 规则文案列表，如 ["满100减15", "每满100减10，上限50"]；查不到或入参为空时返回空集合
     */
    List<String> queryDiscountRules(List<Long> userCouponIds);
}
