package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.cache.CategoryCache;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.dto.CouponFormDTO;
import com.tianji.promotion.domain.dto.CouponIssueFormDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.CouponQuery;
import com.tianji.promotion.domain.vo.CouponDetailVO;
import com.tianji.promotion.domain.vo.CouponPageVO;
import com.tianji.promotion.domain.vo.CouponScopeVO;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.ObtainType;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.ICouponService;
import com.tianji.promotion.service.IExchangeCodeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
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
    private final IUserCouponService userCouponService;
    private final StringRedisTemplate redisTemplate;

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

        // 5.立刻发放 → 写缓存
        //    ★ 必须放在 updateById 之后：Redis 不受数据库事务回滚的保护，
        //      先写缓存后写库，一旦写库失败回滚，缓存里就会残留一张"线上并不存在"的券。
        //    ★ 必须传 DB 查出来的 coupon，不能传 dto 拷出来的 c：
        //      CouponIssueFormDTO 里只有发放时间，没有 totalNum / userLimit，
        //      用它写缓存会把这两个字段写成 null，后续库存校验直接 NPE。
        //      coupon 里的 issueBeginTime 还是旧值，从 c 上回填成刚写入库的新值。
        if (isBegin) {
            coupon.setIssueBeginTime(c.getIssueBeginTime());
            coupon.setIssueEndTime(c.getIssueEndTime());
            cacheCouponInfo(coupon);
        }

        // 6.兑换码方式的券，且原本是「待发放」→ 异步生成兑换码
        //    注意这里读的是 coupon.getStatus()，即改状态之前的原始值，
        //    这样从「暂停」恢复发放时不会重复生成一批码
        if (coupon.getObtainWay() == ObtainType.ISSUE && coupon.getStatus() == CouponStatus.DRAFT) {
            coupon.setIssueEndTime(c.getIssueEndTime());
            codeService.asyncGenerateCode(coupon);
        }
    }

    /**
     * 把优惠券的校验信息写入 Redis 缓存
     * <p>
     * 结构：{@code prs:coupon:{couponId}} 是一个 Hash，
     * field 为 issueBeginTime / issueEndTime / totalNum / userLimit，
     * value 统一存字符串。只存校验需要的这 4 个字段，不存整个券对象，省内存。
     * <p>
     * 用 Hash 而不是一整个 JSON 字符串的原因：后面要单独对字段做 HINCRBY（扣库存）。
     *
     * @param coupon 必须是数据库里的券对象（含完整字段），否则写进去的是 null
     */
    private void cacheCouponInfo(Coupon coupon) {
        // 一条 map 直接 putAll，避免 4 次网络往返
        Map<String, String> map = new HashMap<>(4);
        // 时间统一转成毫秒时间戳存：一来 Hash 的值只能是字符串，
        // 二来读取端反序列化时不用再解析时间格式
        map.put("issueBeginTime", String.valueOf(DateUtils.toEpochMilli(coupon.getIssueBeginTime())));
        map.put("issueEndTime", String.valueOf(DateUtils.toEpochMilli(coupon.getIssueEndTime())));
        map.put("totalNum", String.valueOf(coupon.getTotalNum()));
        map.put("userLimit", String.valueOf(coupon.getUserLimit()));
        // ★ key 必须用常量拼，不能加引号：写成 "COUPON_CACHE_KEY_PREFIX" 会被当成字面量，
        //   最终 key 变成 COUPON_CACHE_KEY_PREFIX123，和读取端对不上，永远读不到券
        redisTemplate.opsForHash().putAll(PromotionConstants.COUPON_CACHE_KEY_PREFIX + coupon.getId(), map);
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
        // 5.删缓存
        redisTemplate.delete(PromotionConstants.COUPON_CACHE_KEY_PREFIX + id);
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
        // 4.删缓存
        //    暂停后这张券就不可领了，缓存留着会让读缓存的那条链路仍然认为它在发放中。
        //    必须和 updateById 同步删除，否则「暂停」这个动作对领券接口是无效的。
        redisTemplate.delete(PromotionConstants.COUPON_CACHE_KEY_PREFIX + id);
    }

    /**
     * 定时开始发放：把到达发放开始时间的「未开始」券改成「发放中」，并写入缓存
     * <p>
     * ★ 为什么必须同时写缓存：券缓存不是凭空就有的，
     * 它只在「发放」这个动作里产生（立刻发放走 beginIssue，延时发放就走这里）。
     * 如果这里只改状态不写缓存，这张券会被改成发放中，
     * 但用户点领取时 queryCouponByCache 读不到任何东西 → 直接抛"优惠券不存在"。
     * 也就是"延时发放"这条业务线完全走不通。
     * <p>
     * ★ 为什么改成"先查、再改、最后逐条写缓存"：
     * 原来是"一条批量 UPDATE 搞定"，那样确实更省事，但拿不到券的明细，没法逐条写缓存。
     * 而且查询必须在 UPDATE **之前**做 —— 因为 UPDATE 会把状态改成 ISSUING，
     * 之后再按 issue_begin_time 去反查，会把"早就发放完、只是没到结束时间"的老券也捞出来，
     * 造成脏数据被重新写进缓存。
     * <p>
     * 注意状态条件必须是 UN_ISSUE（已排期、等开始），而不是 DRAFT（还没排期）。
     * 将来换 XXL-JOB 时，在这里追加分片条件：
     * {@code .apply("MOD(id, {0}) = {1}", total, index)}
     */
    @Override
    public void beginIssueBatch() {
        // 1.查出「已经排期、且到达开始时间」的券（此时状态还是 UN_ISSUE）
        List<Coupon> coupons = lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.UN_ISSUE)
                .le(Coupon::getIssueBeginTime, LocalDateTime.now())
                .list();
        if (CollUtils.isEmpty(coupons)) {
            return;
        }
        // 2.按 id 批量改状态
        //    ★ 这里特意用 in(id, ids) 而不是重写一遍原来的查询条件：
        //      保证"被改状态的行"就是"第 1 步查出来的这批"，
        //      否则两次查询之间若有新券满足条件，就会出现"改了状态却漏了缓存"的券
        List<Long> ids = coupons.stream()
                .map(Coupon::getId)
                .collect(Collectors.toList());
        lambdaUpdate()
                .set(Coupon::getStatus, CouponStatus.ISSUING)
                .in(Coupon::getId, ids)
                .update();
        // 3.逐条写缓存
        //    ★ 时间用券上排期的原值，不能像 beginIssue 那样覆盖成 now：
        //      beginIssue 是"立刻发放"，所以要把开始时间改成当前时刻；
        //      而这里是"延时发放"，开始时间是运营早就排好的，原样保留才符合语义
        for (Coupon coupon : coupons) {
            cacheCouponInfo(coupon);
        }
    }

    /**
     * 定时结束发放：把到达发放结束时间的「发放中」券改成「发放结束」
     */
    @Override
    public void endIssueBatch() {
        lambdaUpdate()
                .set(Coupon::getStatus, CouponStatus.FINISHED)
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .le(Coupon::getIssueEndTime, LocalDateTime.now())
                .update();
    }

    @Override
    public List<CouponVO> queryIssuingCoupons() {
        // 1.查询发放中且为手动领取的优惠券
        List<Coupon> coupons = lambdaQuery()
                .eq(Coupon::getStatus, CouponStatus.ISSUING)
                .eq(Coupon::getObtainWay, ObtainType.PUBLIC)
                .list();
        if (CollUtils.isEmpty(coupons)) {
            return Collections.emptyList();
        }
        // 2.查询当前用户对这些券的领取记录
        List<Long> couponIds = coupons.stream()
                .map(Coupon::getId)
                .collect(Collectors.toList());
        Long userId = UserContext.getUser();
        // userId 为空说明是未登录的游客（/coupons/list 已放行登录拦截）。
        // 这时不要去查 user_coupon，否则会拼出 user_id = null 这种恒不成立的条件，
        // 直接按"一张都没领过"处理，让所有券都显示成可领取
        List<UserCoupon> userCoupons = userId == null
                ? Collections.emptyList()
                : userCouponService.lambdaQuery()
                        .eq(UserCoupon::getUserId, userId)
                        .in(UserCoupon::getCouponId, couponIds)
                        .list();
        // 注意：不能因为 userCoupons 为空就直接返回空集合。
        // 「用户一张券都没领过」是正常状态，此时每张券都应该是可领取的，
        // 下面的 getOrDefault 会把没有记录的券当成 0 处理。
        // 直接 return 会导致新用户、游客永远看不到领券列表。

        // 统计该用户每张券已领取的总数量
        Map<Long, Long> issuedMap = userCoupons.stream()
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));
        // 统计该用户每张券已领取且未使用的数量
        Map<Long, Long> unusedMap = userCoupons.stream()
                .filter(uc -> uc.getStatus() == UserCouponStatus.UNUSED)
                .collect(Collectors.groupingBy(UserCoupon::getCouponId, Collectors.counting()));
        // 3.封装VO
        List<CouponVO> list = new ArrayList<>(coupons.size());
        for (Coupon coupon : coupons) {
            CouponVO vo = BeanUtils.copyBean(coupon, CouponVO.class);
            // 3.1.是否可以领取 = 券本身还有库存 && 当前用户没超出每人限领数量。
            //     库存看的是券上的 issueNum / totalNum，和个人领取数量无关；
            //     两个 map 都要用 getOrDefault，否则 null 拆箱会 NPE
            vo.setAvailable(
                    coupon.getIssueNum() < coupon.getTotalNum()
                            && issuedMap.getOrDefault(coupon.getId(), 0L) < coupon.getUserLimit()
            );
            // 3.2.是否已领取：有"已领且未使用"的券就算已领，前端据此显示"去使用"
            vo.setReceived(unusedMap.getOrDefault(coupon.getId(), 0L) > 0);
            list.add(vo);
        }
        return list;
    }

}
