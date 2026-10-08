package com.tianji.promotion.strategy.discount;

import com.tianji.promotion.enums.DiscountType;

import java.util.EnumMap;

/**
 * 优惠券规则「工厂」—— 按券的优惠类型取出对应的规则实现
 * <p>
 * 典型用法（第 2 章算优惠、第 3 章算明细都会用到）：
 * <pre>
 *   Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
 *   if (discount != null &amp;&amp; discount.canUse(amount, coupon)) {
 *       int d = discount.calculateDiscount(amount, coupon);
 *   }
 * </pre>
 * <p>
 * ★ 为什么用 {@code EnumMap} 而不是 {@code HashMap}：
 * key 是枚举时，EnumMap 内部就是一个<b>数组</b>（直接拿枚举的 ordinal 当下标），
 * 比 HashMap 的「算哈希 + 处理冲突」更快，内存也更省。
 * <p>
 * ★ 为什么用「静态工厂 + 静态初始化块」：
 * 4 个实现类都是<b>无状态</b>的（不存任何字段，纯靠入参计算），
 * 所以整个应用只需要 4 个实例，用 static 共享即可，不需要每次 new。
 * <p>
 * ★ 这就是<b>策略模式 + 工厂模式</b>的组合：
 * {@code Discount} 是策略抽象，4 个 {@code XxxDiscount} 是具体策略，本类负责选策略。
 * （面试题 4.2「你在项目里用过什么设计模式」直接答这个。）
 */
public class DiscountStrategy {

    /** 优惠类型 → 规则实现。类加载时一次性装配好，之后只读 */
    private final static EnumMap<DiscountType, Discount> strategies;

    static {
        strategies = new EnumMap<>(DiscountType.class);
        strategies.put(DiscountType.NO_THRESHOLD, new NoThresholdDiscount());
        strategies.put(DiscountType.PER_PRICE_DISCOUNT, new PerPriceDiscount());
        strategies.put(DiscountType.RATE_DISCOUNT, new RateDiscount());
        strategies.put(DiscountType.PRICE_DISCOUNT, new PriceDiscount());
    }

    /**
     * 按优惠类型取出规则实现
     *
     * @param type 优惠类型（就是券的 {@code discountType} 字段）
     * @return 对应的规则实现；type 为 null 或没有注册过时返回 <b>null</b> —— 调用方必须判空
     */
    public static Discount getDiscount(DiscountType type) {
        return strategies.get(type);
    }
}
