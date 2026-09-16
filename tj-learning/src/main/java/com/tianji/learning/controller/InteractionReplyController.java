package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.service.IInteractionReplyService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * <p>
 * 互动问题的回答或评论 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-09-03
 */
@RestController
@RequestMapping("/replies")
@Api(tags = "互动回答相关接口")
@RequiredArgsConstructor
public class InteractionReplyController {
    private final IInteractionReplyService replyService;

    @ApiOperation("发布回答")
    @PostMapping
    public void saveReply(@Valid @RequestBody ReplyDTO replyFormDTO) {
        replyService.saveReply(replyFormDTO);
    }

    @ApiOperation("分页查询回答")
    @GetMapping("page")
    public PageDTO<ReplyVO> getReplyPage(ReplyPageQuery replyPageQuery) {
        return replyService.getReplyPage(replyPageQuery);
    }
}
