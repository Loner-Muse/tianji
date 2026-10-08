package com.tianji.promotion.strategy.discount;

import com.tianji.common.utils.NumberUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.promotion.domain.po.Coupon;
import lombok.RequiredArgsConstructor;

/**
 * 每满减券 —— 例如「每满 100 减 10，上限 50」
 * <p>
 * ★★ 核心是 {@link #calculateDiscount} 里的那个 <b>while 循环</b>：
 * 「每满」意味着可以<b>反复减</b>，只要剩下的金额还够一个门槛就再减一次。
 * <pre>
 *   订单 350、每满 100 减 10，上限 50：
 *     第1轮：350 >= 100 → 减 10，剩 250
 *     第2轮：250 >= 100 → 减 10，剩 150
 *     第3轮：150 >= 100 → 减 10，剩  50
 *     第4轮： 50 &lt; 100 → 结束
 *     共减 30 元（不是只减 10！）
 * </pre>
 * <p>
 * ★ 最后一定要 {@code Math.min(discount, maxDiscountAmount)} <b>封顶</b>：
 * maxDiscountAmount 是"这张券最多能减多少"，防止大额订单无限累减。
 * 上例上限 50，所以即便订单 100000 元，最多也只减 50。
 * <p>
 * ★ 用到的字段：{@code thresholdAmount} / {@code discountValue} / {@code maxDiscountAmount}（三个都用）
 * <p>
 * ★ 金额单位是「分」。
 */
@RequiredArgsConstructor
public class PerPriceDiscount implements Discount {

    /** 规则文案模板；三个 {} 依次填「门槛」「优惠值」「上限」 */
    private final static String RULE_TEMPLATE = "每满{}减{}，上限{}";

    /**
     * 每满减同样要求订单金额达到一个「门槛」才能用（至少能减一次）
     */
    @Override
    public boolean canUse(int totalAmount, Coupon coupon) {
        return totalAmount >= coupon.getThresholdAmount();
    }

    /**
     * 按倍数累减，最后封顶
     * <p>
     * ★ 注意这里把入参 totalAmount 当成"剩余金额"在不断扣减 ——
     * 因为 int 是值传递，改它不会影响调用方，所以可以放心地用。
     */
    @Override
    public int calculateDiscount(int totalAmount, Coupon coupon) {
        int discount = 0;
        Integer thresholdAmount = coupon.getThresholdAmount();
        Integer discountValue = coupon.getDiscountValue();
        // 只要剩余金额还够一个门槛，就再减一次
        while (totalAmount >= thresholdAmount) {
            discount += discountValue;
            totalAmount -= thresholdAmount;
        }
        // 封顶：最多不能超过 maxDiscountAmount
        return Math.min(discount, coupon.getMaxDiscountAmount());
    }

    /**
     * 例如门槛 10000、优惠值 1000、上限 5000 → 输出「每满100减10，上限50」
     */
    @Override
    public String getRule(Coupon coupon) {
        return StringUtils.format(
                RULE_TEMPLATE,
                NumberUtils.scaleToStr(coupon.getThresholdAmount(), 2),
                NumberUtils.scaleToStr(coupon.getDiscountValue(), 2),
                NumberUtils.scaleToStr(coupon.getMaxDiscountAmount(), 2));
    }
}
