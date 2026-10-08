package com.tianji.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.enums.UserCouponStatus;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 用户领取优惠券的记录 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-09-21
 */
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 查询某个用户「未使用」的优惠券，连带把券的规则字段一起查出来
     * <p>
     * 用于「查询可用优惠方案」：需要用户所有未使用的券，
     * 且必须带上 coupon 表的规则字段（否则算不了优惠）。
     *
     * @param userId 用户 id
     * @return 该用户所有未使用的券（已带上 coupon 的规则字段）
     */
    List<Coupon> queryMyCoupons(@Param("userId") Long userId);

    /**
     * 根据「用户券 id 集合」批量查询优惠券（<b>多表联查</b>），并按券状态过滤
     * <p>
     * 用于「根据券方案计算订单优惠明细」：前端（交易服务）传来用户选定的用户券 id 集合，
     * 服务端<b>不能直接信任</b>这批 id，必须回数据库确认它们现在仍然可用。
     * <p>
     * ★ 为什么必须手写 SQL 联查：入参是 `user_coupon.id`，但要返回 `coupon` 表的规则字段
     * （discountType / thresholdAmount / discountValue / maxDiscountAmount）——
     * MP 的通用 CRUD 只能查一张表，做不到。
     * <p>
     * ★ 三个作用一次搞定：
     * <ol>
     *   <li><b>状态过滤</b>：`AND uc.status = #{status}` —— 交给 SQL，不用在 Java 里手写 stream filter
     *       （手写容易把 UNUSED / USED 写反）</li>
     *   <li><b>消除 N+1</b>：一次 `IN (...)` 查完，不用循环逐条 selectById</li>
     *   <li><b>不会带出 null</b>：券被删时 INNER JOIN 直接查不出来，不用担心 list 里混进 null</li>
     * </ol>
     * <p>
     * ★ 返回值仍然是 {@link Coupon}：用户券 id 借 `creater` 字段带出来（SQL 里的 `uc.id AS creater`），
     * 后面算方案时用它填 `CouponDiscountDTO.ids`。
     *
     * @param userCouponIds 用户券 id 集合（不可为空，调用方需先判空）
     * @param status        要过滤的券状态，算优惠时传 {@code UserCouponStatus.UNUSED}
     * @return 命中的券列表（含 coupon 规则字段）；一条都没命中时返回空集合
     */
    List<Coupon> queryCouponByUserCouponIds(@Param("userCouponIds") List<Long> userCouponIds,
                                            @Param("status") UserCouponStatus status);
}
