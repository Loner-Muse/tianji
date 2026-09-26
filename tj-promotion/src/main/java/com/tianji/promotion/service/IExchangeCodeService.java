package com.tianji.promotion.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.promotion.domain.query.CodeQuery;
import com.tianji.promotion.domain.vo.ExchangeCodeVO;

/**
 * <p>
 * 兑换码 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-20
 */
public interface IExchangeCodeService extends IService<ExchangeCode> {

    /**
     * 异步生成优惠券的兑换码
     *
     * @param coupon 优惠券
     */
    void asyncGenerateCode(Coupon coupon);

    /**
     * 分页查询某张优惠券的兑换码
     *
     * @param query 包含优惠券id和兑换码状态的查询条件
     */
    PageDTO<ExchangeCodeVO> queryCodePage(CodeQuery query);

    /**
     * 修改兑换码的兑换标记（基于 Redis BitMap）
     * <p>
     * 底层是 {@code SETBIT key serialNum mark}。利用 SETBIT 会返回该位「旧值」的特性，
     * 一次调用同时完成「判断」和「占位」，避免"先查后写"的并发窗口。
     * <p>
     * 调用方需要这样用：先以 mark=true 尝试占位，
     * 返回 true 说明旧值就是 1（之前已被兑换过）；
     * 后续业务失败时，再用 mark=false 把占位释放掉。
     *
     * @param serialNum 兑换码序列号，作为 BitMap 的 offset
     * @param mark      要写入的值：true=已兑换，false=未兑换
     * @return 该位在本次操作「之前」的值
     */
    boolean updateExchangeMark(long serialNum, boolean mark);
}
