package com.tianji.promotion.controller;


import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 优惠券作用范围信息 前端控制器
 * </p>
 * <p>
 * ★ 本类是<b>空壳</b> —— 一个接口都没有，这是有意为之，不是漏写了。
 * <p>
 * {@code coupon_scope} 表记录「某张券限定了哪些三级分类」，
 * 而作用范围<b>不能独立存在</b>，它永远跟着券一起增删改：
 * <ul>
 *   <li>新增券时，若勾了「限定范围」就顺带写入（{@code CouponServiceImpl#addCoupon}）</li>
 *   <li>修改券时，先删旧的范围记录，需要时再写新的（{@code updateCoupon}）</li>
 *   <li>删除券时，一起删掉（{@code deleteCoupon}）</li>
 * </ul>
 * <p>
 * 所以不存在「单独给某张券加一个使用范围」这种操作，也就不需要接口。
 * <p>
 * ★ 这个类是怎么来的：代码生成器照着 {@code coupon_scope} 表生成了一套 CRUD 骨架，
 * 但业务上不需要独立接口，所以只留下了空类。
 * <b>留着不影响功能；想删掉也可以，不会破坏任何东西。</b>
 *
 * @author author
 * @since 2026-09-20
 */
@RestController
@RequestMapping("/coupon-scope")
public class CouponScopeController {

}
