package com.tianji.promotion.controller;

import com.tianji.api.dto.promotion.CouponDiscountDTO;
import com.tianji.api.dto.promotion.OrderCouponDTO;
import com.tianji.api.dto.promotion.OrderCourseDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.service.IDiscountService;
import com.tianji.promotion.service.IUserCouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 用户优惠券 前端控制器
 * </p>
 * <p>
 * 3 个接口 = 「用户拿到券」的两条路径 + 「查看自己的券」：
 * <ol>
 *   <li>{@code POST /{couponId}/receive} —— <b>手动领取</b>（券中心点「领取」按钮）</li>
 *   <li>{@code POST /{code}/exchange} —— <b>兑换码兑换</b>（输入一串兑换码）</li>
 *   <li>{@code GET /page} —— 分页查询「我的优惠券」</li>
 * </ol>
 * <p>
 * ★ 前两个是 day11「异步领券」优化的核心，现在它们的结构是：
 * <pre>
 *   ┌─ 同步阶段（就是本 Controller 调的那两个 service 方法）
 *   │    校验资格 → 扣 Redis 库存 → 发 MQ → 立刻返回「领取成功」
 *   └──────────── 发 MQ：一条不可回退的承诺线 ────────────
 *        异步阶段（MQ 消费者 PromotionMqHandler）
 *          扣 DB 库存 → 写 user_coupon
 * </pre>
 * 也就是说：<b>接口返回成功 ≠ 数据已经落库</b>，落库是消息队列异步完成的。
 * <p>
 * ★ 两个路径参数的含义完全不同，最容易看混：
 * <ul>
 *   <li>{@code /{couponId}/receive} 传的是<b>券 id</b>（一长串数字）</li>
 *   <li>{@code /{code}/exchange} 传的是<b>兑换码本身</b>（一长串 Base32 字符）</li>
 * </ul>
 * <p>
 * ★ 三个接口都<b>必须带登录态</b>（{@code user-info} 请求头），
 * 未登录会被 {@code LoginAuthInterceptor} 拦成 401。
 * 用户 id 一律从登录态取（{@code UserContext.getUser()}），不接受客户端传参。
 *
 * @author author
 * @since 2026-09-21
 */
@RestController
@RequestMapping("/user-coupons")
@Api(tags = "用户优惠券相关接口")
@RequiredArgsConstructor
public class UserCouponController {

    private final IUserCouponService userCouponService;
    private final IDiscountService discountService;

    /**
     * 手动领取优惠券 ★ day11 2.4 的核心接口
     * <p>
     * 调用方：用户端券中心列表里点「领取」。
     * <p>
     * 服务端只做「受理」，校验全部通过就直接返回，真正的落库交给 MQ：
     * <ol>
     *   <li>加分布式锁 {@code lock:coupon:{couponId}}
     *       —— 锁的是<b>券</b>而不是人，因为被抢的资源是这张券的库存</li>
     *   <li>读 Redis 券缓存 {@code prs:coupon:{couponId}}，读不到就报「优惠券不存在」</li>
     *   <li>校验发放起止时间（不校验 status —— status 不在缓存里，
     *       券被暂停/删除时缓存会被删掉，第 2 步就已经返回 null 了）</li>
     *   <li>校验每人限领：{@code HINCRBY prs:user:coupon:{couponId} {userId} 1}，超了要回滚</li>
     *   <li>扣减 Redis 库存：{@code HINCRBY prs:coupon:{couponId} totalNum -1}，
     *       扣成负数要回滚（此时限领计数也必须一起回滚）</li>
     *   <li>发 MQ（{@code promotion.topic / coupon.receive}），消息体只带 userId + couponId</li>
     * </ol>
     * <p>
     * ★ 为什么锁 couponId 而不是 userId：
     * 锁 userId 只能防「同一个人重复领」，防不住「多人抢同一张券」。
     * 口诀：<b>锁谁，看谁是被竞争的资源。</b>
     * <p>
     * ★ 为什么参数只有 couponId、没有 userId：
     * userId 从登录态取，客户端传的不可信（否则可以传别人的 id 替别人领券）。
     * <p>
     * ★ <b>返回成功只代表「抢到了名额」</b>，数据库里还没有你的券 ——
     * 如果 MQ 消费最终失败（重试 3 次后进 error 队列），会出现「接口报成功但券没到账」。
     */
    @ApiOperation("领取优惠券")
    @PostMapping("/{couponId}/receive")
    public void receiveCoupon(@PathVariable("couponId") Long couponId) {
        userCouponService.receiveCoupon(couponId);
    }

