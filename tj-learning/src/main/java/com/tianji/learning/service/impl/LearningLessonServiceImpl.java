package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.IdAndNumDTO;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.domain.query.PageQuery;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.enums.LessonStatus;
import com.tianji.learning.domain.enums.PlanStatus;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.domain.vo.LearningLessonVO;
import com.tianji.learning.domain.vo.LearningPlanPageVO;
import com.tianji.learning.domain.vo.LearningPlanVO;
import com.tianji.learning.mapper.LearningLessonMapper;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 学生课程表 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-08-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningLessonServiceImpl extends ServiceImpl<LearningLessonMapper, LearningLesson> implements ILearningLessonService {

    private final CourseClient courseClient;
    private final CatalogueClient catalogueClient;
    private final LearningRecordMapper recordMapper;

    /**
     * 批量给用户添加课程到课表(支付成功/报名后调用)
     * <p>远程查课程有效期,逐门生成课表记录(含过期时间),批量入库</p>
     *
     * @param userId   用户id
     * @param courseId 课程id列表
     */
    @Override
    @Transactional
    public void addUserLessons(Long userId, List<Long> courseId) {
        List<CourseSimpleInfoDTO> simpleInfoList = courseClient.getSimpleInfoList(courseId);
        if(simpleInfoList.isEmpty()){
            log.error("课程不存在");
        }
        List<LearningLesson> learningLessons=new ArrayList<>(simpleInfoList.size());
        for(CourseSimpleInfoDTO simpleInfoDTO:simpleInfoList){
            LearningLesson learningLesson = new LearningLesson();
            LocalDateTime now = LocalDateTime.now();
            Integer validDuration = simpleInfoDTO.getValidDuration();
            if(validDuration!=null&&validDuration>0){

                learningLesson.setCreateTime(now);
                learningLesson.setExpireTime(now.plusMonths(validDuration));

            }
            learningLesson.setUserId(userId);
            learningLesson.setCourseId(simpleInfoDTO.getId());
            learningLessons.add(learningLesson);
        }
        saveBatch(learningLessons);
    }

    /**
     * 分页查询我的课表
     * <p>按最近学习时间倒序分页,再远程查课程信息补齐课程名/封面/章节数</p>
     *
     * @param query 分页参数
     * @return 课表分页结果
     */
    @Override
    public PageDTO<LearningLessonVO> queryMyLessonPage(PageQuery query) {
        //1.获取用户id
        Long userId = UserContext.getUser();
        //2.根据用户id查询课程列表
        Page<LearningLesson> page = lambdaQuery().eq(LearningLesson::getUserId, userId).page(query.toMpPage("latest_learn_time", false));
        List<LearningLesson> list = page.getRecords();
        if (list.isEmpty()) {
            return PageDTO.of(page, new ArrayList<>());   // 直接返回空分页
        }
        //使用stream流获取课程id
        List<Long> courseIdList = list.stream().map(LearningLesson::getCourseId).collect(Collectors.toList());
        if(courseIdList.isEmpty()){
            throw new IllegalArgumentException("用户没有课程");
        }

        //3.根据课程id查询课程信息
        List<CourseSimpleInfoDTO> simpleInfoList = courseClient.getSimpleInfoList(courseIdList);
        if(simpleInfoList.isEmpty()){
            log.error("课程不存在");
        }
        //使用stream流将课程消息转换为map，key为课程id，value为课程信息
        Map<Long, CourseSimpleInfoDTO> courseMap = simpleInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, Function.identity()));
        //4.封装vo列表
        List<LearningLessonVO> voLessonList = new ArrayList<>(list.size());
        for(LearningLesson learningLesson:list){
            LearningLessonVO lessonVO = new LearningLessonVO();
            BeanUtils.copyProperties(learningLesson, lessonVO);
            //查询课程消息(注意:极少数下架课程可能查不到,不做强制绑定)
            CourseSimpleInfoDTO courseSimpleInfoDTO = courseMap.get(learningLesson.getCourseId());
            if(courseSimpleInfoDTO != null){
                //封装课程消息:DTO字段名(name/coverUrl/sectionNum)与VO(courseName/courseCoverUrl/sections)不一致,
                //copyProperties按字段名匹配拷贝不到,必须手动set
                lessonVO.setCourseName(courseSimpleInfoDTO.getName());
                lessonVO.setCourseCoverUrl(courseSimpleInfoDTO.getCoverUrl());
                lessonVO.setSections(courseSimpleInfoDTO.getSectionNum());
            }
            voLessonList.add(lessonVO);
        }
        return PageDTO.of(page, voLessonList);
    }

    /**
     * 查询我正在学习的课程
     * <p>取课表中"学习中 + 最近学习时间最新"的一条,再远程补课程名/封面/章节数、
     * 统计课表总数、查最近学习小节信息</p>
     *
     * @return 正在学习的课程信息;没有返回 null
     */
    @Override
    public LearningLessonVO queryNowLesson() {
        //1.获取用户id
        Long userId = UserContext.getUser();
        //健壮性校验
        if(userId==null){
            throw new IllegalArgumentException("用户id不能为空");
        }
        //查询该用户正在学习的课程
        LearningLesson lesson = lambdaQuery().eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getStatus, LessonStatus.LEARNING.getValue())
                .orderByDesc(LearningLesson::getLatestLearnTime)
                .last("limit 1")
                .one();
        if(lesson==null){
            return null;
        }
        LearningLessonVO lessonVO = new LearningLessonVO();
        BeanUtils.copyProperties(lesson,lessonVO);
        //获取用户的课程id，根据课程id查询课程相关内容
        CourseFullInfoDTO courseInfoById = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        //将课程相关内容封装到vo对象
        if (courseInfoById != null) {
            lessonVO.setCourseName(courseInfoById.getName());
            lessonVO.setCourseCoverUrl(courseInfoById.getCoverUrl());
            lessonVO.setSections(courseInfoById.getSectionNum());
        }
        //查询用户的课程总数
        Long count = lambdaQuery().eq(LearningLesson::getUserId,userId)
                .count();
        lessonVO.setCourseAmount(count != null ? count.intValue() : 0);
        //根据最近章节的id查询章节详情
        if (lesson.getLatestSectionId() != null) {
            List<CataSimpleInfoDTO> cataSimpleInfoDTOS = catalogueClient.batchQueryCatalogue(
                    CollUtils.singletonList(lesson.getLatestSectionId()));

            if (!cataSimpleInfoDTOS.isEmpty()) {
                CataSimpleInfoDTO cataSimpleInfoDTO = cataSimpleInfoDTOS.get(0);
                lessonVO.setLatestSectionName(cataSimpleInfoDTO.getName());
                lessonVO.setLatestSectionIndex(cataSimpleInfoDTO.getCIndex());
            }
        }

        //
        return lessonVO;
    }

    /**
     * 校验指定课程是否有效(是否报名/可学),供其他微服务Feign调用
     *
     * @param courseId 课程id
     * @return 已报名返回课表lessonId,否则返回 null
     */
    @Override
    public Long isLessonValid(Long courseId) {
        //1.获取用户id
        Long userId = UserContext.getUser();
        //2.根据用户id和课程id查询课程
        LearningLesson lesson = lambdaQuery().eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if(lesson==null){
            return null;
        }
        //3.判断课程状态是否为学习中,不是则视为课程无效
        if(lesson.getStatus() != LessonStatus.LEARNING){
            return null;
        }
        return lesson.getId();
    }

    /**
     * 查询用户课表中指定课程的学习信息
     *
     * @param courseId 课程id
     * @return 课表记录对应的DTO;未报名返回 null
     */
    @Override
    public LearningLessonDTO queryLesson(Long courseId) {
        //1.获取用户id
        Long userId = UserContext.getUser();
        //根据用户id和课程id查询课程学习课程
        LearningLesson lesson = lambdaQuery().eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if(lesson==null){
            return null;
        }
        //2.封装vo对象
        LearningLessonDTO lessonDTO = new LearningLessonDTO();
        BeanUtils.copyProperties(lesson, lessonDTO);

        return lessonDTO;
    }

    /**
     * 统计课程的报名学习人数(供其他微服务Feign调用)
     *
     * @param courseId 课程id
     * @return 该课程的报名学习人数
     */
    @Override
    public Integer countLearningLessonByCourse(Long courseId) {
        //1.根据课程id查询课程学习课程
        Integer count = lambdaQuery().eq(LearningLesson::getCourseId, courseId)
                .count();
        return count;
    }

    /**
     * 创建学习计划
     * <p>给课表中的指定课程设置每周学习频率;若原本没有计划,置为"计划进行中"</p>
     *
     * @param courseId 课程id
     * @param freq     每周学习频率
     */
    @Override
    public void createLearningPlan(Long courseId, Integer freq) {
        //1.获取用户id
        Long userId = UserContext.getUser();
        //健壮性校验
        if(userId==null){
            throw new IllegalArgumentException("用户id不能为空");
        }

        //根据用户id和课程id查询课程学习课程
        LearningLesson lesson = lambdaQuery().eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if(lesson==null){
            throw new IllegalArgumentException("课程学习课程不存在");
        }
        //设置学习频率(是否为首次创建计划,决定是否要把计划状态置为"计划进行中")
        if(lesson.getPlanStatus() != PlanStatus.PLAN_RUNNING){
            lesson.setPlanStatus(PlanStatus.PLAN_RUNNING);
        }
        lesson.setWeekFreq(freq);
        //一次update完成:状态(如果需要) + 频率
        updateById(lesson);
    }

    
    /**
     * 分页查询我的学习计划
     * <p>实现思路:</p>
     * <p>1.先统计本周总的学习数据:本周实际学习小节数 + 本周计划学习总节数;</p>
     * <p>2.再分页查"计划进行中"的课表(按最近学习时间倒序);</p>
     * <p>3.远程查询课程信息 + 统计每门课本周已学习小节数,组装成VO返回</p>
     *
     * @param query 分页参数
     * @return 学习计划分页结果(含本周统计数据)
     */
    @Override
    public LearningPlanPageVO queryMyLessonPagePlan(PageQuery query) {
        LearningPlanPageVO result = new LearningPlanPageVO();
        // 1.获取当前登录用户
        Long userId = UserContext.getUser();
        // 2.获取本周起始时间
        LocalDate now = LocalDate.now();
        LocalDateTime begin = DateUtils.getWeekBeginTime(now);
        LocalDateTime end = DateUtils.getWeekEndTime(now);
        // 3.查询总的统计数据
        // 3.1.本周总的已学习小节数量
        Long weekFinished = recordMapper.selectCount(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getUserId, userId)
                .eq(LearningRecord::getFinished, true)
                .gt(LearningRecord::getFinishTime, begin)
                .lt(LearningRecord::getFinishTime, end));
        result.setWeekFinished(weekFinished != null ? weekFinished.intValue() : 0);
        // 3.2.本周总的计划学习小节数量(SUM无数据时MySQL返回NULL,兜底为0)
        Integer weekTotalPlan = getBaseMapper().queryTotalPlan(userId);
        result.setWeekTotalPlan(weekTotalPlan == null ? 0 : weekTotalPlan);
        // 4.查询分页数据
        // 4.1.分页查询"有学习计划"的课表,按最近学习时间倒序
        Page<LearningLesson> p = lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getPlanStatus, PlanStatus.PLAN_RUNNING)
                .in(LearningLesson::getStatus, LessonStatus.NOT_BEGIN, LessonStatus.LEARNING)
                .page(query.toMpPage("latest_learn_time", false));
        List<LearningLesson> records = p.getRecords();
        if (CollUtils.isEmpty(records)) {
            return result.pageInfo(PageDTO.of(p, new ArrayList<LearningPlanVO>()));
        }
        // 4.2.查询课表对应的课程信息
        Map<Long, CourseSimpleInfoDTO> cMap = queryCourseSimpleInfoList(records);
        // 4.3.统计每一门课本周已学习小节数量
        List<IdAndNumDTO> list = recordMapper.countLearnedSections(userId, begin, end);
        Map<Long, Integer> countMap = IdAndNumDTO.toMap(list);
        // 4.4.组装数据VO
        List<LearningPlanVO> voList = new ArrayList<>(records.size());
        for (LearningLesson r : records) {
            // 4.4.1.拷贝基础属性到vo
            LearningPlanVO vo = new LearningPlanVO();
            BeanUtils.copyProperties(r, vo);
            // 4.4.2.填充课程详细信息
            CourseSimpleInfoDTO cInfo = cMap.get(r.getCourseId());
            if (cInfo != null) {
                vo.setCourseName(cInfo.getName());
                vo.setSections(cInfo.getSectionNum());
            }
            // 4.4.3.每门课的本周已学习小节数量
            vo.setWeekLearnedSections(countMap.getOrDefault(r.getId(), 0));
            voList.add(vo);
        }
        return result.pageInfo(p.getTotal(), p.getPages(), voList);
    }

    /**
     * 批量查询课程信息并组装为 Map(courseId -> 课程信息),供页面填充课程名/章节数
     *
     * @param records 课表记录列表
     * @return key为课程id、value为课程简要信息的 Map
     */
    private Map<Long, CourseSimpleInfoDTO> queryCourseSimpleInfoList(List<LearningLesson> records) {
        // 1.收集课程id
        Set<Long> cIds = records.stream().map(LearningLesson::getCourseId).collect(Collectors.toSet());
        // 2.远程查询课程信息
        List<CourseSimpleInfoDTO> cInfoList = courseClient.getSimpleInfoList(cIds);
        if (CollUtils.isEmpty(cInfoList)) {
            // 课程不存在
            throw new IllegalArgumentException("课程信息不存在！");
        }
        // 3.转成 Map:key=courseId, value=课程信息
        return cInfoList.stream().collect(Collectors.toMap(CourseSimpleInfoDTO::getId, c -> c));
    }

}
