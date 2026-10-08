package com.tianji.promotion.service.impl;

import com.tianji.api.dto.promotion.CouponDiscountDTO;
import com.tianji.api.dto.promotion.OrderCouponDTO;
import com.tianji.api.dto.promotion.OrderCourseDTO;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.promotion.domain.po.Coupon;
import com.tianji.promotion.domain.po.CouponScope;
import com.tianji.promotion.domain.po.UserCoupon;
import com.tianji.promotion.enums.CouponStatus;
import com.tianji.promotion.enums.UserCouponStatus;
import com.tianji.promotion.mapper.CouponMapper;
import com.tianji.promotion.mapper.UserCouponMapper;
import com.tianji.promotion.service.ICouponScopeService;
import com.tianji.promotion.service.IDiscountService;
import com.tianji.promotion.strategy.discount.Discount;
import com.tianji.promotion.strategy.discount.DiscountStrategy;
import com.tianji.promotion.utils.PermuteUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery;

/**
 * 优惠券方案推荐 —— 订单确认页「选哪张券最划算」（day12 第 2 章）
 * <p>
 * 整体是 6 步：
 * <ol>
 *   <li>查我的所有未使用券（<b>多表联查</b>，顺便把用户券 id 带出来）</li>
 *   <li><b>初筛</b>：用「订单总价」砍掉明显不可用的券（廉价过滤，省后续 DB 查询）</li>
 *   <li><b>细筛</b>：用「券限定范围内课程的总价」重新判断，并缓存「券 → 可用课程」</li>
 *   <li><b>全排列</b>：枚举券的所有使用顺序（顺序会影响最终优惠）+ 补单券方案</li>
 *   <li><b>并行计算</b>：每个方案算一次优惠明细</li>
 *   <li><b>选最优</b>：用两个 Map + 求交集找出最优方案</li>
 * </ol>
 * <p>
 * ★ 为什么单独建一个 Service，而不是塞进 IUserCouponService：
 * 这里是「算钱」（查券 → 筛券 → 排列 → 算明细 → 挑最优），
 * IUserCouponService 管的是「券的流转」（领取/兑换/核销/退券）。
 * 两件事没有重叠，混在一起只会让那个类继续膨胀 —— 单一职责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscountServiceImpl implements IDiscountService {

    private final UserCouponMapper userCouponMapper;
    private final ICouponScopeService couponScopeService;
    private final CouponMapper couponMapper;
    /**
     * 算优惠方案专用的线程池，在 {@code PromotionConfig#discountSolutionExecutor} 里定义
     * <p>
     * ★ 为什么不直接用 {@code CompletableFuture.supplyAsync()} 的默认线程池：
     * 默认用的是全 JVM 共享的 {@code ForkJoinPool.commonPool()}，
     * 一旦某个业务把池占满，其他所有用到默认池的地方全受影响。
     * <b>给每个业务单独配池（线程池隔离），是并发编程的基本原则。</b>
     */
    private final Executor discountSolutionExecutor;

    /**
     * 根据订单里的课程，推荐可用的优惠券方案
     *
     * @param orderCourses 订单中的课程列表（含课程id、三级分类id、价格）
     * @return 优惠方案列表，按优惠金额降序；没有任何可用方案时返回空集合
     */
    @Override
    public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses) {
        // 1.查询我的所有可用优惠券
        //    为什么必须手写 SQL 联查：查询条件在 user_coupon 表，但要返回 coupon 表的规则字段
        //    （discountType / thresholdAmount / discountValue / maxDiscountAmount），
        //    MP 的通用 CRUD 只能查一张表，做不到。
        //    ★ 用户身份从登录态取，绝不放进入参 —— 否则就能查别人的券。
        List<Coupon> coupons = userCouponMapper.queryMyCoupons(UserContext.getUser());
        // 用 CollUtils.isEmpty 而不是 coupons.isEmpty()：后者不判 null，SQL 返回 null 时会 NPE
        if (CollUtils.isEmpty(coupons)) {
            return CollUtils.emptyList();
        }

        // 2.初筛：用【订单总价】把明显不可用的券砍掉
        //    ★ 为什么要有这一步（而不是直接细筛）：
        //      细筛要为每张券查一次 coupon_scope 表，成本高；
        //      初筛是纯内存计算，几乎免费，能省掉大量无用的 DB 查询。
        //    ★ 初筛的数学依据：订单总价 ≥ 券范围内课程总价（范围内课程是订单的子集），
        //      所以"全部课程都不够门槛"⇒"范围内更不够"⇒ 初筛淘汰的一定是【注定不可用】的券，不会误杀。
        //    ★ 但反过来【初筛通过 ≠ 可用】—— 券可能只覆盖部分课程，那部分不够门槛。
        //      所以初筛之后还必须细筛，两步各有各的职责：初筛管"省"，细筛管"准"。
        int totalAmount = orderCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
        List<Coupon> availableCoupons = coupons.stream()
                .filter(coupon -> DiscountStrategy.getDiscount(coupon.getDiscountType()).canUse(totalAmount, coupon))
                .collect(Collectors.toList());
        if (CollUtils.isEmpty(availableCoupons)) {
            return CollUtils.emptyList();
        }

        // 3.细筛：用【券限定范围内课程的总价】重新判断一次，同时把「券 → 可用课程」缓存下来
        Map<Coupon, List<OrderCourseDTO>> availableCouponMap = findAvailableCoupons(availableCoupons, orderCourses);
        if (CollUtils.isEmpty(availableCouponMap)) {
            return CollUtils.emptyList();
        }

        // 4.排列组合出所有方案
        //    4.1.细筛之后才知道哪些券【真的】可用，用它覆盖初筛结果
        //        （初筛的 List 到这里已经用完，所以复用同名变量；语义从"候选券"变成"最终可用券"）
        //    4.2.为什么必须枚举【全排列】而不是只挑"最优组合"：
        //        券叠加时【顺序会影响结果】—— 前一张券减完，后一张券的门槛是按【折后价】算的，
        //        同一批券换个顺序，最终优惠可能差很多，所以只有把顺序都算一遍才能找到最优。
        //        （注：只枚举"用全部 n 张"的排列就够了，不需要枚举"用 k 张"的子集 ——
        //          因为加一张券只会让优惠变多或不变，任何子集方案的优惠都能被某个全排列追平或超越。）
        availableCoupons = new ArrayList<>(availableCouponMap.keySet());
        // ★ new ArrayList<>(...)：permute 内部会 Collections.swap 修改入参，
        //   所以必须复制成一个可变的副本（直接传 keySet() 会抛 UnsupportedOperationException）
        List<List<Coupon>> solutions = PermuteUtil.permute(availableCoupons);
        //    4.3.permute 只生成"用全部 n 张券"的排列，不生成"只用 1 张"的，
        //        所以这里手动补上单券方案。
        //        ★ 为什么单券方案不能省：最优解的判定标准之一是"优惠金额相同时用券最少"，
        //          如果方案集里只有"用 3 张"，就没办法体现"用 1 张也能拿到同样的优惠"。
        for (Coupon coupon : availableCoupons) {
            solutions.add(List.of(coupon));
        }

        // 5.并行计算每个方案的优惠明细
        //    5.1.结果集合必须线程安全 —— 下面多个工作线程会【同时】往它里面 add。
        //        普通 ArrayList 并发 add 会丢数据、甚至数组越界。
        //        （顺带预设容量，避免中途扩容。注意 synchronizedList 只保证"单个方法"原子，
        //          遍历时仍需自己加锁 —— 所以下面必须确保 await 成功后再遍历。）
        List<CouponDiscountDTO> result = Collections.synchronizedList(new ArrayList<>(solutions.size()));
        //    5.2.闭锁：初值 = 方案数，每算完一个方案减 1；减到 0 时主线程被唤醒。
        //        它的作用是把"并行的任务"重新汇聚回主线程的时间线 ——
        //        否则 for 循环提交完就直接往下走，此时 result 还是空的。
        CountDownLatch latch = new CountDownLatch(solutions.size());
        for (List<Coupon> solution : solutions) {
            CompletableFuture
                    // supplyAsync：把"算一个方案"这个任务丢进线程池（有返回值的异步任务）
                    // （显式传 discountSolutionExecutor，避免占用全 JVM 共享的默认线程池）
                    .supplyAsync(() -> calculateSolutionDiscount(availableCouponMap, orderCourses, solution),
                            discountSolutionExecutor)
                    // ★ whenComplete 而不是 thenAccept —— 这是关键：
                    //   thenAccept 只在【上一步成功】时执行；如果任务抛异常，它根本不会跑，
                    //   于是 countDown() 被漏掉，latch 永远归不了零，await 必然等到超时。
                    //   whenComplete 无论成功失败都会执行，在这里做"收结果 + 计数"才安全。
                    .whenComplete((dto, ex) -> {
                        if (ex != null) {
                            log.error("优惠方案计算失败", ex);
                        } else {
                            result.add(dto);
                        }
                        latch.countDown();   // ★ 无论如何都要减 1
                    });
        }
        //    5.3.等待所有方案计算完成
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                // 超时：说明有方案没算完，结果是残缺的（虽然 whenComplete 已经保证了不会漏计数）
                log.warn("优惠方案计算超时，已完成 {}/{}", solutions.size() - latch.getCount(), solutions.size());
            }
        } catch (InterruptedException e) {
            // ★ 恢复中断标志：吞掉 InterruptedException 会清除线程的中断状态，上层就无法感知"该停了"
            Thread.currentThread().interrupt();
            log.error("优惠方案计算被中断", e);
        }

        // 6.从所有方案里筛出最优解
        return findBestSolution(result);
    }

    @Override
    public CouponDiscountDTO queryDiscountDetailByOrder(OrderCouponDTO orderCouponDTO) {
        List<Long> userCouponIds = orderCouponDTO.getUserCouponIds();
        List<OrderCourseDTO> courseList = orderCouponDTO.getCourseList();
        if (CollUtils.isEmpty(courseList)){
            return null;
        }
        if (CollUtils.isEmpty(userCouponIds)){
            return null;
        }
        // 1.★ 一次多表联查：按 id 批量查券 + 状态过滤
        //    为什么交给 SQL 而不是在 Java 里手写 stream filter：
        //      ① 状态条件写进 SQL，就不会出现 UNUSED / USED 写反这类错误
        //      ② 一次 IN 查询替代循环里的 selectById，没有 N+1
        //      ③ 券被删时 INNER JOIN 直接查不出来，list 里不会混进 null
        //    返回的每条 Coupon 里，creater 装的是「用户券id」（SQL 里的 uc.id AS creater）
        List<Coupon> coupons = userCouponMapper.queryCouponByUserCouponIds(
                userCouponIds, UserCouponStatus.UNUSED);
        if (CollUtils.isEmpty(coupons)) {
            return null;
        }
        // 2.细筛：算出每张券在【这张订单】上真正可用的课程（和查询方案用的是同一个方法）
        Map<Coupon, List<OrderCourseDTO>> availableCoupons = findAvailableCoupons(coupons, courseList);
        if (CollUtils.isEmpty(availableCoupons)) {
            return null;
        }
        // 3.算优惠明细，返回带 discountDetail 的 DTO
        return calculateSolutionDiscount(availableCoupons, courseList, coupons);
    }

    /**
     * 筛选最优方案
     * <p>
     * 最优标准有两条：
     * <ol>
     *   <li><b>用券相同时</b>，优惠金额最高</li>
     *   <li><b>优惠金额相同时</b>，用券最少</li>
     * </ol>
     * ★ 为什么用两个 Map 而不是一个变量记录最优：
     * 因为券组合有多种，<b>最优解不止一个</b>（比如 [券1,券2] 和 [券3] 优惠一样多）。
     * <ul>
     *   <li>{@code moreDiscountMap}：key = 券组合，value = 该组合下优惠最高的方案 → 保证第 1 条</li>
     *   <li>{@code lessCouponMap}：key = 优惠金额，value = 该金额下用券最少的方案 → 保证第 2 条</li>
     * </ul>
     * 两个 Map 的 values <b>求交集</b>，就是同时满足两条的方案。
     *
     * @param result 所有已算完的方案
     * @return 最优方案列表，按优惠金额降序
     */
    private List<CouponDiscountDTO> findBestSolution(List<CouponDiscountDTO> result) {
        // 1.准备两个 Map 记录最优解
        Map<String, CouponDiscountDTO> moreDiscountMap = new HashMap<>();
        Map<Integer, CouponDiscountDTO> lessCouponMap = new HashMap<>();
        // 2.遍历，筛选最优解
        for (CouponDiscountDTO solution : result) {
            // 2.1.计算当前方案的"券组合"标识
            //     ★ 关键：先把券id【排序】再拼成字符串。
            //       这样 [9001,9002,9003] 和 [9002,9001,9003] 会得到同一个 key "9001,9002,9003"，
            //       正好实现"用券相同（不关心顺序）"这个语义。
            String ids = solution.getIds().stream()
                    .sorted(Long::compare).map(String::valueOf).collect(Collectors.joining(","));
            // 2.2.用券相同时，优惠金额是否最大
            CouponDiscountDTO best = moreDiscountMap.get(ids);
            if (best != null && best.getDiscountAmount() >= solution.getDiscountAmount()) {
                // 当前方案用同一批券但优惠更少（或一样），跳过
                continue;
            }
            // 2.3.优惠金额相同时，用券数量是否最少
            best = lessCouponMap.get(solution.getDiscountAmount());
            int size = solution.getIds().size();
            if (size > 1 && best != null && best.getIds().size() <= size) {
                // 当前方案优惠金额和别人一样，但用券更多（或一样），放弃
                // 注意 size > 1 的条件：单券方案永远放行，保证"用券最少"的候选一定在
                continue;
            }
            // 2.4.更新两个 Map
            moreDiscountMap.put(ids, solution);
            lessCouponMap.put(solution.getDiscountAmount(), solution);
        }
        // 3.求交集：同时满足"优惠最高"和"用券最少"的方案
        Collection<CouponDiscountDTO> bestSolutions = CollUtils
                .intersection(moreDiscountMap.values(), lessCouponMap.values());
        // 4.按优惠金额降序返回，让前端第一个显示最划算的
        return bestSolutions.stream()
                .sorted(Comparator.comparingInt(CouponDiscountDTO::getDiscountAmount).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 计算【一个方案】的优惠明细
     * <p>
     * 一个"方案" = 券的一个<b>排列</b>（比如 [券1, 券2, 券3]），顺序就是它的全部意义。
     * <p>
     * ★★ 核心机制：按顺序逐张券算，每张券都遵循"测量 → 决策 → 行动"：
     * <pre>
     *   ① 测量：算出这张券可用课程【现在的折后总价】= Σ(原价 − 已优惠)
     *   ② 决策：canUse(折后总价, 券) —— 够不够门槛
     *   ③ 行动：算优惠额 + 把优惠额【分摊到每件商品】并累加进账本
     * </pre>
     * ★ 第 ① 步用「折后价」是整个算法的灵魂：
     * 因为前面几张券已经减过价，后一张券的门槛必须按当前实际价格算。
     * 这也正是"券的叠加【顺序会影响最终结果】"的根源。
     *
     * @param availableCouponMap 细筛结果：券 → 它可用的课程
     * @param orderCourses       订单全部课程（用来初始化账本，要覆盖所有课程）
     * @param solution           一个方案（券的一种顺序）
     * @return 这个方案的账单：用了哪几张用户券、各自什么规则、一共省多少
     */
    private CouponDiscountDTO calculateSolutionDiscount(Map<Coupon, List<OrderCourseDTO>> availableCouponMap,
                                                        List<OrderCourseDTO> orderCourses,
                                                        List<Coupon> solution) {
        // 1.初始化 DTO（它的三个字段都有默认值，所以不用手动初始化）
        CouponDiscountDTO dto = new CouponDiscountDTO();
        // 2.初始化账本：课程id → 这门课已被优惠了多少，初值全 0
        //    ★ 必须用【全部课程】初始化，因为不同券覆盖不同课程，账本要能查到任意一门课。
        //    ★ 初值 0 是"减法的单位元"：于是"原价 - 0 = 原价"自然成立，
        //      所以第一张券不需要任何特判，同一套公式就能覆盖所有轮次。
        Map<Long, Integer> detailMap = orderCourses.stream()
                .collect(Collectors.toMap(OrderCourseDTO::getId, oc -> 0));
        // 3.按方案顺序逐张券计算
        //    ★ detailMap 建在循环【外面】，所有券共享同一本账 —— 这是"叠加"能成立的基础
        for (Coupon coupon : solution) {
            // 3.1.取这张券能作用的课程（细筛时已缓存，不用再查 DB）
            List<OrderCourseDTO> courses = availableCouponMap.get(coupon);
            // 3.2.测量：算这些课程的【折后总价】= Σ(原价 − 已优惠)
            int num = courses.stream()
                    .mapToInt(oc -> oc.getPrice() - detailMap.get(oc.getId())).sum();
            // 3.3.决策：拿这张券的规则实现，判断够不够门槛
            //     （getDiscount 提取成变量，下面还要用两次，不用重复查表）
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (!discount.canUse(num, coupon)) {
                // 券不可用（门槛没达到）—— 直接跳过
                // ★ 注意 continue 只跳过【这一张券】：不登记、不加金额，dto 里已有的内容不受影响
                continue;
            }
            // 3.4.行动：算出这张券能减多少
            int discountAmount = discount.calculateDiscount(num, coupon);
            // 3.5.行动：把优惠额分摊到它作用的每件商品上，更新账本
            //      （这一步既为下一张券准备"折后价"，也为将来退款准备"每件商品的实付金额"）
            calculateDiscountDetails(detailMap, courses, num, discountAmount);
            // 3.6.登记：把这张生效的券记进账单
            //      ★ 加的是 coupon.getCreater()，但那不是"创建人"——
            //        是 SQL 里 uc.id AS creater 借用这个字段带出来的【用户券id】
            dto.getIds().add(coupon.getCreater());
            dto.getRules().add(discount.getRule(coupon));
            dto.setDiscountAmount(dto.getDiscountAmount() + discountAmount);
            dto.setDiscountDetail(detailMap);
        }
        return dto;
    }

    /**
     * 把一张券的优惠总额，按价格占比<b>分摊</b>到它作用的每件商品上，并累加进账本
     * <p>
     * ★ 为什么要落到"每件商品"而不是只记总额：
     * ① 下一张券要用"每件商品的折后价"去算门槛；
     * ② 将来用户【部分退款】时，要按"每件商品的实付金额"退钱（面试题 4.5）。
     * <p>
     * ★★ 最后一个商品必须特殊处理：直接取"剩余的优惠额"，不做除法。
     * 因为 Java 的整数除法会<b>向下取整</b>，如果每件都按比例算，累加起来会少于总优惠额
     * （比如总优惠 10 分给三件商品，可能变成 3+3+3=9，少了 1）。
     * 让最后一件"吃掉余数"，就能保证 <b>Σ各商品明细 == 优惠总额</b> —— 账必须平，退款才算得对。
     *
     * @param detailMap      账本（课程id → 已优惠金额），本方法会<b>就地修改</b>它
     * @param courses        这张券作用的课程（注意：不是订单全部课程）
     * @param num            这些课程的折后总价
     * @param discountAmount 这张券的优惠总额
     */
    private void calculateDiscountDetails(Map<Long, Integer> detailMap, List<OrderCourseDTO> courses,
                                          int num, int discountAmount) {
        int count = 0;
        int remainnum = discountAmount;   // 还剩下多少优惠没分摊，一开始是全部
        for (OrderCourseDTO course : courses) {
            count++;
            int num2;
            if (count == courses.size()) {
                // ★ 最后一件：不做除法，直接吃掉剩余的
                num2 = remainnum;
            } else {
                // 按价格占比分摊
                num2 = discountAmount * course.getPrice() / num;
                // ★ 是"减去已分摊的"，不是"重新算一遍"：
                //   必须用 remainnum -= num2，否则每次都覆盖成 discountAmount - num2，
                //   前面几件的分摊就白算了，最后一件会拿到错误的余额。
                remainnum -= num2;
            }
            // ★ 累加而不是覆盖：多张券可能作用在同一件商品上（比如券1和券3都作用于商品1）
            detailMap.put(course.getId(), num2 + detailMap.get(course.getId()));
        }
    }

    /**
     * 细筛：找出每张券【真正可用】的课程，并返回「券 → 可用课程」的映射
     * <p>
     * 它同时干两件事（这是返回值设计成 Map 而不是 List 的原因）：
     * <ol>
     *   <li><b>筛选</b>：用"券限定范围内课程的总价"重新判断门槛</li>
     *   <li><b>建索引</b>：把「券 → 可用课程」缓存下来，
     *       供后面 {@code calculateSolutionDiscount} 算优惠时直接取用，**避免重复查 coupon_scope**</li>
     * </ol>
     *
     * @param availableCoupons 初筛后的候选券
     * @param orderCourses     订单全部课程
     * @return 券 → 它可用的课程（只包含真正通过门槛的券）
     */
    private Map<Coupon, List<OrderCourseDTO>> findAvailableCoupons(List<Coupon> availableCoupons,
                                                                   List<OrderCourseDTO> orderCourses) {
        Map<Coupon, List<OrderCourseDTO>> map = new HashMap<>(availableCoupons.size());
        for (Coupon coupon : availableCoupons) {
            // 1.先默认"全部课程"—— 不限范围的券（specific = false）可以用在订单里所有课程上
            List<OrderCourseDTO> availableCourses = orderCourses;
            // 2.只有【限定了范围】的券才需要去查 coupon_scope、筛课程
            //    ★ specific 是 Boolean 包装类，用 Boolean.TRUE.equals 判等：
            //      写成 if (coupon.getSpecific()) 会在它为 null 时【自动拆箱 → NPE】
            if (Boolean.TRUE.equals(coupon.getSpecific())) {
                List<CouponScope> couponScopes = couponScopeService.lambdaQuery()
                        .eq(CouponScope::getCouponId, coupon.getId())
                        .list();
                // 用 Set 而不是 List：下面 filter 里要反复 contains，
                // Set 是 O(1)，List 是 O(n) —— 配合外层课程循环，差距会放大成 O(n×m)
                Set<Long> scopeIds = couponScopes.stream()
                        .map(CouponScope::getBizId)
                        .collect(Collectors.toSet());
                availableCourses = orderCourses.stream()
                        .filter(orderCourse -> scopeIds.contains(orderCourse.getCateId()))
                        .collect(Collectors.toList());
            }
            // 3.★ 判断和入 map 要放在 if 【外面】—— 对所有券都要执行。
            //    如果关在 if 里面，不限范围的券就永远进不了 map，会被整批丢掉。
            if (CollUtils.isEmpty(availableCourses)) {
                // 没有任何可用课程 → 这张券不可用，抛弃
                continue;
            }
            // 4.用【范围内课程的总价】判断门槛 —— 这就是细筛和初筛的本质区别
            int totalNum = availableCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
            if (DiscountStrategy.getDiscount(coupon.getDiscountType()).canUse(totalNum, coupon)) {
                map.put(coupon, availableCourses);
            }
        }
        return map;
    }
}
