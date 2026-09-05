package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final CourseClient courseClient;
    private final SearchClient searchClient;
    private final CatalogueClient catalogueClient;
    private final CategoryCache categoryCache;
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
        BeanUtils.copyProperties(questionFormDTO, interactionQuestion);
        //3.保存数据
        updateById(interactionQuestion);
    }

    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        // 1.参数校验:课程id和小节id不能都为空
        Long courseId = query.getCourseId();
        Long sectionId = query.getSectionId();
        if (courseId == null && sectionId == null) {
            throw new BadRequestException("课程id和小节id不能都为空");
        }
        // 2.分页查询问题列表
        //   - 排除 description 大文本字段(列表页不需要,减小IO)
        //   - 只有 onlyMine=true 才限定查询自己的问题
        //   - 课程/小节条件按需拼接,避免传null时拼出 xx = null 查不到数据
        Page<InteractionQuestion> page = lambdaQuery()
                .select(InteractionQuestion.class, info -> !info.getProperty().equals("description"))
                .eq(Boolean.TRUE.equals(query.getOnlyMine()), InteractionQuestion::getUserId, UserContext.getUser())
                .eq(courseId != null, InteractionQuestion::getCourseId, courseId)
                .eq(sectionId != null, InteractionQuestion::getSectionId, sectionId)
                .eq(InteractionQuestion::getHidden, false)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 3.收集需要的信息
        Set<Long> userIds = new HashSet<>();   // 提问者id(匿名问题不收集,需脱敏)
        Set<Long> answerIds = new HashSet<>(); // 每个问题的最新回答id(没回答则为null)
        // 3.1.从问题中取出提问者id和最新回答id
        for (InteractionQuestion q : records) {
            if (!Boolean.TRUE.equals(q.getAnonymity())) { // 匿名问题的提问者不查用户
                userIds.add(q.getUserId());
            }
            if (q.getLatestAnswerId() != null) { // 只收集"有人回答过"的最新回答id
                answerIds.add(q.getLatestAnswerId());
            }
        }
        // 3.2.按id查询最新回答,同时收集回答者id
        Map<Long, InteractionReply> replyMap = new HashMap<>(answerIds.size());
        if (CollUtils.isNotEmpty(answerIds)) {
            List<InteractionReply> replies = replyService.listByIds(answerIds);
            for (InteractionReply reply : replies) {
                replyMap.put(reply.getId(), reply);
                if (!Boolean.TRUE.equals(reply.getAnonymity())) { // 匿名回答者不查用户
                    userIds.add(reply.getUserId());
                }
            }
        }
        // 3.3.批量查询用户信息(提问者 + 回答者一次查完)
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if (CollUtils.isNotEmpty(userIds)) {
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
        }
        // 4.组装VO
        List<QuestionVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion q : records) {
            // 4.1.问题转VO(此时userId带真实值,下面统一做脱敏处理)
            QuestionVO vo = BeanUtils.copyBean(q, QuestionVO.class);
            vo.setUserId(null); // 先无条件脱敏,非匿名再补回
            // 4.2.填提问者信息(仅非匿名问题)
            if (!Boolean.TRUE.equals(q.getAnonymity())) {
                UserDTO questioner = userMap.get(q.getUserId());
                if (questioner != null) {
                    vo.setUserId(questioner.getId());
                    vo.setUserName(questioner.getName());
                    vo.setUserIcon(questioner.getIcon());
                }
            }
            // 4.3.填最新回答内容 + 回答者昵称(仅非匿名回答;没回答则跳过)
            InteractionReply reply = replyMap.get(q.getLatestAnswerId());
            if (reply != null) {
                vo.setLatestReplyContent(reply.getContent());
                if (!Boolean.TRUE.equals(reply.getAnonymity())) {
                    UserDTO answerer = userMap.get(reply.getUserId());
                    if (answerer != null) {
                        vo.setLatestReplyUser(answerer.getName());
                    }
                }
            }
            voList.add(vo);
        }
        return PageDTO.of(page, voList);
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

    @Override
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query) {
        // 1.处理课程名称搜索:按课程名去搜索服务查出课程id集合
        List<Long> courseIds = null;
        if (StringUtils.isNotBlank(query.getCourseName())) {
            courseIds = searchClient.queryCoursesIdByName(query.getCourseName());
            if (CollUtils.isEmpty(courseIds)) {
                return PageDTO.empty(0L, 0L);
            }
        }
        // 2.按条件分页查询问题(管理端:不过滤hidden,能看到被隐藏的)
        //    条件:课程id(如有)、状态(0-未查看/1-已查看)、提问时间区间
        Integer status = query.getStatus();
        Page<InteractionQuestion> page = lambdaQuery()
                .in(courseIds != null, InteractionQuestion::getCourseId, courseIds)
                .eq(status != null, InteractionQuestion::getStatus, status)
                .gt(query.getBeginTime() != null, InteractionQuestion::getCreateTime, query.getBeginTime())
                .lt(query.getEndTime() != null, InteractionQuestion::getCreateTime, query.getEndTime())
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 3.准备VO需要的数据id:提问者id、课程id、章id、节id
        Set<Long> userIds = new HashSet<>();
        Set<Long> courseIdSet = new HashSet<>();
        Set<Long> cataIds = new HashSet<>();
        for (InteractionQuestion q : records) {
            userIds.add(q.getUserId());
            courseIdSet.add(q.getCourseId());
            cataIds.add(q.getChapterId());
            cataIds.add(q.getSectionId());
        }
        // 3.1.查提问者信息
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if (CollUtils.isNotEmpty(userIds)) {
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u));
        }
        // 3.2.查课程信息(课程名 + 分类id,再经CategoryCache拼分类名)
        Map<Long, CourseSimpleInfoDTO> courseMap = new HashMap<>(courseIdSet.size());
        if (CollUtils.isNotEmpty(courseIdSet)) {
            List<CourseSimpleInfoDTO> courses = courseClient.getSimpleInfoList(courseIdSet);
            courseMap = courses.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
        }
        // 3.3.查章/节名称
        Map<Long, String> cataMap = new HashMap<>(cataIds.size());
        if (CollUtils.isNotEmpty(cataIds)) {
            List<CataSimpleInfoDTO> catas = catalogueClient.batchQueryCatalogue(cataIds);
            cataMap = catas.stream()
                    .collect(Collectors.toMap(CataSimpleInfoDTO::getId, CataSimpleInfoDTO::getName));
        }
        // 4.组装VO
        List<QuestionAdminVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion q : records) {
            // 4.1.问题转VO
            QuestionAdminVO vo = BeanUtils.copyBean(q, QuestionAdminVO.class);
            voList.add(vo);
            // 4.2.提问者昵称
            UserDTO user = userMap.get(q.getUserId());
            if (user != null) {
                vo.setUserName(user.getName());
            }
            // 4.3.课程名 + 分类名
            CourseSimpleInfoDTO course = courseMap.get(q.getCourseId());
            if (course != null) {
                vo.setCourseName(course.getName());
                vo.setCategoryName(categoryCache.getCategoryNames(course.getCategoryIds()));
            }
            // 4.4.章名、节名(查不到的给空串,避免null)
            vo.setChapterName(cataMap.getOrDefault(q.getChapterId(), ""));
            vo.setSectionName(cataMap.getOrDefault(q.getSectionId(), ""));
        }
        return PageDTO.of(page, voList);
    }
}