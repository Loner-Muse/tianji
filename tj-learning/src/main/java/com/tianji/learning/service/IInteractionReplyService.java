package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.po.InteractionReply;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;

/**
 * <p>
 * 互动问题的回答或评论 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-03
 */
public interface IInteractionReplyService extends IService<InteractionReply> {

    void saveReply(ReplyDTO replyFormDTO);

    PageDTO<ReplyVO> getReplyPage(ReplyPageQuery replyPageQuery);

    PageDTO<ReplyVO> getAdminReplyPage(ReplyPageQuery replyPageQuery);

    void updateHidden(Long id, Boolean hidden);
}
