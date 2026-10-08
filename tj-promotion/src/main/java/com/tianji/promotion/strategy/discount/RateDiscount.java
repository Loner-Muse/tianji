package com.tianji.promotion.strategy.discount;

import com.tianji.common.utils.NumberUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.promotion.domain.po.Coupon;
import lombok.RequiredArgsConstructor;

/**
 * 折扣券 —— 例如「满 100 打 9.5 折，上限 50 元」
 * <p>
 * ★★ 最容易看错的地方：{@code discountValue} 对折扣券来说存的<b>不是金额，而是「百分比整数」</b>：
 * <pre>
 *   discountValue = 95  →  表示 9.5 折（即按 95% 收费）→  优惠 5%
 *   discountValue = 80  →  表示 8 折  （即按 80% 收费）→  优惠 20%
 * </pre>
 * 所以同一个字段在不同券类型里语义不同（对比 {@link PriceDiscount} 里它就是"减多少钱"）。
 * <p>
 * ★ 由此推出两个配套写法：
 * <ul>
 *   <li>算优惠额：用 {@code (100 - discountValue)} 拿到「优惠的百分比」</li>
 *   <li>生成文案：用 {@code scaleToStr(discountValue, 1)} ——
 *       注意第二个参数是 <b>1</b> 不是 2，这样 95 会显示成 "9.5"
 *       （其他券的金额字段才是用 2）</li>
 * </ul>
 * <p>
 * ★ 用到的字段：{@code thresholdAmount} / {@code discountValue} / {@code maxDiscountAmount}
 * <p>
 * ★ 金额单位是「分」；{@code discountValue} 是个百分比整数。
 */
@RequiredArgsConstructor
public class RateDiscount implements Discount {

    /** 规则文案模板；三个 {} 依次填「门槛」「折扣」「上限」 */
    private static final String RULE_TEMPLATE = "满{}打{}折，上限{}元";

    /**
     * 折扣券同样要满足门槛才能用
     */
    @Override
    public boolean canUse(int totalAmount, Coupon coupon) {
        return totalAmount >= coupon.getThresholdAmount();
    }

    /**
     * 按百分比算优惠额，再和上限取小
     * <p>
     * ★ 拆解 {@code totalAmount * (100 - discountValue) / 100}：
     * <pre>
     *   订单 350 元 = 35000 分，discountValue = 95（9.5 折）
     *   35000 * (100 - 95) / 100
     *   = 35000 * 5 / 100
     *   = 175000 / 100
     *   = 1750 分 = 17.5 元   ← 9.5 折省下来的钱 ✅
     * </pre>
     * ★ 全程用 int 运算：{@code / 100} 是整数除法，<b>会自动向下取整</b>（对用户有利，不会多收钱）。
     * 这也正是"金额用 int 存"的好处 —— 不会出现 17.499999 这种浮点垃圾。
     * <p>
     * ★ 最后 {@code Math.min(maxDiscountAmount, ...)} 封顶，
     * 防止大额订单按比例算出巨额优惠（例如上限 50 时，买 10000 元最多也只减 50）。
     */
    @Override
    public int calculateDiscount(int totalAmount,  Coupon coupon) {
        // 计算折扣，扩大100倍计算，向下取整，单位是分
        return Math.min(coupon.getMaxDiscountAmount(), totalAmount * (100 - coupon.getDiscountValue()) / 100);
    }

    /**
     * 例如门槛 10000、折扣 95、上限 5000 → 输出「满100打9.5折，上限50元」
     * <p>
     * ★ 注意折扣值用的是 {@code scaleToStr(..., 1)}（除以 10），95 → "9.5"
     */
    @Override
    public String getRule( Coupon coupon) {
        return StringUtils.format(
                RULE_TEMPLATE,
                NumberUtils.scaleToStr(coupon.getThresholdAmount(), 2),
                NumberUtils.scaleToStr(coupon.getDiscountValue(), 1),
                NumberUtils.scaleToStr(coupon.getMaxDiscountAmount(), 2)
        );
    }
}
