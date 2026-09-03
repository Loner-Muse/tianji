package com.tianji.learning.service.impl;

import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 互动提问的问题表 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-03
 */
@Service
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion> implements IInteractionQuestionService {

    private final UserClient userClient;
    private final IInteractionReplyService replyService;
    @Override
    public void saveQuestion(QuestionFormDTO questionDTO) {
        //1.获取用户id
        Long user = UserContext.getUser();
        //2.转换属性
        InteractionQuestion interactionQuestion = BeanUtils.copyBean(questionDTO, InteractionQuestion.class);
        //3.设置用户id
        interactionQuestion.setUserId(user);
        //4.保存数据
        save(interactionQuestion);
    }

    @Override
    public void updateQuestion(Long id,QuestionFormDTO questionFormDTO) {
        //1.根据id查询数据库
        InteractionQuestion interactionQuestion = baseMapper.selectById(id);
        //2.更新数据
        interactionQuestion = BeanUtils.copyBean(questionFormDTO,  InteractionQuestion.class);
        //3.保存数据
        updateById(interactionQuestion);
    }

    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        //1.获取用户ID
        Long user = UserContext.getUser();
        //2.查询数据
        lambdaQuery().eq(InteractionQuestion::getUserId, user)
                .eq(
        return null;
    }

    @Override
    public QuestionVO queryQuestionById(Long id) {
        //1.根据id查询数据库
        InteractionQuestion interactionQuestion = baseMapper.selectById(id);
        //2.判断是否存在
        if(interactionQuestion == null){
            throw new IllegalArgumentException("问题不存在");
        }
        //3.转换属性
        QuestionVO questionVO = BeanUtils.copyBean(interactionQuestion, QuestionVO.class);
        //4.判断是否匿名（包装类型用 TRUE.equals 防止拆箱空指针）
        if(Boolean.TRUE.equals(interactionQuestion.getAnonymity())){
            //匿名问题：抹去身份信息直接返回，无需查询用户
            questionVO.setUserId(null);
            questionVO.setUserName(null);
            questionVO.setUserIcon(null);
            return questionVO;
        }
        //5.非匿名问题：远程查询用户信息，补齐昵称、头像（判空防止降级返回null时NPE）
        UserDTO userDTO = userClient.queryUserById(interactionQuestion.getUserId());
        if(userDTO != null){
            questionVO.setUserName(userDTO.getName());
            questionVO.setUserIcon(userDTO.getIcon());
        }
        return questionVO;
    }

    @Override
    public void deleteQuestion(Long id) {
        //1.获取用户Id并判空（未登录直接拒绝，防止 NPE）
        Long user = UserContext.getUser();
        if (user == null) {
            throw new BadRequestException("用户未登录");
        }
        //2.根据id查询数据库
        InteractionQuestion interactionQuestion = baseMapper.selectById(id);
        //3.判断是否存在
        if (interactionQuestion == null) {
            throw new BadRequestException("问题不存在");
        }
        //4.判断是否是当前用户的问题
        if (!interactionQuestion.getUserId().equals(user)) {
            throw new BadRequestException("您没有权限删除该问题");
        }
        //5.级联删除该问题下的所有回答（防孤儿数据）
        replyService.lambdaUpdate()
                .eq(InteractionReply::getQuestionId, id)
                .remove();
        //6.删除问题本身
        removeById(id);
    }

    @Override
    public void hiddenQuestion(Long id, Boolean hidden) {
        //1.参数校验
        if (hidden == null) {
            throw new BadRequestException("hidden 参数不能为空");
        }
        //2.判断问题是否存在
        InteractionQuestion q = baseMapper.selectById(id);
        if (q == null) {
            throw new BadRequestException("问题不存在");
        }
        //3.只更新 hidden 字段（条件更新，避免整个实体回写）
        lambdaUpdate()
                .set(InteractionQuestion::getHidden, hidden)
                .eq(InteractionQuestion::getId, id)
                .update();
    }

    @Override
    public QuestionVO queryQuestionByid(Long id) {
        //1.根据id查询数据库
        InteractionQuestion interactionQuestion = baseMapper.selectById(id);
        //2.判断是否存在
        if (interactionQuestion == null) {
            throw new BadRequestException("问题不存在");
        }
        //3.转换属性（管理端设计上可见匿名提问者身份，不做匿名脱敏）
        QuestionVO questionVO = new QuestionVO();
        BeanUtils.copyProperties(interactionQuestion, questionVO);
        //4.查询提问者信息，补齐昵称、头像
        UserDTO userDTO = userClient.queryUserById(interactionQuestion.getUserId());
        if (userDTO != null) {
            questionVO.setUserName(userDTO.getName());
            questionVO.setUserIcon(userDTO.getIcon());
        }
        //5.如果是未查看状态，标记为已查看（只更新status字段，Integer防null拆箱）
        if (Integer.valueOf(0).equals(interactionQuestion.getStatus())) {
            lambdaUpdate()
                    .set(InteractionQuestion::getStatus, 1)
                    .eq(InteractionQuestion::getId, id)
                    .update();
        }
        return questionVO;
    }
}