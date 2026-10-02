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

    /**
     * 根据兑换码序列号，反查出它属于哪张优惠券（<b>不查数据库</b>）
     * <p>
     * ★ 为什么需要它：兑换接口只拿到一串兑换码，
     * 解析出 {@code serialNum} 之后还必须知道 {@code couponId} 才能读券缓存。
     * 而"兑换"这条链路已经异步化，<b>校验阶段不能碰数据库</b>，
     * 所以不能用 {@code getById(serialNum)} 去查 exchange_code 表。
     * <p>
     * ★ 反查原理：生成兑换码时，每张券向 Redis 申请的是<b>一整段连续号</b>
     * （见 {@link #asyncGenerateCode}），所以一张券占用的号码永远是
     * 「连续、不重叠」的区间。于是只需要在 ZSet
     * {@code coupon:code:range} 里存每张券的<b>号段上界</b>：
     * <pre>
     *   券A 分到   1 ~ 6000     → ZSet {券A: 6000}
     *   券B 分到 6001 ~ 12100   → ZSet {券B: 12100}
     * </pre>
     * 反查时找「score 不小于 serialNum 的第一个成员」即可：
     * <pre>
     *   serialNum = 3000 → 第一个 ≥3000 的是 6000 → 券A ✅
     *   serialNum = 6001 → 6000 不够，12100 满足   → 券B ✅
     *   serialNum = 13000 → 没有券覆盖它           → null ✅
     * </pre>
     * 正确性来自不变式：号码连续且不重叠 ⇒ 覆盖 serialNum 的那张券的上界，
     * 一定是"第一个 ≥ serialNum"的 score。
     *
     * @param serialNum 兑换码序列号（由 {@code CodeUtil.parseCode} 从兑换码中解析出来）
     * @return 对应的优惠券id；序列号不在任何券的号段内（码不存在）时返回 {@code null}
     */
    Long exchangeTargetId(long serialNum);
}
