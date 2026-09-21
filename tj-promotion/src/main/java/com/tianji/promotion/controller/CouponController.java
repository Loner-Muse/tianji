package com.tianji.promotion.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponDetailVO;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.service.ICouponService;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * <p>
 * 优惠券的规则信息 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-09-20
 */
@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
public class CouponController {
    private final ICouponService couponService;
    @PostMapping
    public void addCoupon(@RequestBody @Valid CouponFormDTO couponFormDTO) {
        // 新增优惠券
        couponService.addCoupon(couponFormDTO);

    }
    @ApiOperation("分页查询优惠券接口")
    @GetMapping("/page")
    public PageDTO<CouponPageVO> queryCouponByPage(CouponQuery query){
        return couponService.queryCouponByPage(query);
    }
    @ApiOperation("发放优惠券接口")
    @PutMapping("/{id}/issue")
    public void beginIssue(@PathVariable("id") Long id,
                           @RequestBody @Valid CouponIssueFormDTO dto) {
        couponService.beginIssue(id, dto);
    }
    @ApiOperation("修改优惠卷")
    @PutMapping("/{id}")
    public void updateCoupon(@PathVariable("id") Long id,
                             @RequestBody @Valid CouponFormDTO dto) {
        couponService.updateCoupon(id, dto);
    }
    @ApiOperation("删除优惠卷")
    @DeleteMapping("/{id}")
    public void deleteCoupon(@PathVariable("id") Long id) {
        couponService.deleteCoupon(id);
    }

    @ApiOperation("根据id查询优惠券接口")
    @GetMapping("/{id}")
    public CouponDetailVO queryCouponById(@PathVariable("id") Long id) {
        return couponService.queryCouponById(id);
    }

    @ApiOperation("暂停发放优惠券")
    @PutMapping("/{id}/pause")
    public void pauseIssue(@PathVariable("id") Long id) {
        couponService.pauseIssue(id);
    }
}
