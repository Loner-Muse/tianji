package com.tianji.trade.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.promotion.PromotionClient;
import com.tianji.api.constants.CourseStatus;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.promotion.CouponDiscountDTO;
import com.tianji.api.dto.promotion.OrderCouponDTO;
import com.tianji.api.dto.promotion.OrderCourseDTO;
import com.tianji.api.dto.trade.OrderBasicDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.pay.sdk.dto.PayResultDTO;
import com.tianji.trade.config.TradeProperties;
import com.tianji.trade.constants.OrderStatus;
import com.tianji.trade.constants.RefundStatus;
import com.tianji.trade.constants.TradeErrorInfo;
import com.tianji.trade.domain.dto.PlaceOrderDTO;
import com.tianji.trade.domain.po.Order;
import com.tianji.trade.domain.po.OrderDetail;
import com.tianji.trade.domain.query.OrderPageQuery;
import com.tianji.trade.domain.vo.*;
import com.tianji.trade.mapper.OrderMapper;
import com.tianji.trade.service.ICartService;
import com.tianji.trade.service.IOrderDetailService;
import com.tianji.trade.service.IOrderService;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.tianji.common.constants.ErrorInfo.Msg.OPERATE_FAILED;
import static com.tianji.trade.constants.TradeErrorInfo.ORDER_ALREADY_FINISH;
import static com.tianji.trade.constants.TradeErrorInfo.ORDER_NOT_EXISTS;

