package com.tianji.api.client.promotion;

import com.tianji.api.client.promotion.fallback.PromotionClientFallback;
import com.tianji.api.dto.promotion.CouponDiscountDTO;
import com.tianji.api.dto.promotion.OrderCouponDTO;
import com.tianji.api.dto.promotion.OrderCourseDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 促销服务（promotion-service）的远程调用接口
 * <p>
 * ★ 为什么放在 {@code tj-api} 而不是 {@code tj-promotion}：
 * 调用方是交易服务（tj-trade），被调方是促销服务（tj-promotion），两者代码不共享。
 * 把接口定义放在共享模块 tj-api 里，调用方注入这个接口就能像调本地方法一样调远程服务（Feign 代理）。
 * <p>
 * ★ 路径全部是【promotion 服务内部 Controller 的相对路径】——
 * {@code @FeignClient(value = "promotion-service")} 会自动把服务名解析成地址（走 Nacos 注册中心）。
 * <p>
 * ★ 五个方法分别服务于（day12）：
 * <ul>
 *   <li>{@link #findDiscountSolution} —— 订单确认页推荐可用优惠方案</li>
 *   <li>{@link #queryDiscountDetailByOrder} —— 下单时校验券并算出优惠明细</li>
 *   <li>{@link #writeOffCoupon} —— 下单成功后核销券</li>
 *   <li>{@link #refundCoupon} —— 取消订单时退还券</li>
 *   <li>{@link #queryDiscountRules} —— 查询订单详情时展示"用了哪些券"的规则文案</li>
 * </ul>
 * <p>
 * ★ 每个方法都有对应的降级实现，见 {@link PromotionClientFallback}。
 */
@FeignClient(value = "promotion-service", fallbackFactory = PromotionClientFallback.class)
public interface PromotionClient {

    /**
     * 根据订单中的课程，推荐可用的优惠券方案
     *
     * @param orderCourses 订单中的课程列表（含课程id、三级分类id、价格）
     * @return 优惠方案列表，按优惠金额降序
     */
    @PostMapping("/user-coupons/available")
    List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> orderCourses);

    /**
     * 根据用户选定的券方案，计算订单的优惠明细
     * <p>
     * ★ 交易服务下单时必须调它：既做一次服务端校验（券是否仍然可用），
     * 又拿到 {@code discountDetail}（每件商品优惠多少），用于写入订单明细。
     *
     * @param orderCouponDTO 用户选定的用户券id集合 + 订单课程列表
     * @return 优惠金额 + 每件商品的优惠明细；没有任何可用券时返回 null
     */
    @PostMapping("/user-coupons/discount")
    CouponDiscountDTO queryDiscountDetailByOrder(@RequestBody OrderCouponDTO orderCouponDTO);

    /**
     * 核销指定的用户优惠券
     *
     * @param userCouponIds 用户优惠券 id 集合
     */
    @PutMapping("/user-coupons/use")
    void writeOffCoupon(@RequestParam("couponIds") List<Long> userCouponIds);

    /**
     * 退还指定的用户优惠券
     *
     * @param userCouponIds 用户优惠券 id 集合
     */
    @PutMapping("/user-coupons/refund")
    void refundCoupon(@RequestParam("couponIds") List<Long> userCouponIds);

    /**
     * 查询优惠券的规则描述（用于订单详情页展示"用了哪些优惠券"）
     *
     * @param userCouponIds 用户优惠券 id 集合
     * @return 规则文案列表，例如 ["满100减15", "每满100减10，上限50"]
     */
    @GetMapping("/user-coupons/rules")
    List<String> queryDiscountRules(@RequestParam("couponIds") List<Long> userCouponIds);
}
