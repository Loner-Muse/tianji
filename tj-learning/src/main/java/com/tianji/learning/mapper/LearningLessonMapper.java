package com.tianji.learning.mapper;

import com.tianji.learning.domain.po.LearningLesson;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 学生课程表 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-08-30
 */
public interface LearningLessonMapper extends BaseMapper<LearningLesson> {

    /**
     * 统计用户所有"进行中"学习计划的本周计划总节数
     * @param userId 用户id
     * @return 本周计划学习总节数
     */
    Integer queryTotalPlan(Long userId);
}
