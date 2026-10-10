package com.tianji.common.autoconfigure.mvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.tianji.common.utils.DateUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigInteger;
import java.time.format.DateTimeFormatter;

@Configuration
@ConditionalOnClass(ObjectMapper.class)
public class JsonConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer() {
        return jacksonObjectMapperBuilder -> {
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DateUtils.DEFAULT_DATE_TIME_FORMAT);
            // 反序列化(入参)兼容两种格式:
            //   1) 空格分隔 "2026-10-10 17:30:00"
            //   2) ISO带T   "2026-10-10T17:30:00"
            // ★ 前端(dayjs)统一传带T格式,若只按空格解析会导致反序列化失败(500),
            //   表现就是"学习进度存不进去/不能跳上次进度"
            DateTimeFormatter lenientDateTimeFormatter =
                    DateTimeFormatter.ofPattern("yyyy-MM-dd[' ']['T']HH:mm:ss");
            jacksonObjectMapperBuilder.timeZone(DateUtils.TIME_ZONE_8);
            jacksonObjectMapperBuilder.serializers(new LocalDateTimeSerializer(dateTimeFormatter));
            jacksonObjectMapperBuilder.deserializers(new LocalDateTimeDeserializer(lenientDateTimeFormatter));
            // long -> string
            jacksonObjectMapperBuilder.serializerByType(Long.class, ToStringSerializer.instance);
            jacksonObjectMapperBuilder.serializerByType(BigInteger.class, ToStringSerializer.instance);
        };
    }
}
