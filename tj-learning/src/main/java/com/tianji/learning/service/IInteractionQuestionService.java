package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionVO;

import javax.validation.Valid;

/**
 * <p>
 * 互动提问的问题表 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-03
 */
public interface IInteractionQuestionService extends IService<InteractionQuestion> {

    void saveQuestion(@Valid QuestionFormDTO questionDTO);

    void updateQuestion(Long id,QuestionFormDTO questionFormDTO);

    PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query);

    QuestionVO queryQuestionById(Long id);

    void deleteQuestion(Long id);

    void hiddenQuestion(Long id, Boolean hidden);

    QuestionVO queryQuestionByid(Long id);
}
