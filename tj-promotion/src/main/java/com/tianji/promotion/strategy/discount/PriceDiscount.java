package com.tianji.promotion.strategy.discount;

import com.tianji.common.utils.NumberUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.promotion.domain.po.Coupon;
import lombok.RequiredArgsConstructor;

/**
 * 满减券 —— 例如「满 100 减 15」
 * <p>
 * ★★ 与「每满减」({@link PerPriceDiscount}) 的区别（<b>最容易记混的一点</b>）：
 * <pre>
 *   订单 350 元：
 *     满减券「满100减15」   → 只减 15 元   ← 只判断一次门槛
 *     每满减券「每满100减10」→ 减 30 元    ← 按倍数累减
 * </pre>
 * 差别就在 {@link #calculateDiscount}：这里<b>直接返回</b>，每满减那边是<b>循环累加</b>。
 * <p>
 * ★ 用到的字段：{@code thresholdAmount}（门槛）、{@code discountValue}（优惠值）。
 * <p>
 * ★ 金额单位是「分」：门槛 100 元存成 {@code 10000}。
 */
@RequiredArgsConstructor
public class PriceDiscount implements Discount{

    /** 规则文案模板；两个 {} 依次填「门槛」和「优惠值」 */
    private static final String RULE_TEMPLATE = "满{}减{}";


    /**
     * 满减：订单金额达到门槛即可用（{@code >=}，刚好等于门槛也算满足）
     */
    @Override
    public boolean canUse(int totalAmount, Coupon coupon) {
        return totalAmount >= coupon.getThresholdAmount();
    }

    /**
     * 直接减固定金额，<b>不随订单金额变化</b>
     * <p>
     * 所以这里不需要用 totalAmount —— 订单 100 和订单 10000，都是减同一个 discoutValue。
     */
    @Override
    public int calculateDiscount(int totalAmount, Coupon coupon) {
        return coupon.getDiscountValue();
    }

    /**
     * 例如 thresholdAmount = 10000、discountValue = 1500 → 输出「满100减15」
     */
    @Override
    public String getRule(Coupon coupon) {
        return StringUtils.format(
                RULE_TEMPLATE,
                NumberUtils.scaleToStr(coupon.getThresholdAmount(), 2),
                NumberUtils.scaleToStr(coupon.getDiscountValue(), 2)
        );
    }
}
