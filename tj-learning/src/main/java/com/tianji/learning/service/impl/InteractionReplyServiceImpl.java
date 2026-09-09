package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.ReplyDTO;
import com.tianji.learning.domain.enums.QuestionStatus;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.ReplyPageQuery;
import com.tianji.learning.domain.vo.ReplyVO;
import com.tianji.learning.mapper.InteractionReplyMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.tianji.learning.service.IInteractionReplyService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动问题的回答或评论 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-03
 */
@RequiredArgsConstructor
@Service
public class InteractionReplyServiceImpl extends ServiceImpl<InteractionReplyMapper, InteractionReply> implements IInteractionReplyService {
    @Lazy // 打破 InteractionQuestionService↔ReplyService 的循环依赖
    private final IInteractionQuestionService questionService;
    private final UserClient userClient;
    private final RabbitMqHelper rabbitMqHelper;

    @Override
    @Transactional
    public void saveReply(ReplyDTO replyFormDTO) {
        //1.获取用户id
        Long userId = UserContext.getUser();
        //2.查询问题是否存在
        InteractionQuestion question = questionService.getById(replyFormDTO.getQuestionId());
        if (question == null) {
            throw new BadRequestException("问题不存在");
        }
        //3.DTO转PO，补作者id（拷贝的是前端传来的DTO，不是question）
        InteractionReply reply = BeanUtils.copyBean(replyFormDTO, InteractionReply.class);
        reply.setUserId(userId);
        //4.按answerId是否为空，区分回答/评论
        if (replyFormDTO.getAnswerId() == null) {
            //4a.回答：先插入拿到自增id，再更新问题表统计
            save(reply);
            questionService.lambdaUpdate()
                    .setSql("answer_times=answer_times + 1")
                    .set(InteractionQuestion::getLatestAnswerId, reply.getId())
                    .eq(InteractionQuestion::getId, replyFormDTO.getQuestionId())
                    .update();
            //发送消息到队列
            rabbitMqHelper.send(MqConstants.Exchange.LEARNING_EXCHANGE, MqConstants.Key.WRITE_REPLY, userId);
        } else {
            //4b.评论：先校验上级回答存在（查的是本表，不是问题表），再插入，最后累加评论数
            InteractionReply parent = getById(replyFormDTO.getAnswerId());
            if (parent == null) {
                throw new BadRequestException("回答不存在或已被删除");
            }
            save(reply);
            lambdaUpdate()
                    .setSql("reply_times=reply_times + 1")
                    .eq(InteractionReply::getId, replyFormDTO.getAnswerId())
                    .update();
        }

        //5.学生提交的，把问题标记为未查看，提醒管理端有新动态
        if (Boolean.TRUE.equals(replyFormDTO.getIsStudent())) {
            questionService.lambdaUpdate()
                    .set(InteractionQuestion::getStatus, QuestionStatus.UN_CHECK.getValue())
                    .eq(InteractionQuestion::getId, replyFormDTO.getQuestionId())
                    .update();
        }
    }

    @Override
    public PageDTO<ReplyVO> getReplyPage(ReplyPageQuery query) {
        // 1.参数校验：问题id和回答id至少传一个
        if (query.getQuestionId() == null && query.getAnswerId() == null) {
            throw new BadRequestException("问题id和回答id至少传一个");
        }
        // 2.分流构建查询条件（唯一的差异点：查回答还是查评论）
        LambdaQueryChainWrapper<InteractionReply> w = lambdaQuery();
        if (query.getQuestionId() != null) {
            // 查回答：属于该问题，且不是评论（answerId为空 = 它自己就是回答）
            w.eq(InteractionReply::getQuestionId, query.getQuestionId())
             .isNull(InteractionReply::getAnswerId);
        } else {
            // 查评论：挂在该回答下（回答的answerId为null天然被排除）
            w.eq(InteractionReply::getAnswerId, query.getAnswerId());
        }
        // 3.公共条件 + 分页查询（wrapper累积拼接，if内外条件AND到一起）
        Page<InteractionReply> page = w
                .eq(InteractionReply::getHidden, false)
                .orderByDesc(InteractionReply::getCreateTime)
                .page(query.toMpPage());
        List<InteractionReply> records = page.getRecords();
        if (records.isEmpty()) {
            return PageDTO.of(page, new ArrayList<>());
        }
        // 4.收集用户id：作者 + 被评论者（评论才有targetUserId），去重
        Set<Long> userIds = new HashSet<>();
        for (InteractionReply r : records) {
            if (r.getUserId() != null) {
                userIds.add(r.getUserId());
            }
            if (r.getTargetUserId() != null) {
                userIds.add(r.getTargetUserId());
            }
        }
        // 5.批量查询用户信息（一次Feign调用），转Map方便取用
        List<UserDTO> users = userClient.queryUserByIds(userIds);
        Map<Long, UserDTO> userMap = users == null ? new HashMap<>() :
                users.stream().collect(Collectors.toMap(UserDTO::getId, Function.identity()));
        // 6.转VO：匿名脱敏 + 补昵称头像 + 评论补targetUserName
        List<ReplyVO> vos = new ArrayList<>(records.size());
        for (InteractionReply reply : records) {
            ReplyVO vo = new ReplyVO();
            BeanUtils.copyProperties(reply, vo);
            if (Boolean.TRUE.equals(reply.getAnonymity())) {
                // 匿名：抹掉身份信息（copyProperties拷进来的userId必须显式清掉）
                vo.setUserId(null);
            } else {
                // 非匿名：补作者昵称、头像、用户类型
                UserDTO u = userMap.get(reply.getUserId());
                if (u != null) {
                    vo.setUserName(u.getName());
                    vo.setUserIcon(u.getIcon());
                    vo.setUserType(u.getType());
                }
            }
            // 评论特有：补@谁的昵称（直接评论回答时targetUserId为null，跳过）
            if (reply.getTargetUserId() != null) {
                UserDTO t = userMap.get(reply.getTargetUserId());
                if (t != null) {
                    vo.setTargetUserName(t.getName());
                }
            }
            // 每条记录都要进列表，只add一次（匿名的也展示，只是没身份）
            vos.add(vo);
        }
        return PageDTO.of(page, vos);
    }