/**
 * <p>
 * 订单 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-08-29
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final CourseClient courseClient;
    /**
     * 促销服务的远程调用客户端（Feign）
     * <p>
     * ★ 为什么交易服务要依赖促销服务：下单时「算优惠」和「核销券」都归促销服务管，
     * 本服务通过 Feign 跨服务调用；促销服务不可用时走 PromotionClientFallback 降级。
     */
    private final PromotionClient promotionClient;
    private final IOrderDetailService detailService;
    private final ICartService cartService;
    private final TradeProperties tradeProperties;
    private final RabbitMqHelper rabbitMqHelper;

    /**
     * 下单
     * <p>
     * ★★ 为什么这里需要 Seata 的 {@code @GlobalTransactional}（day12 3.2.5）：
     * 这个方法里有两次<b>跨服务</b>的数据修改：
     * <pre>
     *   ① 本地：写 order / order_detail   （tj_trade 库）
     *   ② 远程：promotionClient.writeOffCoupon(...)  →  改 user_coupon / coupon（tj_promotion 库）
     * </pre>
     * 普通的 {@code @Transactional} 只能管住 ①；如果 ① 成功、② 失败，
     * 就会出现「订单创建了、券却没核销」——用户能拿同一张券反复下单，<b>直接资损</b>。
     * <p>
     * {@code @GlobalTransactional} 会把这两步包成一个<b>全局事务</b>：
     * 由 Seata 的 TC（独立部署的 seata-server）协调，任一步失败则整体回滚。
     * <p>
     * ★ 本方法是 TM（事务发起方）；写 tj_promotion 库的那一步由促销服务作为 RM 参与，
     * 它靠 Seata 的 DataSourceProxy 自动记录 undo_log 来实现回滚。
     * <p>
     * ★ 注意：这里两个注解得同时保留 ——
     * {@code @Transactional} 管本地库的事务，{@code @GlobalTransactional} 管跨服务的全局事务。
     */
    @Override
    @GlobalTransactional
    @Transactional
    public PlaceOrderResultVO placeOrder(PlaceOrderDTO placeOrderDTO) {
        Long userId = UserContext.getUser();
        // 1.查询课程费用信息，如果不可购买，这里直接报错
        List<CourseSimpleInfoDTO> courseInfos = getOnShelfCourse(placeOrderDTO.getCourseIds());
        // 2.封装订单信息
        Order order = new Order();
        // 2.1.计算订单金额
        Integer totalAmount = courseInfos.stream()
                .map(CourseSimpleInfoDTO::getPrice).reduce(Integer::sum).orElse(0);
        // 2.2.计算优惠金额（远程调用促销服务）
        //     ★ 为什么必须由服务端算、不能信前端传的金额：
        //       前端参数可篡改；而且下单这一刻要【重新校验】券是否仍然可用（状态 / 有效期 / 门槛）。
        //     ★ 促销服务不可用时走降级：queryDiscountDetailByOrder 返回 null ⇒ 按原价下单，
        //       用户能正常买到东西，只是没优惠 —— "下单"比"优惠"重要。
        order.setDiscountAmount(0);
        List<Long> couponIds = placeOrderDTO.getCouponIds();
        CouponDiscountDTO discount = null;
        if (CollUtils.isNotEmpty(couponIds)) {
            // 把课程信息转成促销服务要的 DTO（★ 分类用三级分类 id，券的使用范围就限定在三级分类）
            List<OrderCourseDTO> orderCourses = new ArrayList<>(courseInfos.size());
            for (CourseSimpleInfoDTO c : courseInfos) {
                orderCourses.add(new OrderCourseDTO()
                        .setId(c.getId())
                        .setCateId(c.getThirdCateId())
                        .setPrice(c.getPrice()));
            }
            // 远程调用：促销服务会重新校验券，并返回优惠总额 + 每件商品的优惠明细
            discount = promotionClient.queryDiscountDetailByOrder(
                    new OrderCouponDTO(couponIds, orderCourses));
            if (discount != null) {
                order.setDiscountAmount(discount.getDiscountAmount());
                // ★ 存促销服务确认过的【用户券id】（不是前端传来的原值）——
                //   后面的核销、退券、查规则都靠它
                order.setCouponIds(discount.getIds());
            }
        }
        Integer realAmount = totalAmount - order.getDiscountAmount();
        // 2.3.封装其它信息
        order.setUserId(userId);
        order.setTotalAmount(totalAmount);
        order.setRealAmount(realAmount);
        order.setStatus(OrderStatus.NO_PAY.getValue());
        order.setMessage(OrderStatus.NO_PAY.getProgressName());
        // 2.4.订单id
        Long orderId = placeOrderDTO.getOrderId();
        order.setId(orderId);

        // 3.封装订单详情
        //    ★ 每件商品的优惠金额从 discountDetail 里取（key 是课程 id）；
        //      取不到就是 0（这件商品不在券的适用范围内，或者压根没用券）
        List<OrderDetail> orderDetails = new ArrayList<>(courseInfos.size());
        for (CourseSimpleInfoDTO courseInfo : courseInfos) {
            int discountValue = discount == null
                    ? 0
                    : discount.getDiscountDetail().getOrDefault(courseInfo.getId(), 0);
            orderDetails.add(packageOrderDetail(courseInfo, order, discountValue));
        }

        // 4.写入数据库
        saveOrderAndDetails(order, orderDetails);

        // 5.删除购物车数据
        cartService.deleteCartByUserAndCourseIds(userId, placeOrderDTO.getCourseIds());

        // 6.核销优惠券
        //    ★ 放在最后：前面都成功了才核销。
        //    ★ 判空用 order.getCouponIds()：只有【真正生效的券】才需要核销
        //      （discount 非 null 也可能一张都没用上，那时 ids 是空的）
        //    ★ 核销失败必须让整个下单失败（降级实现里是抛异常的）——
        //      否则"订单创建成功、券却没核销"，用户能拿同一张券反复下单 ⇒ 资损。
        //    ★ 这一步与上面的写库属于跨服务操作，靠 Seata 的 @GlobalTransactional 保证一致性。
        if (CollUtils.isNotEmpty(order.getCouponIds())) {
            promotionClient.writeOffCoupon(order.getCouponIds());
        }

        // 7.构建下单结果
        return PlaceOrderResultVO.builder()
                .orderId(orderId)
                .payAmount(realAmount)
                .status(order.getStatus())
                .payOutTime(LocalDateTime.now().plusMinutes(tradeProperties.getPayOrderTTLMinutes()))
                .build();
    }

    private List<CourseSimpleInfoDTO> getOnShelfCourse(List<Long> courseIds) {
        // 1.查询课程
        List<CourseSimpleInfoDTO> courseInfos = courseClient.getSimpleInfoList(courseIds);
        LocalDateTime now = LocalDateTime.now();
        // 2.判断状态
        for (CourseSimpleInfoDTO courseInfo : courseInfos) {
            // 2.1.检查课程是否上架
            if(!CourseStatus.SHELF.equalsValue(courseInfo.getStatus())){
                throw new BizIllegalException(TradeErrorInfo.COURSE_NOT_FOR_SALE);
            }
            // 2.2.检查课程是否过期
            if(courseInfo.getPurchaseEndTime().isBefore(now)){
                throw new BizIllegalException(TradeErrorInfo.COURSE_EXPIRED);
            }
        }
        return courseInfos;
    }


    @Override
    @Transactional
    public PlaceOrderResultVO enrolledFreeCourse(Long courseId) {
        Long userId = UserContext.getUser();
        // 1.查询课程信息
        List<Long> cIds = CollUtils.singletonList(courseId);
        List<CourseSimpleInfoDTO> courseInfos = getOnShelfCourse(cIds);
        if (CollUtils.isEmpty(courseInfos)) {
            // 课程不存在
            throw new BizIllegalException(TradeErrorInfo.COURSE_NOT_EXISTS);
        }
        CourseSimpleInfoDTO courseInfo = courseInfos.get(0);
        if(!courseInfo.getFree()){
            // 非免费课程，直接报错
            throw new BizIllegalException(TradeErrorInfo.COURSE_NOT_FREE);
        }
        // 2.创建订单
        Order order = new Order();
        // 2.1.基本信息
        order.setUserId(userId);
        order.setTotalAmount(0);
        order.setDiscountAmount(0);
        order.setRealAmount(0);
        order.setStatus(OrderStatus.ENROLLED.getValue());
        order.setFinishTime(LocalDateTime.now());
        order.setMessage(OrderStatus.ENROLLED.getProgressName());
        // 2.2.订单id
        Long orderId = IdWorker.getId(order);
        order.setId(orderId);

        // 3.订单详情
        // 免费课程报名：金额恒为 0，所以优惠金额也传 0
        OrderDetail detail = packageOrderDetail(courseInfo, order, 0);

        // 4.写入数据库
        saveOrderAndDetails(order, CollUtils.singletonList(detail));

        // 5.发送MQ消息，通知报名成功
        rabbitMqHelper.send(
                MqConstants.Exchange.ORDER_EXCHANGE,
                MqConstants.Key.ORDER_PAY_KEY,
                OrderBasicDTO.builder()
                        .orderId(orderId)
                        .userId(userId)
                        .courseIds(cIds)
                        .finishTime(order.getFinishTime())
                        .build()
        );
        // 6.返回vo
        return PlaceOrderResultVO.builder()
                .orderId(orderId)
                .payAmount(0)
                .status(order.getStatus())
                .build();
    }

    @Override
    public OrderConfirmVO prePlaceOrder(List<Long> courseIds) {
        // 1.查询课程信息
        List<CourseSimpleInfoDTO> courseInfos = courseClient.getSimpleInfoList(courseIds);
        if (CollUtils.isEmpty(courseInfos)) {
            throw new BizIllegalException(TradeErrorInfo.COURSE_NOT_EXISTS);
        }
        List<OrderCourseVO> courses = BeanUtils.copyList(courseInfos, OrderCourseVO.class);
        // 2.计算总价
        int total = courseInfos.stream().mapToInt(CourseSimpleInfoDTO::getPrice).sum();
        // 3.计算折扣：组装订单课程(含三级分类id,用于券范围筛选) → Feign调促销服务推荐优惠方案
        List<OrderCourseDTO> orderCourses = courseInfos.stream()
                .map(ci -> new OrderCourseDTO().setId(ci.getId()).setCateId(ci.getThirdCateId()).setPrice(ci.getPrice()))
                .collect(Collectors.toList());
        List<CouponDiscountDTO> discountSolution = promotionClient.findDiscountSolution(orderCourses);
        // 4.生成订单id
        long orderId = IdWorker.getId();
        // 5.组织返回
        OrderConfirmVO vo = new OrderConfirmVO();
        vo.setOrderId(orderId);
        vo.setTotalAmount(total);
        // 方案列表按优惠金额降序,第一个就是最优方案;无可用方案(promotion降级/无券)时兜底0
        vo.setDiscountAmount(CollUtils.isEmpty(discountSolution) ? 0 : discountSolution.get(0).getDiscountAmount());
        vo.setDiscounts(discountSolution);
        vo.setCourses(courses);
        return vo;
    }

    /**
     * 组装订单明细
     *
     * @param courseInfo    课程信息
     * @param order         所属订单
     * @param discountValue 这件商品分摊到的优惠金额（由促销服务算出的 discountDetail 提供）
     *                      <p>
     *                      ★ 下面这两个字段是「部分退款」的基础：
     *                      {@code discountAmount} = 这件商品优惠了多少，
     *                      {@code realPayAmount} = 这件商品实付多少（原价 - 优惠）。
     *                      将来用户只退其中一件时，按它的 {@code realPayAmount} 退钱；
     *                      不能按原价退 —— 那样等于把优惠也退掉了，平台会吃亏。
     */
    private OrderDetail packageOrderDetail(CourseSimpleInfoDTO courseInfo, Order order, Integer discountValue) {
        OrderDetail detail = new OrderDetail();
        detail.setUserId(order.getUserId());
        detail.setOrderId(order.getId());
        detail.setStatus(order.getStatus());
        detail.setCourseId(courseInfo.getId());
        detail.setPrice(courseInfo.getPrice());
        detail.setCoverUrl(courseInfo.getCoverUrl());
        detail.setName(courseInfo.getName());
        detail.setValidDuration(courseInfo.getValidDuration());
        // 这件商品的优惠金额（调用方按课程id从 discountDetail 里取出来传入）
        detail.setDiscountAmount(discountValue);
        // 实付金额 = 原价 - 该商品的优惠
        detail.setRealPayAmount(courseInfo.getPrice() - detail.getDiscountAmount());
        return detail;
    }

    @Override
    @Transactional
    public void saveOrderAndDetails(Order order, List<OrderDetail> orderDetails) {
        // 4.1.写订单
        boolean success = save(order);
        if (!success) {
            throw new DbException(TradeErrorInfo.PLACE_ORDER_FAILED);
        }
        // 4.2.写订单详情
        if(orderDetails.size() == 1){
            success = detailService.save(orderDetails.get(0));
        }else {
            success = detailService.saveBatch(orderDetails);
        }
        if (!success) {
            throw new DbException(TradeErrorInfo.PLACE_ORDER_FAILED);
        }
    }

    /**
     * 取消订单
     * <p>
     * ★★ 同样需要 {@code @GlobalTransactional}（day12 3.3.5）：
     * 这里有「本地改 order / order_detail 状态」+「远程 promotionClient.refundCoupon 退券」两次跨服务修改。
     * 如果订单状态改成"已取消"了、券却没退回，用户就白白损失一张券 ——
     * 全局事务保证"要么都成，要么都不成"。
     */
    @Override
    @GlobalTransactional
    @Transactional
    public void cancelOrder(Long orderId) {
        Long userId = UserContext.getUser();
        // 1.查询订单
        Order order = getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            throw new BadRequestException(ORDER_NOT_EXISTS);
        }
        // 2.判断订单状态是否已经取消，幂等判断
        if(OrderStatus.CLOSED.equalsValue(order.getStatus())){
           // 订单已经取消，无需重复操作
           return;
        }
        // 3.判断订单是否未支付，只有未支付订单才可以取消
        if(!OrderStatus.NO_PAY.equalsValue(order.getStatus())){
            throw new BizIllegalException(ORDER_ALREADY_FINISH);
        }
        // 4.可以更新订单状态为取消了
        boolean success = lambdaUpdate()
                .set(Order::getStatus, OrderStatus.CLOSED.getValue())
                .set(Order::getMessage, "用户取消订单")
                .set(Order::getCloseTime, LocalDateTime.now())
                .eq(Order::getStatus, OrderStatus.NO_PAY.getValue())
                .eq(Order::getId, orderId)
                .update();
        if (!success) {
            return;
        }
        // 5.更新订单条目的状态
        detailService.updateStatusByOrderId(orderId, OrderStatus.CLOSED.getValue());
        // 6.退还优惠券
        //    ★ 判空：没用券的订单（couponIds 为空）不需要退，
        //      也避免下游 SQL 用 IN <foreach> 拼出 IN () 而报语法错误
        //    ★ 退券失败必须让取消订单失败（降级实现里是抛异常的）——
        //      否则"订单取消了、券却没退回"，用户白白损失一张券。
        //    ★ 这一步与上面的订单状态更新是跨服务操作，靠 Seata 的 @GlobalTransactional 保证一致性。
        if (CollUtils.isNotEmpty(order.getCouponIds())) {
            promotionClient.refundCoupon(order.getCouponIds());
        }
    }

    @Override
    public void deleteOrder(Long id) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();
        // 2.查询订单
        Order order = getById(id);
        if (order == null) {
            return;
        }
        // 3.判断订单所属用户与当前登录用户是否一致
        if(userId != order.getUserId()){
            // 不一致，说明不是当前用户的订单，结束
            throw new BadRequestException("不能删除他人订单");
        }
        // 4.删除订单
        boolean success = removeById(id);
        if (!success) {
            throw new DbException(OPERATE_FAILED);
        }
    }

    @Override
    public PageDTO<OrderPageVO> queryMyOrderPage(OrderPageQuery pageQuery) {
        Long userId = UserContext.getUser();
        // 1.分页排序条件
        Page<Order> p = pageQuery.toMpPageDefaultSortByCreateTimeDesc();
        // 2.分页查询订单
        Integer status = pageQuery.getStatus();
        Page<Order> page = lambdaQuery()
                .eq(status != null, Order::getStatus, status)
                .eq(Order::getUserId, userId)
                .page(p);
        // 3.数据判断
        List<Order> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(p);
        }
        // 4.查询订单明细信息
        List<Long> orderIds = records.stream().map(Order::getId).collect(Collectors.toList());
        // 4.1.根据订单id查询订单明细
        List<OrderDetail> details = detailService.queryByOrderIds(orderIds);
        // 4.2.将订单明细分组，key是订单id，值是订单下的所有detail
        Map<Long, List<OrderDetailVO>> detailMap = details.stream()
                .map(od -> BeanUtils.copyBean(od, OrderDetailVO.class))
                .collect(Collectors.groupingBy(OrderDetailVO::getOrderId));
        // 5.转换VO
        List<OrderPageVO> list = new ArrayList<>(orderIds.size());
        for (Order record : records) {
            // 5.1.转换订单
            OrderPageVO v = BeanUtils.toBean(record, OrderPageVO.class);
            list.add(v);
            // 5.2.写入vo
            v.setDetails(detailMap.get(record.getId()));
            v.setStatusDesc(OrderStatus.desc(v.getStatus()));
        }
        return PageDTO.of(page, list);
    }

    @Override
    public OrderVO queryOrderById(Long id) {
        // 1.查询订单
        Order order = getById(id);
        if (order == null) {
            throw new BadRequestException(ORDER_NOT_EXISTS);
        }
        // 2.查询订单详情
        List<OrderDetail> details = detailService.queryByOrderId(id);
        // 3.转换VO
        // 3.1.订单
        OrderVO vo = BeanUtils.toBean(order, OrderVO.class);
        // 3.2.订单详情
        List<OrderDetailVO> dvs = BeanUtils.copyList(details, OrderDetailVO.class, (d, v) -> v.setCanRefund(
                // 订单已经支付，且 退款没有在进行中，标记为可退款状态
                OrderStatus.canRefund(d.getStatus()) && !RefundStatus.inProgress(v.getRefundStatus())
        ));
        vo.setDetails(dvs);
        // 3.3.订单进度
        vo.setProgressNodes(detailService.packageProgressNodes(order, null));
        // 3.4.优惠券描述
        //     order 表里只存了用过的【用户券id】，而详情页要展示"用了哪些券、规则是什么"，
        //     所以要拿这批 id 回促销服务查规则文案。
        //     ★ 没用券的订单不调远程（也避免下游 SQL 拼出 IN () 报错）
        List<String> rules;
        if (CollUtils.isEmpty(order.getCouponIds())) {
            rules = CollUtils.emptyList();
        } else {
            rules = promotionClient.queryDiscountRules(order.getCouponIds());
        }
        // 多张券的规则用 "/" 拼成一行，前端直接展示
        vo.setCouponDesc(String.join("/", rules));
        return vo;
    }

    @Override
    public PlaceOrderResultVO queryOrderStatus(Long orderId) {
        // 1.查询订单
        Order order = getById(orderId);
        if (order == null) {
            throw new BizIllegalException(ORDER_NOT_EXISTS);
        }
        // 2.计算超时时间
        LocalDateTime outTime = null;
        if(OrderStatus.NO_PAY.equalsValue(order.getStatus())){
            outTime = order.getCreateTime().plusMinutes(tradeProperties.getPayOrderTTLMinutes());
        }
        // 3.封装结果
        return PlaceOrderResultVO.builder()
                .orderId(orderId)
                .payAmount(order.getRealAmount())
                .status(order.getStatus())
                .payOutTime(outTime)
                .build();
    }

    @Override
    @Transactional
    public void handlePaySuccess(PayResultDTO payResult) {
        // 1.查询订单
        Order order = getById(payResult.getBizOrderId());
        if (order == null) {
            return;
        }
        // 2.更新订单状态
        Order o = new Order();
        o.setId(order.getId());
        o.setStatus(OrderStatus.PAYED.getValue());
        o.setPayTime(payResult.getSuccessTime());
        o.setPayChannel(payResult.getPayChannel());
        o.setPayOrderNo(payResult.getPayOrderNo());
        o.setMessage("用户支付成功");
        updateById(o);
        // 3.更新订单条目
        detailService.markDetailSuccessByOrderId(o.getId(), payResult.getPayChannel(), payResult.getSuccessTime());
        // 4.查询订单包含的课程信息
        List<Long> cIds = detailService.queryCourseIdsByOrderId(o.getId());
        // 5.发送MQ消息，通知报名成功
        rabbitMqHelper.send(
                MqConstants.Exchange.ORDER_EXCHANGE,
                MqConstants.Key.ORDER_PAY_KEY,
                OrderBasicDTO.builder()
                        .orderId(o.getId()).userId(order.getUserId()).courseIds(cIds)
                        .finishTime(o.getPayTime())
                        .build()
        );
    }

}
