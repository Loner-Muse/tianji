package com.tianji.learning.controller;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.service.IInteractionReplyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 互动问题的回答或评论 管理端控制器
 * </p>
 *
 * @author author
 * @since 2026-09-03
 */
@Api(tags = "回答管理相关接口")
@RestController
@RequestMapping("/admin/replies")
@RequiredArgsConstructor
public class
InteractionReplyAdminController {

    private final IInteractionReplyService replyService;

    /**
     * 管理端分页查询回答或评论(含被隐藏的,便于运营审核)
     *
     * @param replyPageQuery 分页查询条件(questionId或answerId至少一个)
     * @return 回答/评论分页结果
     */
    @ApiOperation("管理端分页查询回答或评论")
    @GetMapping("page")
    public PageDTO<ReplyVO> getAdminReplyPage(ReplyPageQuery replyPageQuery) {
        return replyService.getAdminReplyPage(replyPageQuery);
    }

    /**
     * 隐藏或显示回答/评论(管理端操作)
     * <p>若隐藏的是"回答",其下的评论也会一并隐藏</p>
     *
     * @param id     回答或评论id
     * @param hidden 是否隐藏,true/false
     */
    @ApiOperation("隐藏或显示回答或评论")
    @PutMapping("/{id}/hidden/{hidden}")
    public void updateHidden(
            @ApiParam(value = "回答或评论id", example = "1") @PathVariable("id") Long id,
            @ApiParam(value = "是否隐藏，true/false", example = "true") @PathVariable("hidden") Boolean hidden
    ) {
        replyService.updateHidden(id, hidden);
    }
}
