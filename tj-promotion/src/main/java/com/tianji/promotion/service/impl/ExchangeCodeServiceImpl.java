package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.utils.CollUtils;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.query.CodeQuery;
import com.tianji.promotion.domain.vo.ExchangeCodeVO;
import com.tianji.promotion.mapper.ExchangeCodeMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.utils.CodeUtil;
import org.springframework.data.redis.core.BoundValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.tianji.promotion.constants.PromotionConstants.COUPON_CODE_MAP_KEY;
import static com.tianji.promotion.constants.PromotionConstants.COUPON_CODE_SERIAL_KEY;
import static com.tianji.promotion.constants.PromotionConstants.COUPON_RANGE_KEY;

/**
 * <p>
 * 兑换码 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-20
 */
@Service
public class ExchangeCodeServiceImpl extends ServiceImpl<ExchangeCodeMapper, ExchangeCode> implements IExchangeCodeService {

    private final StringRedisTemplate redisTemplate;

    /**
     * 已绑定到序列号 key 的操作对象，后续取号不用再传 key
     */
    private final BoundValueOperations<String, String> serialOps;

    public ExchangeCodeServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.serialOps = redisTemplate.boundValueOps(COUPON_CODE_SERIAL_KEY);
    }

    @Async("generateExchangeCodeExecutor")
    @Transactional
    @Override
    public void asyncGenerateCode(Coupon coupon) {
        // 本次要生成的兑换码数量
        Integer totalNum = coupon.getTotalNum();
        // 1.向Redis申请一段自增序列号，返回值是这段号的最大值（不是本次数量）
        Long result = serialOps.increment(totalNum);
        if (result == null) {
            return;
        }
        int maxSerialNum = result.intValue();
        List<ExchangeCode> list = new ArrayList<>(totalNum);
        // 2.倒推起点：起点 = 最大值 - 数量 + 1
        for (int serialNum = maxSerialNum - totalNum + 1; serialNum <= maxSerialNum; serialNum++) {
            // 3.生成兑换码：序列号 + 券id（取后4位当新鲜值）
            String code = CodeUtil.generateCode(serialNum, coupon.getId());
            ExchangeCode e = new ExchangeCode();
            e.setCode(code);
            // 序列号直接作为主键，保证一码一号
            e.setId(serialNum);
            // 这张码兑换的是哪张券
            e.setExchangeTargetId(coupon.getId());
            // 过期时间与券的发放结束时间一致
            e.setExpiredTime(coupon.getIssueEndTime());
            list.add(e);
        }
        // 4.批量入库
        saveBatch(list);
        // 5.记录这张券占用的号段上界（member: 券id, score: 最大序列号），供兑换时校验
        redisTemplate.opsForZSet().add(COUPON_RANGE_KEY, coupon.getId().toString(), maxSerialNum);
    }

    /**
     * 分页查询某张优惠券的兑换码
     * <p>
     * 两个条件：兑换码状态（前端选的「已兑换 / 未兑换」）+ 券id（隐含条件）。
     * 注意 PO 里的 exchangeTargetId 就是券id。
     */
    @Override
    public PageDTO<ExchangeCodeVO> queryCodePage(CodeQuery query) {
        // 1.分页查询兑换码
        Page<ExchangeCode> page = lambdaQuery()
                .eq(ExchangeCode::getStatus, query.getStatus())
                .eq(ExchangeCode::getExchangeTargetId, query.getCouponId())
                .page(query.toMpPage());
        // 2.转VO：只返回 id 和 code 两个字段
        return PageDTO.of(page, c -> new ExchangeCodeVO(c.getId(), c.getCode()));
    }

    /**
     * 修改兑换码的兑换标记
     * <p>
     * 用序列号作为 BitMap 的 offset —— 这正是兑换码用「单调递增序列号」
     * 而不是雪花id的原因：BitMap 按 offset 分配内存，
     * 连续密集的 offset（1~N）只占 N/8 字节，
     * 而雪花id这种稀疏的巨大 offset 会直接把内存撑爆。
     */
    @Override
    public boolean updateExchangeMark(long serialNum, boolean mark) {
        // SETBIT 的返回值是这一位「被设置之前」的旧值：
        //   旧值 0 → 返回 false，说明之前没人占过，本次占位成功
        //   旧值 1 → 返回 true，说明之前已经被兑换过了
        Boolean boo = redisTemplate.opsForValue().setBit(COUPON_CODE_MAP_KEY, serialNum, mark);
        return boo != null && boo;
    }

    /**
     * 根据序列号反查它属于哪张券 —— 靠 ZSet 的「序号段上界」做范围查找，不查 DB
     * <p>
     * 配合 {@link #asyncGenerateCode} 里写进去的
     * {@code coupon:code:range}（member=券id，score=该券号段的最大序列号）使用。
     * 原理见 {@link IExchangeCodeService#exchangeTargetId(long)}。
     * <p>
     * ★ <b>注意：day11 3.2（LUA 版）之后，业务代码已不再调用本方法。</b>
     * 兑换接口的校验整体搬进了 {@code exchange_coupon.lua}，
     * 脚本里直接执行 {@code ZRANGEBYSCORE}（省掉一次网络往返）。
     * 保留在这里作为「Java 版怎么反查券」的参照实现。
     */
    @Override
    public Long exchangeTargetId(long serialNum) {
        // 1.找「score 不小于 serialNum」的第一个成员。
        //    ★ 上界为什么是 serialNum + 5000：
        //      单张券的号段跨度 = coupon.totalNum，而 CouponFormDTO 上有
        //      @Range(max = 5000, min = 1)，即最长也只有 5000。
        //      覆盖 serialNum 的那张券，其上界 ≤ 起点 + 4999 ≤ serialNum + 4999，
        //      所以 [serialNum, serialNum + 5000] 一定能括住正确答案。
        //      有了上界，范围更小、ZSet 查找更快（比用无穷大更划算）。
        //    ★ LIMIT 0 1：只要第一个，ZSet 按 score 升序返回
        Set<String> results = redisTemplate.opsForZSet().rangeByScore(
                COUPON_RANGE_KEY, serialNum, serialNum + 5000, 0L, 1L);
        if (CollUtils.isEmpty(results)) {
            // 没有券的号段覆盖这个序列号 → 说明这个码压根不存在
            return null;
        }
        // 2.取第一个（就是 score 最小的那个）转成 couponId
        //    ★ 能这么取是因为返回的 Set 底层是 LinkedHashSet（保序）：
        //      Redis 返回的数据本身按 score 升序，LinkedHashSet 又保持插入顺序。
        //      Set 没有 get(int)，所以只能用 iterator().next()。
        String next = results.iterator().next();
        return Long.parseLong(next);
    }
}
