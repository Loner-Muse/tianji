package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.cache.CategoryCache;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponDetailVO;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponScopeVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.ICouponService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 优惠券的规则信息 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-20
 */
@Service
@RequiredArgsConstructor
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {

    private final ICouponScopeService scopeService;

    private final IExchangeCodeService codeService;

    private final CategoryCache categoryCache;

    @Override
    @Transactional
    public void addCoupon(CouponFormDTO couponFormDTO) {
        // 1.保存优惠券本身
        // 1.1.转PO
        Coupon coupon = BeanUtils.copyBean(couponFormDTO, Coupon.class);
        // 1.2.保存。save 之后自增主键会回填到 coupon 对象上，后面要用
        save(coupon);

        // 2.没有限定使用范围，到此结束
        //    用 Boolean.TRUE.equals 判断，避免 specific 为 null 时拆箱空指针
        if (!Boolean.TRUE.equals(couponFormDTO.getSpecific())) {
            return;
        }

        // 3.限定了使用范围，把选中的分类保存到 coupon_scope
        List<Long> scopes = couponFormDTO.getScopes();
        if (CollUtils.isEmpty(scopes)) {
            throw new BadRequestException("限定范围不能为空");
        }
        Long couponId = coupon.getId();
        // 3.1.每个分类转成一条 CouponScope
        List<CouponScope> list = scopes.stream()
                .map(bizId -> new CouponScope().setBizId(bizId).setCouponId(couponId))
                .collect(Collectors.toList());
        // 3.2.批量保存
        scopeService.saveBatch(list);
    }

    @Override
    public PageDTO<CouponPageVO> queryCouponByPage(CouponQuery query) {
        // 1.分页查询
        //    过滤条件都是可选的，用带 condition 的重载，为空时不参与筛选
        //    注意：query.type 的语义是"折扣类型"，对应的列是 discount_type，不是 type
        Page<Coupon> page = lambdaQuery()
                .eq(query.getStatus() != null, Coupon::getStatus, query.getStatus())
                .eq(query.getType() != null, Coupon::getDiscountType, query.getType())
                .like(StringUtils.isNotBlank(query.getName()), Coupon::getName, query.getName())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<Coupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 2.转VO返回
        List<CouponPageVO> voList = BeanUtils.copyList(records, CouponPageVO.class);
        return PageDTO.of(page, voList);
    }

    @Transactional
    @Override
    public void beginIssue(Long id, CouponIssueFormDTO dto) {
        // 1.查询优惠券
        Coupon coupon = getById(id);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在！");
        }
        // 2.校验状态：只有「待发放」和「暂停」的券能发放
        if (coupon.getStatus() != CouponStatus.DRAFT && coupon.getStatus() != CouponStatus.PAUSE) {
            throw new BizIllegalException("优惠券状态错误！");
        }
        // 3.判断是否立刻发放：发放开始时间为空，或已经早于/等于当前时间
        LocalDateTime issueBeginTime = dto.getIssueBeginTime();
        LocalDateTime now = LocalDateTime.now();
        boolean isBegin = issueBeginTime == null || !issueBeginTime.isAfter(now);

        // 4.更新优惠券
        Coupon c = BeanUtils.copyBean(dto, Coupon.class);
        // 4.1.补上id，否则 updateById 会因 WHERE id = null 而更新0行
        c.setId(id);
        // 4.2.根据是否立刻发放，决定发放状态
        if (isBegin) {
            c.setStatus(CouponStatus.ISSUING);
            c.setIssueBeginTime(now);
        } else {
            c.setStatus(CouponStatus.UN_ISSUE);
        }
        // 4.3.写库
        updateById(c);

        // 5.兑换码方式的券，且原本是「待发放」→ 异步生成兑换码
        //    注意这里读的是 coupon.getStatus()，即改状态之前的原始值，
        //    这样从「暂停」恢复发放时不会重复生成一批码
        if (coupon.getObtainWay() == ObtainType.ISSUE && coupon.getStatus() == CouponStatus.DRAFT) {
            coupon.setIssueEndTime(c.getIssueEndTime());
            codeService.asyncGenerateCode(coupon);
        }
    }

    @Transactional
    @Override
    public void updateCoupon(Long id, CouponFormDTO dto) {
        // 1.查询优惠券
        Coupon coupon = getById(id);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在！");
        }
        // 2.只有「待发放」的券能修改
        if (coupon.getStatus() != CouponStatus.DRAFT) {
            throw new BizIllegalException("只有待发放的优惠券才能修改！");
        }
        // 3.更新优惠券本身
        Coupon c = BeanUtils.copyBean(dto, Coupon.class);
        // 3.1.补上id，否则 updateById 会因 WHERE id = null 而更新0行
        c.setId(id);
        // 3.2.写库
        updateById(c);

        // 4.同步使用范围
        // 4.1.先无条件删掉旧的范围：不管改成「限定」还是「不限定」，旧数据都不能留
        scopeService.removeByCouponId(id);
        // 4.2.只有改成「限定」才写入新的范围
        if (Boolean.TRUE.equals(dto.getSpecific())) {
            List<Long> scopes = dto.getScopes();
            if (CollUtils.isEmpty(scopes)) {
                throw new BadRequestException("限定范围不能为空");
            }
            List<CouponScope> list = scopes.stream()
                    .map(bizId -> new CouponScope().setBizId(bizId).setCouponId(id))
                    .collect(Collectors.toList());
            scopeService.saveBatch(list);
        }
    }

    @Transactional
    @Override
    public void deleteCoupon(Long id) {
        // 1.校验券存在
        Coupon coupon = getById(id);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在！");
        }
        // 2.只有「待发放」的券能删：已发放/已结束的券可能已被用户领取，删了会产生孤儿券
        if (coupon.getStatus() != CouponStatus.DRAFT) {
            throw new BizIllegalException("只有待发放的优惠券才能删除！");
        }
        // 3.删券本身
        removeById(id);
        // 4.删它下面的范围记录（没有记录时影响0行，无害，无需先判断 specific）
        scopeService.removeByCouponId(id);
    }

    @Override
    public CouponDetailVO queryCouponById(Long id) {
        // 1.查询优惠券
        Coupon coupon = getById(id);
        // 2.转VO。copyBean 传null会返回null，所以 vo == null 就代表券不存在
        CouponDetailVO vo = BeanUtils.copyBean(coupon, CouponDetailVO.class);
        //    券不存在，或者没有限定使用范围，都不用再查范围表
        if (vo == null || !Boolean.TRUE.equals(coupon.getSpecific())) {
            return vo;
        }
        // 3.查询这张券限定的分类
        List<CouponScope> scopes = scopeService.lambdaQuery()
                .eq(CouponScope::getCouponId, id)
                .list();
        if (CollUtils.isEmpty(scopes)) {
            return vo;
        }
        // 4.每个分类组装一条记录：id + 分类全名
        //    分类名不在本地库里，要通过 categoryCache 去课程服务查
        List<CouponScopeVO> scopeVOS = new ArrayList<>(scopes.size());
        for (CouponScope scope : scopes) {
            Long cateId = scope.getBizId();
            String name = categoryCache.getNameByLv3Id(cateId);
            scopeVOS.add(new CouponScopeVO(cateId, name));
        }
        vo.setScopes(scopeVOS);
        return vo;
    }

    @Transactional
    @Override
    public void pauseIssue(Long id) {
        // 1.校验券存在
        Coupon coupon = getById(id);
        if (coupon == null) {
            throw new BadRequestException("优惠券不存在！");
        }
        // 2.只有「发放中」的券能暂停：未开始/已结束/已暂停都没有暂停的意义
        if (coupon.getStatus() != CouponStatus.ISSUING) {
            throw new BizIllegalException("只有发放中的优惠券才能暂停！");
        }
        // 3.改成暂停状态
        //    issueBeginTime / issueEndTime 保留原值，等「恢复发放」时继续沿用
        coupon.setStatus(CouponStatus.PAUSE);
        updateById(coupon);
    }

}
