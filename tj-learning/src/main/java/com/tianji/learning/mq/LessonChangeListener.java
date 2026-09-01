package com.tianji.learning.mq;

import com.tianji.api.dto.trade.OrderBasicDTO;
import com.tianji.common.constants.MqConstants;
import com.tianji.learning.service.ILearningLessonService;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;



@Component
@Slf4j
@RequiredArgsConstructor
public class LessonChangeListener {

    private  final ILearningLessonService lessonService;

    @RabbitListener(bindings = @QueueBinding(
           value = @Queue(value = "learning.lesson.pay.queue",durable = "true"),
            exchange = @Exchange(name = MqConstants.Exchange.ORDER_EXCHANGE,type = ExchangeTypes.TOPIC),
            key = MqConstants.Key.ORDER_PAY_KEY
    ))
    public void listenLessonPay(OrderBasicDTO orderBasicDTO){
        if(orderBasicDTO==null||(orderBasicDTO.getCourseIds()).isEmpty()||orderBasicDTO.getUserId()==null){
            //打日志
            log.error("出错");
            return;
        }
        lessonService.addUserLessons(orderBasicDTO.getUserId(),orderBasicDTO.getCourseIds());
    }

}
