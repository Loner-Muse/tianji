package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 用户领取优惠券的记录 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCouponServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon> implements IUserCouponService {

    /**
     * 这里注入的是 Mapper 而不是 ICouponService：
     * 因为 CouponServiceImpl 反过来要注入 IUserCouponService，
     * 用 Service 会形成构造器循环依赖（Spring Boot 2.6+ 直接启动失败）。
     * 而且本类只需要"按 id 查优惠券"，Mapper 完全够用。
     */
    private final CouponMapper couponMapper;

    private final IExchangeCodeService codeService;

    /**
     * 手动领取优惠券
     */
    @Override
    @Transactional
    public void receiveCoupon(Long couponId) {
        // 1.查询优惠券
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在");
        }
        // 2.校验优惠券是否在发放中
        if (coupon.getStatus() != CouponStatus.ISSUING) {
            throw new BadRequestException("优惠券不在发放中");
        }
        // 3.校验库存
        //    这里只是"快速失败"，让绝大多数请求不必进入后面的流程；
        //    真正的并发保护在 incrIssueNum 的 WHERE 条件里，
        //    因为这一步查出来的 issueNum 在并发下可能已经过期了
        if (coupon.getIssueNum() >= coupon.getTotalNum()) {
            throw new BadRequestException("优惠券库存不足");
        }
        // 4.校验并生成用户券（手动领取不涉及兑换码，serialNum 传 null）
        checkAndCreateUserCoupon(coupon, UserContext.getUser(), null);
    }

    /**
     * 兑换码兑换优惠券
     * <p>
     * 与手动领取的区别只有两处：入口多了"兑换码合法性"的校验，
     * 出口多了"把这张码标记为已使用"。中间的领券逻辑完全复用。
     */
    @Override
    @Transactional
    public void exchangeCoupon(String code) {
        // 1.校验兑换码格式并解析出序列号
        //    格式不合法或验签不通过，会直接抛异常
        long serialNum = CodeUtil.parseCode(code);
        // 2.用 BitMap 尝试占位，防止同一个码被并发兑换
        //    SETBIT 返回该位的旧值：返回 true 说明之前就是 1，即已经被兑换过了
        //    注意：这里是"先占位再校验"，所以后续步骤失败时必须释放占位
        boolean alreadyExchanged = codeService.updateExchangeMark(serialNum, true);
        if (alreadyExchanged) {
            throw new BizIllegalException("兑换码已经被兑换过了");
        }
        try {
            // 3.根据序列号查询兑换码
            ExchangeCode exchangeCode = codeService.getById(serialNum);
            if (exchangeCode == null) {
                throw new BizIllegalException("兑换码不存在！");
            }
            // 4.校验兑换码是否已过期
            if (LocalDateTime.now().isAfter(exchangeCode.getExpiredTime())) {
                throw new BizIllegalException("兑换码已经过期");
            }
            // 5.查询这张码要兑换的优惠券
            Coupon coupon = couponMapper.selectById(exchangeCode.getExchangeTargetId());
            if (coupon == null) {
                throw new BizIllegalException("优惠券不存在");
            }
            // 6.校验并生成用户券，最后一步会把兑换码标记为已使用
            checkAndCreateUserCoupon(coupon, UserContext.getUser(), serialNum);
        } catch (Exception e) {
            // 上面任何一步失败，都要把第 2 步占的位释放掉。
            // 否则这个兑换码会被永久锁死：用户没兑换成功，却再也兑换不了，
            // 而且数据库事务回滚后查不出任何痕迹
            codeService.updateExchangeMark(serialNum, false);
            throw e;
        }
    }

    /**
     * 分页查询我的优惠券
     */
    @Override
    public PageDTO<CouponVO> queryMyCouponPage(UserCouponQuery query) {
        // TODO 练习 4.1
        return null;
    }

    /**
     * 校验并生成用户券：手动领取与兑换码兑换共用的核心逻辑
     * <p>
     * 注意事务：当前版本的事务在最外层的 receiveCoupon / exchangeCoupon 上，
     * 所以这里不加 @Transactional 也能被事务保护。
     * 到第 3 章调整"锁边界与事务边界"时，事务注解会被挪到本方法上，
     * 那时因为"非事务方法调用事务方法会绕过代理"，还需要用 AopContext 拿代理对象。
     */
    @Override
    public void checkAndCreateUserCoupon(Coupon coupon, Long userId, Long serialNum) {
        // 1.校验每人限领数量
        int count = lambdaQuery()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponId, coupon.getId())
                .count();
        if (count >= coupon.getUserLimit()) {
            throw new BadRequestException("超出领取数量");
        }
        // 2.扣减库存：数据库层的原子自增 + 库存条件
        //    影响 0 行说明库存已经不够了（并发下前面查出来的库存可能已失效）
        int r = couponMapper.incrIssueNum(coupon.getId());
        if (r == 0) {
            throw new BizIllegalException("优惠券库存不足");
        }
        // 3.新增一张用户券
        saveUserCoupon(coupon, userId);
        // 4.如果是兑换码方式，把这张兑换码标记为已使用
        if (serialNum != null) {
            codeService.lambdaUpdate()
                    .set(ExchangeCode::getUserId, userId)
                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                    .eq(ExchangeCode::getId, serialNum)
                    .update();
        }
    }

    /**
     * 保存一张用户券，重点是算出正确的有效期
     */
    private void saveUserCoupon(Coupon coupon, Long userId) {
        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setCouponId(coupon.getId());
        uc.setStatus(UserCouponStatus.UNUSED);
        // 有效期有两种配置方式：
        //   1.配置了指定起止时间 → 直接用券上的时间
        //   2.只配置了天数（termDays）→ 从领取时刻开始计算
        // 必须做这个兜底：user_coupon.term_end_time 是 NOT NULL，
        // 而"按天数"的券在 coupon 表里 term_begin_time / term_end_time 都是空
        LocalDateTime termBeginTime = coupon.getTermBeginTime();
        LocalDateTime termEndTime = coupon.getTermEndTime();
        if (termBeginTime == null) {
            termBeginTime = LocalDateTime.now();
            termEndTime = termBeginTime.plusDays(coupon.getTermDays());
        }
        uc.setTermBeginTime(termBeginTime);
        uc.setTermEndTime(termEndTime);
        save(uc);
    }
}
