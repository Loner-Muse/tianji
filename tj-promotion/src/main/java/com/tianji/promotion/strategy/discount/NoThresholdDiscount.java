package com.tianji.promotion.strategy.discount;

import com.tianji.common.utils.NumberUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.promotion.domain.po.Coupon;
import lombok.RequiredArgsConstructor;

/**
 * 无门槛券 —— 例如「无门槛抵 10 元」
 * <p>
 * ★ 只用到一个字段：{@code discountValue}（优惠值，单位分）。
 * {@code thresholdAmount} 恒为 0（不用它），{@code maxDiscountAmount} 也用不上。
 * <p>
 * ★★ 注意 {@link #canUse} 用的是 {@code >} 而不是 {@code >=} ——
 * 要求订单金额<b>严格大于</b>券面额。这样抵扣之后不会出现 0 元订单。
 * （另外三种券的 canUse 用的都是 {@code >=}，这里是刻意的差异 —— 读代码时留意一下。）
 * <p>
 * ★ 金额单位是「分」：{@code 1000} 表示 10 元。
 */
@RequiredArgsConstructor
public class NoThresholdDiscount implements Discount{

    /** 规则文案模板；{@code {}} 是占位符，由 StringUtils.format（继承自 hutool 的 StrUtil）填充 */
    private static final String RULE_TEMPLATE = "无门槛抵{}元";

    /**
     * 无门槛：只要订单金额大于券面额就能用
     */
    @Override
    public boolean canUse(int totalAmount, Coupon coupon) {
        return totalAmount > coupon.getDiscountValue();
    }

    /**
     * 直接抵扣券面额，<b>不随订单金额变化</b>（买多少都是减这么多）
     */
    @Override
    public int calculateDiscount(int totalAmount, Coupon coupon) {
        return coupon.getDiscountValue();
    }

    /**
     * 例如 discountValue = 1000（10 元）→ 输出「无门槛抵10元」
     * <p>
     * ★ {@code scaleToStr(x, 2)}：把「分」转成「元」并去掉多余的小数位，1000 → "10"。
     * 第二个参数 2 表示金额精确到「分」（也就是两位小数）。
     */
    @Override
    public String getRule(Coupon coupon) {
        return StringUtils.format(RULE_TEMPLATE, NumberUtils.scaleToStr(coupon.getDiscountValue(), 2));
    }
}
