package com.tianji.learning.controller;


import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.service.ILearningRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * <p>
 * 学习记录表 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-08-31
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/learning-records")
@Api(tags = "学习记录的相关接口")
public class LearningRecordController {
    private final ILearningRecordService learningRecordService;

    /**
     * 查询指定课程的学习记录
     * <p>返回该用户课表中指定课程的进度信息(最近学习小节 + 各小节完成情况),
     * 供播放器恢复学习进度用;未报名该课程返回空</p>
     *
     * @param courseId 课程id
     * @return 课表进度信息(含学习记录);未报名返回 null
     */
    @ApiOperation("查询指定课程的学习记录")
    @GetMapping("/course/{courseId}")
    public LearningLessonDTO queryLearningRecordByCourse(
            @ApiParam(value = "课程id", example = "2") @PathVariable("courseId") Long courseId) {
        return learningRecordService.queryLesson(courseId);
    }

    /**
     * 提交学习记录
     * <p>前端在学习到某一小节时上报进度(观看时长/完成状态),后端更新学习记录并刷新课表进度</p>
     *
     * @param formDTO 学习记录表单(sectionType:小节类型、lessonId:课表id、moment:观看时长等)
     */
    @ApiOperation("提交学习记录")
    @PostMapping
    public void addLearningRecord(@Valid @RequestBody LearningRecordFormDTO formDTO) {
        learningRecordService.addLearningRecord(formDTO);
    }
}
