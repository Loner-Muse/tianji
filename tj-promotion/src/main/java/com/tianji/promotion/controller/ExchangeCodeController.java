package com.tianji.promotion.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.query.CodeQuery;
import com.tianji.promotion.domain.vo.ExchangeCodeVO;
import com.tianji.promotion.service.IExchangeCodeService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * <p>
 * 兑换码 前端控制器
 * </p>
 * <p>
 * 只有 1 个接口：分页查询某张券名下的兑换码（运营后台查看「已兑换 / 未兑换」列表）。
 * <p>
 * ★ 「兑换码」这个业务横跨三个类，看代码时先分清各自负责什么：
 * <table border="1">
 *   <tr><th>动作</th><th>在哪个类</th><th>怎么触发</th></tr>
 *   <tr><td>生成</td><td>{@code ExchangeCodeServiceImpl#asyncGenerateCode}</td>
 *       <td>发放兑换码类型的券时自动异步触发，<b>没有手动生成的接口</b></td></tr>
 *   <tr><td>查看</td><td><b>本类</b> {@code GET /codes/page}</td><td>运营后台点开某张券的兑换码列表</td></tr>
 *   <tr><td>兑换</td><td>{@code UserCouponController#exchangeCoupon}</td>
 *       <td>{@code POST /user-coupons/{code}/exchange}，用户在用户端输入码</td></tr>
 * </table>
 * <p>
 * ★ 接口路径是 {@code /codes} 而不是代码生成器默认的 {@code /exchange-code}，
 * 是为了和前端对齐（见 day09 的改动记录）。
 *
 * @author author
 * @since 2026-09-20
 */
@RestController
@RequestMapping("/codes")
@Api(tags = "兑换码相关接口")
@RequiredArgsConstructor
public class ExchangeCodeController {

    private final IExchangeCodeService codeService;

    /**
     * 分页查询某张券的兑换码
     * <p>
     * 两个条件：兑换码状态（前端选「已兑换 / 未兑换」）+ 券 id（隐含条件，由操作入口带过来）。
     * <p>
     * ★ 参数名有点绕：查询参数叫 {@code couponId}，
     * 但它在实体里对应的字段是 {@code exchangeTargetId}，表里叫 {@code exchange_target_id}。
     * 三个名字指的是同一件事 ——「这张码兑换的是哪张券」。
     * <p>
     * ★ 返回只带 {@code id}（= 序列号）和 {@code code} 两个字段，
     * 不暴露 {@code userId}、{@code expiredTime} 等内部信息。
     */
    @ApiOperation("分页查询兑换码")
    @GetMapping("/page")
    public PageDTO<ExchangeCodeVO> queryCodePage(@Valid CodeQuery query) {
        return codeService.queryCodePage(query);
    }
}
