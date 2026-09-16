package com.tianji.learning.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TableNameHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.tianji.learning.utils.TableInfoContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * <p>
 * MyBatis-Plus 相关配置
 * </p>
 * 历史榜单是按赛季水平分表的（points_board_{赛季id}），
 * 这里注册动态表名插件，把 SQL 中的 points_board 替换成实际表名。
 * 具体表名由定时任务计算后放入 TableInfoContext（ThreadLocal）传递过来。
 */
@Configuration
public class MybatisConfiguration {

    @Bean
    public DynamicTableNameInnerInterceptor dynamicTableNameInnerInterceptor() {
        // 1.准备 Map：key 是实体上的旧表名，value 是表名处理器
        Map<String, TableNameHandler> map = new HashMap<>(1);
        // 2.points_board 表名替换规则：从 ThreadLocal 中读取动态表名，
        //   没有设置时保持原表名不变（普通查询不受影响）
        map.put("points_board", (sql, tableName) ->
                TableInfoContext.getInfo() == null ? tableName : TableInfoContext.getInfo());
        // 3.返回插件，最终由 tj-common 的 MybatisConfig 注册到 MybatisPlusInterceptor
        return new DynamicTableNameInnerInterceptor(map);
    }
}
