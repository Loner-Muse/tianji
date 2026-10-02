package com.tianji.promotion.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponDetailVO;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.service.ICouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * <p>
 * 优惠券的规则信息 前端控制器
 * </p>
 * <p>
 * 本类共 8 个接口，按用途分三组，看代码时先分清就不容易乱：
 * <ol>
 *   <li><b>运营后台的增删改查</b>：新增、修改、删除、分页查询、按 id 查详情</li>
 *   <li><b>发放管理</b>：发放 {@code /{id}/issue}、暂停 {@code /{id}/pause}
 *       —— 改的是「发放状态和时间」，不是券的规则内容</li>
 *   <li><b>用户端</b>：{@code GET /list} 是券中心页面的数据源，
 *       已在 {@code bootstrap.yml} 的 {@code excludeLoginPaths} 里放行，游客也能看</li>
 * </ol>
 * <p>
 * ★ 三个最容易看混的地方：
 * <ul>
 *   <li>{@code GET /list} 和 {@code GET /page} 不是一回事：
 *       {@code /list} 是「发放中 + 手动领取」的券，给用户端券中心用，返回 {@code List}；
 *       {@code /page} 是「全部券」的分页，给运营后台用，返回 {@code PageDTO}</li>
 *   <li>{@code PUT /{id}} 和 {@code PUT /{id}/issue} 都是修改，语义完全不同：
 *       前者改「券的规则内容」（名称、折扣、有效期），后者改「发放状态和时间」</li>
 *   <li>「限定使用范围」没有独立接口 —— 它被合并进新增/修改里了，见 {@link #addCoupon}</li>
 * </ul>
 *
 * @author author
 * @since 2026-09-20
 */
@Api(tags = "优惠券管理（运营后台 + 用户端券列表）")
@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponController {
    private final ICouponService couponService;

    /**
     * 新增优惠券
     * <p>
     * 调用方：运营后台「新建优惠券」表单。
     * <p>
     * ★ 如果表单勾选了「限定使用范围」（{@code specific=true}），
     * 会顺带把选中的三级分类写进 {@code coupon_scope} 表。
     * 这也是 {@code CouponScopeController} 是空壳的原因 ——
     * 作用范围总是跟着券一起增删改，不需要独立接口。
     * <p>
     * ★ 新增出来的券状态是「待发放」(DRAFT)，此时<b>还没有 Redis 缓存</b>，
     * 用户端也看不到它，要等运营点「发放」才真正上线。
     */
    @ApiOperation("新增优惠券")
    @PostMapping
    public void addCoupon(@RequestBody @Valid CouponFormDTO couponFormDTO) {
        couponService.addCoupon(couponFormDTO);
    }

    /**
     * 分页查询优惠券（运营后台列表页）
     * <p>
     * 三个可选筛选条件：状态、折扣类型、名称模糊匹配（都不传就查全部）。
     * <p>
     * ★ 注意参数 {@code query.type} 的语义是「折扣类型」，
     * 但对应的是 DB 里的 {@code discount_type} 列 ——
     * 因为 {@code type} 是 MySQL 保留字，实体里特意改名成 {@code discountType}。
     */
    @ApiOperation("分页查询优惠券接口")
    @GetMapping("/page")
    public PageDTO<CouponPageVO> queryCouponByPage(CouponQuery query){
        return couponService.queryCouponByPage(query);
    }

    /**
     * 发放优惠券 ★ 核心接口
     * <p>
     * 调用方：运营后台点「发放」按钮。
     * <p>
     * 根据 {@code dto.issueBeginTime} 决定两种发放方式：
     * <ul>
     *   <li><b>立刻发放</b>：开始时间为空或已过 → 状态置为「发放中」，
     *       开始时间设成当前时刻，<b>同时写入 Redis 券缓存</b>（{@code cacheCouponInfo}）</li>
     *   <li><b>延时发放</b>：开始时间在未来 → 状态置为「未开始」，
     *       交给定时任务 {@code CouponTask#checkIssueStatus} 到点再改成「发放中」并写缓存</li>
     * </ul>
     * <p>
     * ★ 写缓存的时机很关键：必须放在 {@code updateById} <b>之后</b>。
     * Redis 不受数据库事务回滚的保护，先写缓存后写库的话，
     * 一旦写库失败回滚，缓存里就会残留一张「线上并不存在」的券。
     * <p>
     * ★ 兑换码类型的券（{@code obtainWay=ISSUE}）且原本是「待发放」时，
     * 会异步生成一批兑换码（{@code asyncGenerateCode}）。
     */
    @ApiOperation("发放优惠券接口")
    @PutMapping("/{id}/issue")
    public void beginIssue(@PathVariable("id") Long id,
                           @RequestBody @Valid CouponIssueFormDTO dto) {
        couponService.beginIssue(id, dto);
    }

    /**
     * 修改优惠券的规则内容
     * <p>
     * 能改的是券本身：名称、折扣方式、折扣值、门槛、有效期、限领数量、使用范围等。
     * <p>
     * ★ 只有「待发放」(DRAFT) 状态的券能改 —— 已经发出去的券改了会影响用户手里已有的券。
     * <p>
     * ★ 使用范围的更新是「先无条件删旧、需要时再写新」：
     * 不管是把「限定」改成「不限定」还是反过来，旧记录都不能留。
     */
    @ApiOperation("修改优惠卷")
    @PutMapping("/{id}")
    public void updateCoupon(@PathVariable("id") Long id,
                             @RequestBody @Valid CouponFormDTO dto) {
        couponService.updateCoupon(id, dto);
    }

    /**
     * 删除优惠券
     * <p>
     * 会删三样东西：券本身、它名下的使用范围记录、以及 Redis 里的券缓存。
     * <p>
     * ★ 只有「待发放」(DRAFT) 状态的券能删 ——
     * 已发放/已结束的券可能已经被用户领走，删了会产生「孤儿券」
     * （用户手里有券，但查不到券的规则信息）。
     */
    @ApiOperation("删除优惠卷")
    @DeleteMapping("/{id}")
    public void deleteCoupon(@PathVariable("id") Long id) {
        couponService.deleteCoupon(id);
    }

    /**
     * 按 id 查询优惠券详情（运营后台编辑页的回显）
     * <p>
     * 比列表多返回一个 {@code scopes}：这张券限定的三级分类，以及分类名称。
     * <p>
     * ★ 分类名称不在促销库里，要通过 {@code CategoryCache}（Feign）去课程服务查，
     * 所以这个接口依赖课程服务。
     */
    @ApiOperation("根据id查询优惠券接口")
    @GetMapping("/{id}")
    public CouponDetailVO queryCouponById(@PathVariable("id") Long id) {
        return couponService.queryCouponById(id);
    }

    /**
     * 暂停发放优惠券
     * <p>
     * 状态改成「暂停」(PAUSE)，并<b>删除 Redis 券缓存</b>。
     * <p>
     * ★ 删缓存这一步不能省：不删的话领券接口读缓存仍能读到这张券，
     * 「暂停」这个动作对用户端就是完全无效的。
     * <p>
     * ★ 暂停时 {@code issueBeginTime / issueEndTime} 保留原值，
     * 等再次点「发放」时可以继续沿用。
     */
    @ApiOperation("暂停发放优惠券")
    @PutMapping("/{id}/pause")
    public void pauseIssue(@PathVariable("id") Long id) {
        couponService.pauseIssue(id);
    }

    /**
     * 查询发放中的优惠券列表（★ 用户端券中心的数据源）
     * <p>
     * 只返回「发放中 + 手动领取」的券，并针对当前用户算出两个标记：
     * <ul>
     *   <li>{@code available}：现在能不能领（券还有库存 <b>且</b> 该用户没超出限领数量）</li>
     *   <li>{@code received}：是否已经领过（有「已领未使用」的券就算已领，前端显示「去使用」）</li>
     * </ul>
     * <p>
     * ★ 这个接口被放行了（见 {@code excludeLoginPaths}），游客也能访问 ——
     * 此时 {@code UserContext.getUser()} 返回 null，代码要按「一张都没领过」处理，
     * 不能拿 null 去查 user_coupon，否则会拼出 {@code user_id = null} 这种恒不成立的条件。
     * <p>
     * ★ 这里是<b>实时查库计算</b>的，不走 Redis 缓存 ——
     * 因为要展示「该用户领了几张」这种个性化数据，而缓存里只有券的公共信息。
     */
    @ApiOperation("查询发放中的优惠券列表")
    @GetMapping("/list")
    public List<CouponVO> queryIssuingCoupons(){
        return couponService.queryIssuingCoupons();
    }


}
