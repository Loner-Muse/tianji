package com.tianji.learning.controller;



import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.learning.domain.dto.LearningPlanDTO;
import com.tianji.learning.domain.query.MyLessonPageQuery;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;
import com.tianji.learning.service.ILearningLessonService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * <p>
 * 学生课程表 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-08-30
 */
@RestController
@RequestMapping("/lessons")
@Api(tags = "我的课表相关接口")
@RequiredArgsConstructor
public class LearningLessonController {
    private final ILearningLessonService learningLessonService;

    /**
     * 分页查询我的课表
     *
     * @param query 分页参数(pageNo/pageSize)
     * @return 课表分页结果
     */
    @ApiOperation("分页查询我的课表")
    @GetMapping("/page")
    public PageDTO<LearningLessonVO> queryMyLessonPage(MyLessonPageQuery query) {
        return learningLessonService.queryMyLessonPage(query);
    }

    /**
     * 查询我正在学习的课程
     *
     * @return 正在学习的课程信息;无则返回空
     */
    @ApiOperation("查询我正在学习的课程")
    @GetMapping("/now")
    public LearningLessonVO queryNowLesson() {
        return learningLessonService.queryNowLesson();
    }

    /**
     * 校验指定课程是否已经报名(供内部Feign调用)
     *
     * @param courseId 课程id
     * @return 已报名返回课表lessonId,否则返回空
     */
    @ApiOperation("校验当前课程是否已经报名")
    @GetMapping("/{courseId}/valid")
    public Long isLessonValid(@ApiParam(value = "课程id", example = "2") @PathVariable("courseId") Long courseId) {
        return learningLessonService.isLessonValid(courseId);
    }

    /**
     * 查询指定课程的学习信息
     *
     * @param courseId 课程id
     * @return 课表记录;未报名返回空
     */
    @ApiOperation("查询指定课程信息")
    @GetMapping("/{courseId}")
    public LearningLessonDTO queryLesson(@ApiParam(value = "课程id", example = "2") @PathVariable("courseId") Long courseId) {
        return learningLessonService.queryLesson(courseId);
    }

    /**
     * 统计课程学习人数
     *
     * @param courseId 课程id
     * @return 学习人数
     */
    @ApiOperation("统计课程学习人数")
    @GetMapping("/{courseId}/count")
    public Integer countLearningLessonByCourse(@ApiParam(value = "课程id", example = "2") @PathVariable("courseId") Long courseId) {
        return learningLessonService.countLearningLessonByCourse(courseId);
    }

    /**
     * 创建学习计划
     *
     * @param planDTO 学习计划表单(courseId/freq)
     */
    @ApiOperation("创建学习计划")
    @PostMapping("/plans")
    public void createLearningPlans(@Valid @RequestBody LearningPlanDTO planDTO) {
        learningLessonService.createLearningPlan(planDTO.getCourseId(), planDTO.getFreq());
    }

    /**
     * 分页查询我的学习计划
     *
     * @param query 分页参数(pageNo/pageSize)
     * @return 学习计划分页结果(含本周统计)
     */
    @ApiOperation("分页查询我的学习计划")
    @GetMapping("/plans")
    public LearningPlanPageVO queryMyPlans(PageQuery query) {
        return learningLessonService.queryMyLessonPagePlan(query);
    }
}
