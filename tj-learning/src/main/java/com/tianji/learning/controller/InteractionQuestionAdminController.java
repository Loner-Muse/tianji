package com.tianji.learning.controller;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.service.IInteractionQuestionService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 互动提问的问题表 管理端控制器
 * </p>
 *
 * @author author
 * @since 2026-09-03
 */
@Api(tags = "问题管理相关接口")
@RestController
@RequestMapping("/admin/questions")
@RequiredArgsConstructor
public class InteractionQuestionAdminController {

    private final IInteractionQuestionService questionService;

    /**
     * 管理端分页查询互动问题
     * <p>支持按课程名搜索、按状态筛选、按提问时间区间筛选</p>
     *
     * @param query 分页查询条件(courseName/status/beginTime/endTime)
     * @return 管理端问题分页结果(含课程名/章名/节名/分类名/提问者昵称)
     */
    @ApiOperation("管理端分页查询互动问题")
    @GetMapping("page")
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query) {
        return questionService.queryQuestionPageAdmin(query);
    }

    /**
     * 管理端根据id查询互动问题
     *
     * @param id 问题id
     * @return 问题详情(管理端可见匿名提问者身份)
     */
    @ApiOperation("管理端根据id查询互动问题")
    @GetMapping("/{id}")
    public QuestionVO queryQuestionByIdAdmin(@ApiParam(value = "问题id", example = "1") @PathVariable("id") Long id) {
        return questionService.queryQuestionByid(id);
    }

    /**
     * 隐藏或显示问题(管理端操作)
     *
     * @param id     问题id
     * @param hidden 是否隐藏,true/false
     */
    @ApiOperation("隐藏或显示问题")
    @PutMapping("/{id}/hidden/{hidden}")
    public void hiddenQuestion(
            @ApiParam(value = "问题id", example = "1") @PathVariable("id") Long id,
            @ApiParam(value = "是否隐藏，true/false", example = "true") @PathVariable("hidden") Boolean hidden
    ) {
        questionService.hiddenQuestion(id, hidden);
    }
}