    @Override
    public PageDTO<ReplyVO> getAdminReplyPage(ReplyPageQuery replyPageQuery) {
        // 1.参数校验：问题id和回答id至少传一个
        if (replyPageQuery.getQuestionId() == null && replyPageQuery.getAnswerId() == null) {
            throw new BadRequestException("问题id和回答id至少传一个");
        }
        // 2.分页查询(管理端:不过滤hidden,能看到被隐藏的违规回答)
        Page<InteractionReply> page = lambdaQuery()
                .eq(replyPageQuery.getQuestionId() != null, InteractionReply::getQuestionId, replyPageQuery.getQuestionId())
                .eq(replyPageQuery.getAnswerId() != null, InteractionReply::getAnswerId, replyPageQuery.getAnswerId())
                .page(replyPageQuery.toMpPage());
        List<InteractionReply> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 3.收集用户id:回答者 + 被评论者(合并一次查询,管理端可见匿名者身份,不做脱敏)
        Set<Long> userIds = new HashSet<>();
        for (InteractionReply r : records) {
            if (r.getUserId() != null) {
                userIds.add(r.getUserId());
            }
            if (r.getTargetUserId() != null) {
                userIds.add(r.getTargetUserId());
            }
        }
        // 4.批量查询用户信息(一次Feign调用),转Map方便取用
        Map<Long, UserDTO> userMap = new HashMap<>();
        if (CollUtils.isNotEmpty(userIds)) {
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, Function.identity()));
        }
        // 5.转VO:先拷全字段(content/createTime/hidden等),再补昵称头像
        List<ReplyVO> vos = new ArrayList<>(records.size());
        for (InteractionReply r : records) {
            ReplyVO vo = BeanUtils.copyBean(r, ReplyVO.class);
            // 补回答者昵称、头像、用户类型
            UserDTO u = userMap.get(r.getUserId());
            if (u != null) {
                vo.setUserName(u.getName());
                vo.setUserIcon(u.getIcon());
                vo.setUserType(u.getType());
            }
            // 评论特有:补@谁的昵称(直接评论回答时targetUserId为null,跳过)
            if (r.getTargetUserId() != null) {
                UserDTO t = userMap.get(r.getTargetUserId());
                if (t != null) {
                    vo.setTargetUserName(t.getName());
                }
            }
            vos.add(vo);
        }
        return PageDTO.of(page, vos);
    }

    @Override
    @Transactional
    public void updateHidden(Long id, Boolean hidden) {
        // 1.参数校验
        if (hidden == null) {
            throw new BadRequestException("hidden 参数不能为空");
        }
        // 2.根据id查询该回答/评论
        InteractionReply reply = getById(id);
        if (reply == null) {
            throw new BadRequestException("回复不存在");
        }
        // 3.判断隐藏的是否是"回答":回答的 answerId 为空;评论的 answerId 指向所属回答
        if (reply.getAnswerId() == null) {
            // 3.1.隐藏回答时,连带隐藏它下面所有评论(answerId = 该回答id 的记录)
            lambdaUpdate()
                    .eq(InteractionReply::getAnswerId, id)
                    .set(InteractionReply::getHidden, hidden)
                    .update();
        }
        // 4.更新当前回复自身的隐藏状态
        lambdaUpdate()
                .eq(InteractionReply::getId, id)
                .set(InteractionReply::getHidden, hidden)
                .update();
    }
}
