package com.tianji.learning.service;

import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.learning.domain.po.LearningLesson;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;
import org.hibernate.validator.constraints.Range;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * <p>
 * 学生课程表 服务类
 * </p>
 *
 * @author author
 * @since 2026-08-30
 */
public interface ILearningLessonService extends IService<LearningLesson> {

        void addUserLessons(Long userId, List<Long> courseId);



    PageDTO<LearningLessonVO> queryMyLessonPage(PageQuery query);

    LearningLessonVO queryNowLesson();

    Long isLessonValid(Long courseId);

    LearningLessonDTO queryLesson(Long courseId);

    Integer countLearningLessonByCourse(Long courseId);

    void createLearningPlan(@NotNull @Min(1) Long courseId, @NotNull @Range(min = 1, max = 50) Integer freq);

    LearningPlanPageVO queryMyLessonPagePlan(PageQuery query);
}
