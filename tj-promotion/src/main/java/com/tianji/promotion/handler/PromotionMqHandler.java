package com.tianji.promotion.handler;

import com.tianji.common.constants.MqConstants;
import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 促销服务相关的 MQ 监听器
 * <p>
 * 本类不写业务逻辑，只做"收消息 → 转交 service"这一件事。
 * 落库逻辑全部在 {@link IUserCouponService#checkAndCreateUserCoupon} 里，
 * 这样手动领取、兑换码兑换、MQ 消费三条路径共用同一份实现。
 */
@Component
@RequiredArgsConstructor
public class PromotionMqHandler {

    /**
     * 字段名用 userCouponService 而不是 couponService：
     * 项目里还有一个 ICouponService，名字太像会混淆。
     */
    private final IUserCouponService userCouponService;

    /**
     * 监听领券消息，完成真正的落库（扣 DB 库存 + 写 user_coupon）
     * <p>
     * {@code @QueueBinding} 是声明式绑定：应用启动时若队列/交换机不存在会自动创建，
     * 不需要去 RabbitMQ 控制台手动建，换环境也不用重新点一遍。
     * <p>
     * 三个参数的含义：
     * <ul>
     *   <li>{@code value = @Queue}：队列名 + {@code durable = "true"} 持久化（注意是字符串）</li>
     *   <li>{@code exchange}：绑定的交换机，类型必须是 TOPIC ——
     *       它叫 promotion.topic，且 RabbitMQ 的交换机类型声明后不可更改，
     *       写成 DIRECT 会在启动时报 PRECONDITION_FAILED</li>
     *   <li>{@code key}：RoutingKey</li>
     * </ul>
     * 参数类型直接写 {@link UserCouponDTO}：MqConfig 里配了 Jackson2JsonMessageConverter，
     * 发送端会把类名放进消息头，接收端据此自动反序列化，不需要手工转。
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "coupon.receive.queue", durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.PROMOTION_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.COUPON_RECEIVE
    ))
    public void listenCouponReceiveMessage(UserCouponDTO dto) {
        userCouponService.checkAndCreateUserCoupon(dto);
    }
}
