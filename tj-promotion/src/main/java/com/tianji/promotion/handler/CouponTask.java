package com.tianji.promotion.handler;

import com.tianji.promotion.service.ICouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <p>
 * 优惠券相关的定时任务
 * </p>
 * 目前用 SpringTask 实现；将来部署多实例时换成 XXL-JOB，
 * 并给批量更新加上分片条件（MOD(id, total) = index）。
 *
 * @author author
 * @since 2026-09-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponTask {

    private final ICouponService couponService;

    /**
     * 每分钟检查一次优惠券的发放状态：
     * 1.把到达发放开始时间的「未开始」券改成「发放中」
     * 2.把到达发放结束时间的「发放中」券改成「发放结束」
     */
    @Scheduled(cron = "0 * * * * ?")
    public void checkIssueStatus() {
        log.info("【定时任务】开始检查优惠券发放状态");
        // 1.定时开始发放：未开始 -> 发放中
        couponService.beginIssueBatch();
        // 2.定时结束发放：发放中 -> 发放结束
        couponService.endIssueBatch();
    }
}
