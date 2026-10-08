package com.tianji.promotion.strategy.discount;

import com.tianji.promotion.domain.po.Coupon;

/**
 * 优惠券折扣规则接口（策略模式里的「策略抽象」）
 * <p>
 * ★ 为什么需要这个接口：
 * 订单确认页要「推荐最优优惠券」，前提是能算出每张券能省多少钱。
 * 于是任何一张券都必须具备 3 个能力，正好对应下面 3 个方法：
 * <ol>
 *   <li>{@link #canUse} —— 这张券在<b>本订单</b>上能不能用（订单金额够不够门槛）</li>
 *   <li>{@link #calculateDiscount} —— 用它能省多少钱（<b>能算才能比较</b>，才能挑出最优方案）</li>
 *   <li>{@link #getRule} —— 它的规则文案（页面上展示给用户选）</li>
 * </ol>
 * <p>
 * ★ 为什么用「接口 + 多个实现」而不是一堆 if-else：
 * 券有成千上万张，但<b>规则类型只有 4 种</b>（见 {@code DiscountType}），
 * 不同券只是 {@code thresholdAmount / discountValue / maxDiscountAmount} 三个字段的取值不同。
 * 所以只需要 4 个实现类，运行时按 {@code coupon.discountType} 选实现 —— 这就是策略模式。
 * <p>
 * ★★ 全项目统一的约定：<b>金额单位都是「分」，用 {@code int} 表示</b>。
 * 例如 100 元存成 10000。用 int 而不是 double，是为了避免浮点误差（0.1 + 0.2 != 0.3）。
 */
public interface Discount {

    /**
     * 判断当前订单金额是否满足这张券的使用门槛
     *
     * @param totalAmount 订单总价（单位：分）
     * @param coupon      优惠券信息
     * @return true 表示这张券可以用在本订单上
     */
    boolean canUse(int totalAmount, Coupon coupon);

    /**
     * 计算这张券能优惠多少钱
     * <p>
     * 只在 {@link #canUse} 返回 true 时调用才有意义。
     *
     * @param totalAmount 订单总价（单位：分）
     * @param coupon      优惠券信息
     * @return 优惠金额（单位：分）
     */
    int calculateDiscount(int totalAmount, Coupon coupon);

    /**
     * 生成规则描述文案，例如「满100减15」「满100打9.5折，上限50元」
     *
     * @param coupon 优惠券信息
     * @return 展示给用户的规则文案
     */
    String getRule(Coupon coupon);
}
