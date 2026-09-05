package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 互动问题的回答或评论 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-09-03
 */
@RestController
@RequestMapping("/interaction-reply")
@RequiredArgsConstructor
public class InteractionReplyController {
    private final IInteractionReplyService replyService;
    @PostMapping("/replies")
    public void saveReply(ReplyDTO replyFormDTO) {
        replyService.saveReply(replyFormDTO);
    }
    @GetMapping("/replies/page")
    public PageDTO<ReplyVO> getReplyPage(ReplyPageQuery replyPageQuery) {
        return replyService.getReplyPage(replyPageQuery);
    }
}
