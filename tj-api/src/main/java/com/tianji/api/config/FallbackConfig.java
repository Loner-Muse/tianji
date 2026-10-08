package com.tianji.api.config;

import com.tianji.api.client.learning.fallback.LearningClientFallback;
import com.tianji.api.client.promotion.fallback.PromotionClientFallback;
import com.tianji.api.client.remark.fallback.RemarkClientFallback;
import com.tianji.api.client.trade.fallback.TradeClientFallback;
import com.tianji.api.client.user.fallback.UserClientFallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 降级（fallback）Bean 的注册中心
 * <p>
 * ★ 为什么需要这个类：
 * {@code @FeignClient(fallbackFactory = XxxFallback.class)} 里的 fallback 类，
 * Spring Cloud OpenFeign <b>会去容器里找这个类型的 Bean</b>；
 * 如果 fallback 类只是普通类（没加 {@code @Component}）又没在这里注册，
 * 启动时就会报：<i>"No fallbackFactory instance of type class ... found for feign client ..."</i>
 * <p>
 * ★ 注意：只有<b>配置了 fallbackFactory 的 Client</b> 才需要在这里注册。
 * 例如 {@code CourseClient} / {@code ExamClient} 没配 fallbackFactory，所以不用注册。
 * <p>
 * ★ 另外：{@code RequestIdRelayConfiguration} 上的 {@code @EnableFeignClients(basePackages = "com.tianji.api.client")}
 * 会扫描【整个 client 包】—— 所以连被调方自己（比如 promotion 服务依赖 tj-api）也会扫到自己的 Client，
 * 于是【每个服务】启动时都需要这些 fallback Bean 存在。本类通过 spring.factories 对所有服务生效。
 */
@Configuration
public class FallbackConfig {
    @Bean
    public LearningClientFallback learningClientFallback(){
        return new LearningClientFallback();
    }

    @Bean
    public RemarkClientFallback remarkClientFallback(){
        return new RemarkClientFallback();
    }

    @Bean
    public TradeClientFallback tradeClientFallback(){
        return new TradeClientFallback();
    }

    @Bean
    public UserClientFallback userClientFallback(){
        return new UserClientFallback();
    }

    /**
     * day12 新增：促销服务的降级工厂
     * （PromotionClient 配了 fallbackFactory，所以必须在这里注册成 Bean）
     */
    @Bean
    public PromotionClientFallback promotionClientFallback(){
        return new PromotionClientFallback();
    }

}