    /**
     * 使用兑换码兑换优惠券
     * <p>
     * 调用方：用户端「兑换码兑换」输入框。路径参数 {@code code} 是<b>兑换码本身</b>。
     * <p>
     * 流程和手动领取大体一致，但多了两步「兑换码专属」的处理：
     * <ol>
     *   <li>解析兑换码 → 拿到序列号 serialNum
     *       （{@code CodeUtil.parseCode}，<b>纯计算不查库</b>，验签不过会直接抛异常）</li>
     *   <li>用 BitMap 占位，防同一个码被并发兑换
     *       （{@code SETBIT} 会返回该位的旧值：旧值是 1 说明已经被兑换过了）</li>
     *   <li>根据序列号反查这张码属于哪张券 → {@code couponId}</li>
     *   <li>后面的时间校验、限领校验、扣库存、发 MQ 与手动领取完全一致</li>
     *   <li>发 MQ 时消息体多带一个 {@code serialNum}，
     *       消费者落库时顺手把这张码标记为「已使用」</li>
     * </ol>
     * <p>
     * ★ 第 2 步之后的<b>任何一步失败，都必须把占的位释放掉</b>（{@code SETBIT ... 0}），
     * 否则这个码会被永久锁死：用户没兑换成功，却再也兑换不了，
     * 而且数据库事务回滚后查不出任何痕迹，排查时一头雾水。
     */
    @ApiOperation("兑换码兑换优惠券")
    @PostMapping("/{code}/exchange")
    public void exchangeCoupon(@PathVariable("code") String code) {
        userCouponService.exchangeCoupon(code);
    }

    /**
     * 分页查询我的优惠券
     * <p>
     * 调用方：用户端「我的优惠券」页面。
     * <p>
     * 要查两张表：{@code user_coupon} 回答「我有哪些券、各自什么时候过期」，
     * {@code coupon} 回答「这些券长什么样（名称、折扣规则）」。
     * <p>
     * ★ 用户 id 从登录态取，不接受客户端传参 —— 否则可以传别人的 id 看别人的券。
     * <p>
     * ★ 必须以 {@code user_coupon} 的记录驱动循环：
     * 同一张券可能被同一个人领了多张（限领 &gt; 1），
     * 用 {@code coupon} 循环会丢掉重复的那些券。
     */
    @ApiOperation("分页查询我的优惠券")
    @GetMapping("/page")
    public PageDTO<CouponVO> queryMyCouponPage(UserCouponQuery query) {
        return userCouponService.queryMyCouponPage(query);
    }
    @ApiOperation("查询我的优惠券可用方案")
    @PostMapping("/available")
    public List<CouponDiscountDTO> findDiscountSolution(@RequestBody List<OrderCourseDTO> orderCourses){
        return discountService.findDiscountSolution(orderCourses);
    }
    @ApiOperation("核销指定优惠券")
    @PutMapping("/use")
    public void writeOffCoupon(@ApiParam("用户优惠券id集合") @RequestParam("couponIds") List<Long> userCouponIds){
        userCouponService.writeOffCoupon(userCouponIds);
    }
    @ApiOperation("根据券方案计算订单优惠明细")
    @PostMapping("/discount")
    public CouponDiscountDTO queryDiscountDetailByOrder(
            @RequestBody OrderCouponDTO orderCouponDTO){
        return discountService.queryDiscountDetailByOrder(orderCouponDTO);
    }
    @ApiOperation("退还指定优惠券")
    @PutMapping("/refund")
    public void refundCoupon(@ApiParam("用户优惠券id集合") @RequestParam("couponIds") List<Long> userCouponIds){
        userCouponService.refundCoupon(userCouponIds);
    }

    /**
     * 查询优惠券的规则描述（day12 3.4）
     * <p>
     * 给交易服务的「订单详情」用：order 表里只存了用过的用户券id，
     * 详情页要展示「用了哪几张券 + 规则文案」，所以拿 id 回来查规则。
     * <p>
     * 用 GET：这是纯查询，没有副作用。
     * couponIds 走 query string（?couponIds=1&amp;couponIds=2），所以用 @RequestParam。
     */
    @ApiOperation("查询优惠券的规则描述")
    @GetMapping("/rules")
    public List<String> queryDiscountRules(
            @ApiParam("用户优惠券id集合") @RequestParam("couponIds") List<Long> userCouponIds){
        return userCouponService.queryDiscountRules(userCouponIds);
    }
}
