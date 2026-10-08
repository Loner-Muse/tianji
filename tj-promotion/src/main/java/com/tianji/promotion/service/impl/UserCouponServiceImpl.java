package com.tianji.promotion.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.NumberUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.constants.PromotionConstants;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.ExchangeCode;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.domain.query.UserCouponQuery;
import com.tianji.promotion.domain.vo.CouponVO;
import com.tianji.promotion.enums.ExchangeCodeStatus;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.IExchangeCodeService;
import com.tianji.promotion.service.IUserCouponService;
import com.tianji.promotion.strategy.discount.DiscountStrategy;
import com.tianji.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final RabbitMqHelper rabbitMqHelper;
    private final IExchangeCodeService codeService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 领取资格校验的两个 LUA 脚本（day11 3.2），类加载时一次性读进来
     * <p>
     * ★ 为什么不每次 new：脚本内容只在应用启动时读一次盘。
     * 而且 Spring Data Redis 执行脚本走的是 <b>EVALSHA</b>（先用脚本内容算 SHA1，
     * 之后只传 SHA1 不传正文），所以脚本里写多少注释都不会增加网络开销。
     * <p>
     * ★ 泛型要和脚本的返回值对上：
     * {@code receive_coupon.lua} 返回数字（0 / 1~4）→ {@code RedisScript<Long>}；
     * {@code exchange_coupon.lua} 返回字符串（"1"~"5" 或券id）→ {@code RedisScript<String>}。
     * 配错会在执行时报类型转换异常。
     * <p>
     * ★ 必须用静态代码块加载：字段是 {@code static final}，
     * 不能靠构造器初始化（构造器跑在实例创建时，而这是类级别的共享资源）。
     */
    private static final RedisScript<Long> RECEIVE_COUPON_SCRIPT;
    private static final RedisScript<String> EXCHANGE_COUPON_SCRIPT;

    static {
        RECEIVE_COUPON_SCRIPT = RedisScript.of(new ClassPathResource("lua/receive_coupon.lua"), Long.class);
        EXCHANGE_COUPON_SCRIPT = RedisScript.of(new ClassPathResource("lua/exchange_coupon.lua"), String.class);
    }


    private final UserCouponMapper userCouponMapper;


    /**
     * 手动领取优惠券（LUA 脚本版，day11 3.2）
     * <p>
     * 和 2.4 的 Java 版相比，<b>只改造了一件事：把多次 Redis 交互换成一次脚本调用</b>。
     * <pre>
     *   Java 版：读缓存 → 校时间 → 校限领(HINCRBY) → 扣库存(HINCRBY)   = 4 次网络往返
     *            ＋ 还要 {@code @Lock} 加锁 / 解锁（各一次 Redis 交互）
     *   LUA 版：一次 EVAL 全部搞定，而且<b>不需要锁</b>
     * </pre>
     * ★ 为什么不需要锁了：脚本在 Redis 里是<b>原子执行</b>的（执行期间不会插入其他命令），
     * "判断库存/限领"与"写入"之间没有窗口 —— 而锁要防的正是这个窗口。
     * <p>
     * ★ 脚本内部做的 5 步：
     * <ol>
     *   <li>券缓存不存在 → 返回 1（券没在发放中）</li>
     *   <li>{@code totalNum <= 0} → 返回 2（库存不足）</li>
     *   <li>活动已结束 → 返回 3</li>
     *   <li>超出每人限领 → 返回 4</li>
     *   <li>全部通过 → 扣库存、限领计数 +1，返回 0</li>
     * </ol>
     * <p>
     * ★ 定位没变：本方法<b>只受理、不落库</b>，只发 MQ。
     * 真正的落库（扣 DB 库存、写 user_coupon）由消费者 {@code checkAndCreateUserCoupon} 完成。
     *
     * @param couponId 优惠券 id
     */
    @Override
    public void receiveCoupon(Long couponId) {
        // 1.执行脚本，一次网络往返完成全部资格校验
        //    KEYS[1] = 券缓存（校验字段 + 库存），KEYS[2] = 限领计数，ARGV[1] = userId
        String couponKey = PromotionConstants.COUPON_CACHE_KEY_PREFIX + couponId;
        String userKey = PromotionConstants.USER_COUPON_CACHE_KEY_PREFIX + couponId;
        Long userId = UserContext.getUser();
        Long r = redisTemplate.execute(
                RECEIVE_COUPON_SCRIPT,
                List.of(couponKey, userKey),
                userId.toString());
        // 2.脚本返回 0 才算通过；其他值是错误码，正好对应错误消息数组的下标（值 - 1）
        //    ★ NumberUtils.null2Zero 是防御：脚本异常时 execute 可能返回 null，
        //      直接拆箱会 NPE
        int result = NumberUtils.null2Zero(r).intValue();
        if (result != 0) {
            // RECEIVE_COUPON_ERROR_MSG = {活动未开始, 库存不足, 活动已经结束, 领取次数过多}
            throw new BizIllegalException(PromotionConstants.RECEIVE_COUPON_ERROR_MSG[result - 1]);
        }
        // 3.发送 MQ 消息，落库交给消费者异步完成
        //    注意：这里到此为止，不能再同步调 checkAndCreateUserCoupon，
        //    否则会和 MQ 消费者各写一张券，用户领到两张
        UserCouponDTO userCouponDTO = new UserCouponDTO();
        userCouponDTO.setUserId(userId);
        userCouponDTO.setCouponId(couponId);
        rabbitMqHelper.send(MqConstants.Exchange.PROMOTION_EXCHANGE, MqConstants.Key.COUPON_RECEIVE, userCouponDTO);
    }

    /**
     * 兑换码兑换优惠券（LUA 脚本版，day11 3.2）
     * <p>
     * ★ 兑换这条路比手动领取多两个难点，现在都在脚本里一次解决了：
     * <ol>
     *   <li><b>入参是「兑换码」而不是券id</b> —— 脚本用 ZSet 反查它属于哪张券（不查数据库）</li>
     *   <li><b>同一个码只能兑一次</b> —— 脚本用 BitMap 判重</li>
     * </ol>
     * ★ 脚本内部做的 6 步：
     * <ol>
     *   <li>{@code GETBIT} 判重 → 已兑换返回 {@code "1"}</li>
     *   <li>{@code ZRANGEBYSCORE} 反查券 → 查不到返回 {@code "2"}</li>
     *   <li>{@code EXISTS} 券缓存 → 不存在返回 {@code "3"}</li>
     *   <li>活动已结束 → 返回 {@code "4"}</li>
     *   <li>超出每人限领 → 返回 {@code "5"}</li>
     *   <li>全部通过 → {@code SETBIT} 标记已兑换，返回券id</li>
     * </ol>
     * ★ 返回值约定：{@code "1"}~{@code "5"} 是错误码（对应
     * {@code EXCHANGE_COUPON_ERROR_MSG} 的下标 0~4），其他值是券id（成功）。
     * Java 侧靠「结果 &lt; 10」区分 —— 本项目券id 是 19 位，不会撞上 1~5。
     * <p>
     * ★ 定位没变：只受理、不落库，发 MQ 交给消费者完成落库。
     */
    @Override
    public void exchangeCoupon(String code) {
        // 1.解析兑换码，拿到序列号
        //    纯计算、不查库；格式不合法或验签不通过会直接抛异常
        long serialNum = CodeUtil.parseCode(code);
        Long userId = UserContext.getUser();
        // 2.执行脚本：一次网络往返完成「判重 → 反查券 → 校时间 → 校限领 → 标记已兑换」
        //    KEYS[1] = 兑换标记 BitMap，KEYS[2] = 券号段 ZSet
        //    ARGV[1] = 序列号，ARGV[2] = 序列号 + 5000（ZSet 范围查询上界），ARGV[3] = userId
        String result = redisTemplate.execute(
                EXCHANGE_COUPON_SCRIPT,
                List.of(PromotionConstants.COUPON_CODE_MAP_KEY, PromotionConstants.COUPON_RANGE_KEY),
                String.valueOf(serialNum),
                String.valueOf(serialNum + 5000),
                userId.toString());
        if (result == null) {
            // 正常情况下脚本一定返回字符串（错误码或券id），走到这里说明脚本执行异常
            throw new BizIllegalException("兑换码兑换失败");
        }
        long couponId = Long.parseLong(result);
        if (couponId < 10) {
            // 1~5 是错误码，映射到错误消息数组的下标 0~4
            // EXCHANGE_COUPON_ERROR_MSG = {兑换码已兑换, 无效兑换码, 活动未开始, 活动已经结束, 领取次数过多}
            throw new BizIllegalException(PromotionConstants.EXCHANGE_COUPON_ERROR_MSG[(int) (couponId - 1)]);
        }
        // 3.发送 MQ 消息，落库交给消费者异步完成
        //    消息里带上 serialNum，消费者落库时会顺带把这张码标记为已使用
        //    ★ RoutingKey 必须用 COUPON_RECEIVE：PromotionMqHandler 只把
        //      "coupon.receive.queue" 绑定到这一个 key，换成别的 key 消息会因为
        //      「无队列绑定」被 topic 交换机静默丢弃 —— 表现是用户看到"兑换成功"，
        //      但券永远不到账，且没有任何错误日志
        UserCouponDTO userCouponDTO = new UserCouponDTO();
        userCouponDTO.setUserId(userId);
        userCouponDTO.setCouponId(couponId);
        userCouponDTO.setSerialNum(serialNum);
        try {
            rabbitMqHelper.send(MqConstants.Exchange.PROMOTION_EXCHANGE, MqConstants.Key.COUPON_RECEIVE, userCouponDTO);
        } catch (Exception e) {
            // ★ 必须在这里释放兑换标记，否则这个码就废了：
            //   脚本已经把 BitMap 那一位标记成"已兑换"，但消息没发出去 → 永远不会有消费者来处理
            //   → 用户没拿到券，码却再也用不了，而且不可逆。
            // ★ 释放只能放在这个位置！脚本返回错误码（&lt; 10）时绝对不能释放 ——
            //   那种情况下脚本根本没执行 SETBIT，而那一位可能是「别人占的」，
            //   你一去清就是把别人的兑换标记毁了。
            codeService.updateExchangeMark(serialNum, false);
            throw e;
        }
    }

    /**
     * 分页查询我的优惠券
     * <p>
     * 要查两张表：user_coupon 回答"我有哪些券、各自什么时候过期"，
     * coupon 回答"这些券长什么样（名称、折扣规则）"。
     */
    @Override
    public PageDTO<CouponVO> queryMyCouponPage(UserCouponQuery query) {
        // 1.分页查询当前用户的券，按领取时间倒序
        //    不加排序时 MySQL 不保证返回顺序，翻页会出现记录重复或丢失
        Page<UserCoupon> page = lambdaQuery()
                .eq(UserCoupon::getUserId, UserContext.getUser())
                .eq(query.getStatus() != null, UserCoupon::getStatus, query.getStatus())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<UserCoupon> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 2.批量查出关联的 coupon 信息
        //    先 distinct：同一个 couponId 会在 records 里出现多次（限领多张），
        //    IN 里塞重复 id 没有意义
        List<Long> couponIds = records.stream()
                .map(UserCoupon::getCouponId)
                .distinct()
                .collect(Collectors.toList());
        List<Coupon> coupons = couponMapper.selectBatchIds(couponIds);
        if (CollUtils.isEmpty(coupons)) {
            return PageDTO.empty(page);
        }
        // 3.转成 Map 供下面按 couponId 取用
        //    这里的 key 天然唯一（coupon 主键），不会出现重复 key 异常
        Map<Long, Coupon> couponMap = coupons.stream()
                .collect(Collectors.toMap(Coupon::getId, Function.identity()));
        // 4.★ 必须以 records 驱动循环：列表有几行由「用户券」决定，
        //    同一张 coupon 可能对应多行，用 coupons 循环会丢掉重复的那些券
        List<CouponVO> vos = new ArrayList<>(records.size());
        for (UserCoupon uc : records) {
            Coupon coupon = couponMap.get(uc.getCouponId());
            if (coupon == null) {
                continue;
            }
            CouponVO vo = BeanUtils.copyBean(coupon, CouponVO.class);
            // 过期时间取「用户券」上的：分次领取的券有效期各不相同，
            // 而"按天数"的券在 coupon 表里这个字段本来就是空的
            vo.setTermEndTime(uc.getTermEndTime());
            vos.add(vo);
        }
        return PageDTO.of(page, vos);
    }

    /**
     * 核销优惠券（day12 3.2）
     * <p>
     * 场景：下单成功时把用掉的券标记为"已使用"，并给 coupon 的"已使用数量"+1。
     * <p>
     * ★ 只处理「未使用 且 还在有效期内」的券：
     * 未使用的券才需要核销；已过期的不该被核销（正常流程下交易服务也不会传过期的券）。
     */
    @Override
    @Transactional
    public void writeOffCoupon(List<Long> userCouponIds) {
        // 1.按 id 批量查询
        //    ★★ 用 MP 自带的 listByIds（内部走 selectBatchIds，生成 WHERE id IN (...)），
        //       不要写成 userCouponMapper.selectList(lambdaQuery().in(...)) ——
        //       ServiceImpl.lambdaQuery() 返回的是【链式 Wrapper】(LambdaQueryChainWrapper)，
        //       它不能当普通 Wrapper 参数传给 mapper.selectList()：
        //       MP 执行时会对它调 getSqlFirst()，直接抛
        //       "can not use this method for getSqlFirst"（实测踩过）。
        List<UserCoupon> userCoupons = listByIds(userCouponIds);
        if (CollUtils.isEmpty(userCoupons)) {
            return;
        }
        // 2.挑出「要核销的券」
        //    ★ now 提到循环外：一批券用同一个时间点判断，结果不会自相矛盾
        LocalDateTime now = LocalDateTime.now();
        List<UserCoupon> toUpdate = new ArrayList<>(userCoupons.size());
        for (UserCoupon userCoupon : userCoupons) {
            // 只有「未使用」的券才需要核销
            // ★ 枚举在前：userCoupon.getStatus() 为 null 时也不会 NPE
            if (UserCouponStatus.UNUSED != userCoupon.getStatus()) {
                continue;
            }
            // 有效期判断：过期的券不核销
            if (userCoupon.getTermEndTime() == null || now.isAfter(userCoupon.getTermEndTime())) {
                continue;
            }
            userCoupon.setStatus(UserCouponStatus.USED);
            // ★ 记录使用时间：日后退券、对账、排查"这张券什么时候被用掉的"都要靠它
            //   （讲义没写这一步，但 user_coupon.used_time 字段就是为此存在的）
            userCoupon.setUsedTime(now);
            toUpdate.add(userCoupon);
        }
        if (CollUtils.isEmpty(toUpdate)) {
            return;
        }
        // 3.批量更新 user_coupon（MP 的 updateBatchById 走 JDBC 批处理，一次网络往返）
        updateBatchById(toUpdate);
        // 4.券的「已使用数量」+1（和退券时的 -1 对称）
        //    ★ 逐个调用而不是 WHERE id IN (...)：同一张 coupon 可能对应多张被核销的用户券，
        //      而 SQL 的 IN 是集合语义（IN (100,100) 只命中一行）⇒ 会少加。
        for (UserCoupon userCoupon : toUpdate) {
            couponMapper.incrUsedNum(userCoupon.getCouponId());
        }
    }

    /**
     * 退还优惠券（day12 3.3）
     * <p>
     * 场景：订单退款时，把下单用掉的券还回去。
     * <p>
     * ★★ 核心认知：<b>「退券」不等于「恢复成未使用」</b>。
     * 券是有有效期的，从「用掉」到「退款」之间可能已经过了有效期。
     * 这时如果无脑恢复成 UNUSED，就等于把一张早该作废的券【复活】了 ——
     * 用户能拿它继续下单，直接造成资损。
     * <p>
     * ★ 所以这里要分清<b>两件很容易混的事</b>：
     * <pre>
     *   ① 哪些券要退？   → 状态是 USED 的【都要退】，跟有没有过期无关
     *   ② 退成什么状态？ → 由「现在还在不在有效期内」决定：
     *                        已过期 → EXPIRED（券是退回来了，但它就是过期状态）
     *                        未过期 → UNUSED （恢复成可用的未使用）
     * </pre>
     * ★ 常见错法是把 ② 的条件塞进 ①（写成 {@code USED && 未过期}），
     * 那样【已过期的券会完全不被处理】—— 状态一直卡在 USED，used_num 也不减。
     */
    @Override
    @Transactional
    public void refundCoupon(List<Long> userCouponIds) {
        // 1.按 id 批量查询
        //    ★★ 用 MP 自带的 listByIds（内部走 selectBatchIds，生成 WHERE id IN (...)），
        //       不要写成 userCouponMapper.selectList(lambdaQuery().in(...)) ——
        //       ServiceImpl.lambdaQuery() 返回的是【链式 Wrapper】(LambdaQueryChainWrapper)，
        //       它不能当普通 Wrapper 参数传给 mapper.selectList()：
        //       MP 执行时会对它调 getSqlFirst()，直接抛
        //       "can not use this method for getSqlFirst"（实测踩过）。
        List<UserCoupon> userCoupons = listByIds(userCouponIds);
        if (CollUtils.isEmpty(userCoupons)) {
            return;
        }
        // 2.挑出「要退的券」，并定好它们各自要改成什么状态
        //    ★ now 提到循环外：一是少调几次，二是保证这一批券用【同一个时间点】判断，结果不会自相矛盾
        LocalDateTime now = LocalDateTime.now();
        List<UserCoupon> toUpdate = new ArrayList<>(userCoupons.size());
        for (UserCoupon userCoupon : userCoupons) {
            // 2.1 只有「已使用」的券才需要退
            //     未使用（本来就没用掉）、已过期（本来就不该退）都直接跳过
            //     ★ 注意用枚举在前：userCoupon.getStatus() 为 null 时也不会 NPE
            if (UserCouponStatus.USED != userCoupon.getStatus()) {
                continue;
            }
            // 2.2 ★ 退成什么状态，由「现在还在不在有效期内」决定：
            //       已经过期 → 标成 EXPIRED，不能让过期券变回可用的券
            //       还在期内 → 恢复成 UNUSED
            userCoupon.setStatus(
                    now.isAfter(userCoupon.getTermEndTime())
                            ? UserCouponStatus.EXPIRED
                            : UserCouponStatus.UNUSED
            );
            toUpdate.add(userCoupon);
        }
        if (CollUtils.isEmpty(toUpdate)) {
            return;
        }
        // 3.批量更新 user_coupon
        //    MP 的 updateBatchById 底层走 JDBC 批处理：把 N 条 UPDATE 打包，
        //    【一次网络往返】发给数据库；而 for 里逐条 updateById 是 N 次网络往返。
        //    ★ 这里仍是普通的 for 循环收集，没有用 stream 链式 —— 批量与否和 stream 无关。
        updateBatchById(toUpdate);
        // 4.清掉「使用时间」
        //    券已经退回来了（状态是 UNUSED 或 EXPIRED），就不该再留着"使用时间"，
        //    否则会出现「状态=未使用，但 used_time 有值」这种自相矛盾的数据。
        //    ★ 必须用 lambdaUpdate 显式 set null：
        //      updateById / updateBatchById 默认【不更新 null 字段】（FieldStrategy.NOT_NULL），
        //      在实体上 setUsedTime(null) 是写不进去的。
        List<Long> ids = new ArrayList<>(toUpdate.size());
        for (UserCoupon userCoupon : toUpdate) {
            ids.add(userCoupon.getId());
        }
        lambdaUpdate()
                .set(UserCoupon::getUsedTime, null)
                .in(UserCoupon::getId, ids)
                .update();
        // 5.券的「已使用数量」-1（和核销时的 +1 对称）
        //    ★ 这一步【故意保持逐个调用】，没有写成 WHERE id IN (...)：
        //      同一张 coupon 可能对应多张被退的用户券（比如用户分 3 次领了同一张券，这次一起退），
        //      而 SQL 的 IN 是【集合】语义 —— WHERE id IN (100,100,100) 只会命中一行，
        //      那样就会少减 2 次。逐个调用天然正确（每次 -1），且退券通常就几张，开销可忽略。
        for (UserCoupon userCoupon : toUpdate) {
            couponMapper.decrUsedNum(userCoupon.getCouponId());
        }
    }

    /**
     * 查询优惠券的规则描述（day12 3.4）
     * <p>
     * 场景：用户中心看订单详情时，页面要展示「这个订单用了哪几张券、规则是什么」。
     * 但 order 表里只存了用过的【用户券id】（coupon_ids 字段），没有规则文案 ——
     * 所以查订单详情时要拿着这批 id 回来查规则。
     * <p>
     * ★ 为什么状态过滤传 {@code USED} 而不是 {@code UNUSED}：
     * 要查的是「这个订单【已经用掉】的那几张券」的规则，它们的状态就是"已使用"。
     * （传 UNUSED 会查不到，因为下单核销后券已经是 USED 了）
     * <p>
     * ★ 为什么必须先判空：{@code queryCouponByUserCouponIds} 的 SQL 用 {@code IN <foreach>} ——
     * 如果传进来是空集合，{@code <foreach>} 会渲染出 {@code IN ()}，这是【SQL 语法错误】，数据库直接报错。
     * 而"订单没用券"是完全正常的场景（couponIds 为 null / 空），所以必须提前返回。
     *
     * @param userCouponIds 用户优惠券 id 集合（可为空）
     * @return 规则文案列表；查不到或入参为空时返回空集合
     */
    @Override
    public List<String> queryDiscountRules(List<Long> userCouponIds) {
        // 0.入参判空：避免 SQL 渲染成 IN () 而报语法错误（订单没用券时就是这种情况）
        if (CollUtils.isEmpty(userCouponIds)) {
            return CollUtils.emptyList();
        }
        // 1.按用户券id批量查券（多表联查 + 状态过滤为「已使用」）
        List<Coupon> coupons = userCouponMapper.queryCouponByUserCouponIds(userCouponIds, UserCouponStatus.USED);
        if (CollUtils.isEmpty(coupons)) {
            return CollUtils.emptyList();
        }
        // 2.把每张券翻译成规则文案（复用策略模式的 getRule，和推荐方案时是同一套逻辑）
        List<String> rules = new ArrayList<>(coupons.size());
        for (Coupon coupon : coupons) {
            rules.add(DiscountStrategy.getDiscount(coupon.getDiscountType()).getRule(coupon));
        }
        return rules;
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
    public void checkAndCreateUserCoupon(UserCouponDTO dto) {
        // 1.校验每人限领数量
        Coupon coupon = couponMapper.selectById(dto.getCouponId());
        if (coupon == null) {
            throw new BizIllegalException("优惠券不存在");
        }
        int count = lambdaQuery()
                .eq(UserCoupon::getUserId, dto.getUserId())
                .eq(UserCoupon::getCouponId, dto.getCouponId())
                .count();
        if (count >= coupon.getUserLimit()) {
            throw new BizIllegalException("超出领取数量");
        }
        // 2.扣减库存：数据库层的原子自增 + 库存条件
        //    影响 0 行说明库存已经不够了（并发下前面查出来的库存可能已失效）
        int r = couponMapper.incrIssueNum(coupon.getId());
        if (r == 0) {
            throw new BizIllegalException("优惠券库存不足");
        }
        // 3.新增一张用户券
        saveUserCoupon(coupon, dto.getUserId());
        // 4.如果是兑换码方式，把这张兑换码标记为已使用
        if (dto.getSerialNum() != null) {
            codeService.lambdaUpdate()
                    .set(ExchangeCode::getUserId, dto.getUserId())
                    .set(ExchangeCode::getStatus, ExchangeCodeStatus.USED)
                    .eq(ExchangeCode::getId, dto.getSerialNum())
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

    /**
     * 从 Redis 缓存中查询优惠券
     * <p>
     * 缓存里只存了校验需要的 4 个字段（发放起止时间、总量、每人限领），
     * 没有 id 和 issueNum —— 前者由入参补回，后者在 2.4 里改用 Redis 计数器代替。
     * <p>
     * ★ <b>注意：day11 3.2（LUA 版）之后，本方法已不再被领券 / 兑换两个接口调用。</b>
     * 资格校验全部搬进脚本了，脚本只需要 {@code HGET issueEndTime} 这一个字段，
     * 没必要把整个 Hash 反序列化成一个对象（少一次数据搬运，也少一堆类型转换的坑）。
     * <p>
     * 保留在这里作为「Java 版怎么读券缓存」的参照实现，它处理的两个坑仍然值得记住：
     * <ol>
     *   <li>空 map 不能省判空 —— {@code mapToBean} 对空 map 会返回字段全 null 的「空壳对象」，不是 null</li>
     *   <li>写入端把 null 存成了字符串 {@code "null"}，直接反序列化会因类型转换失败抛异常</li>
     * </ol>
     *
     * @param couponId 优惠券 id
     * @return 缓存中的券信息；缓存不存在（未发放 / 已删除 / 已过期）时返回 null
     */
    private Coupon queryCouponByCache(Long couponId) {
        // 1.拼 key：后缀是 couponId，不是 userId
        String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + couponId;
        // 2.一次性取回全部 field
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        // 3.★ 缓存不存在 → 返回 null，由调用方决定抛什么错。
        //    注意不能省这一步：mapToBean 对空 map 会返回一个"字段全 null 的空壳对象"，
        //    而不是 null，靠调用方判空是判不住的
        if (CollUtils.isEmpty(entries)) {
            return null;
        }
        // 4.★ 兜底：发放时间允许为空（只配"结束时间"或只配"开始时间"都是合法配置）。
        //    写入端把 null 存成了字符串 "null"，如果直接交给 mapToBean，
        //    它会拿 "null" 去转 LocalDateTime —— 因为传的 isIgnoreError=false，
        //    转换失败会直接抛异常，导致领券接口 500。
        //    所以先把这个占位值抹掉，字段缺失时 mapToBean 会保持默认值 null。
        //    （remove 前先判 key 是否存在，避免多一次无谓的写操作；
        //      传进来的 entries 是本地 Map，改动不会影响 Redis）
        removeNullPlaceholder(entries, "issueBeginTime");
        removeNullPlaceholder(entries, "issueEndTime");

        // 5.Map → 对象
        Coupon coupon = BeanUtils.mapToBean(entries, Coupon.class, false);
        // 6.补回 id：缓存里没有这个字段，不补后面拿到的是 null
        coupon.setId(couponId);
        return coupon;
    }

    /**
     * 移除 Map 中值为字符串 "null" 的占位项
     * <p>
     * 写入端用 {@code String.valueOf(...)} 序列化字段，遇到 null 会得到字符串 "null"。
     * 这个值对 mapToBean 是个陷阱：它会认真地把 "null" 当成待转换的数据。
     * 删掉这个键，让 mapToBean 走"字段缺失"分支，保持目标字段为默认值 null。
     */
    private void removeNullPlaceholder(Map<Object, Object> entries, String field) {
        if ("null".equals(entries.get(field))) {
            entries.remove(field);
        }
    }

}
