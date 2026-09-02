package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.dto.course.CourseFullInfoDTO;
import com.tianji.api.dto.leanring.LearningLessonDTO;
import com.tianji.api.dto.leanring.LearningRecordDTO;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.exceptions.DbException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.LearningRecordFormDTO;
import com.tianji.learning.domain.enums.LessonStatus;
import com.tianji.learning.domain.enums.SectionType;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import com.tianji.learning.service.ILearningRecordService;
import com.tianji.learning.utils.LearningRecordDelayTaskHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <p>
 * 学习记录表 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-08-31
 */
@Service
@RequiredArgsConstructor
public class LearningRecordServiceImpl extends ServiceImpl<LearningRecordMapper, LearningRecord> implements ILearningRecordService {

    private final ILearningLessonService lessonService;
    private final CourseClient courseClient;
    // 延迟任务处理器:视频进度高频上报时,先写Redis缓存、停顿20秒再落库一次(防抖合并写)
    private final LearningRecordDelayTaskHandler taskHandler;

    /**
     * 查询指定课程的学习记录
     * <p>通过 用户id+课程id 定位课表(校验是否报名),再用课表id查出该课名下
     * 每个小节的学习记录(观看进度、完成状态),供播放器恢复进度用</p>
     *
     * @param courseId 课程id
     * @return 课表进度信息(含各小节学习记录);未报名该课程返回 null
     */
    @Override
    public LearningLessonDTO queryLesson(Long courseId) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();
        // 2.根据用户id和课程id查询课表,校验是否报名了该课程
        LearningLesson lesson = lessonService.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null) {
            // 未报名该课程,无学习记录,直接返回空
            return null;
        }
        // 3.根据课表id查询学习记录
        List<LearningRecord> records = lambdaQuery()
                .eq(LearningRecord::getLessonId, lesson.getId())
                .list();
        // 4.封装结果:课表id + 最近学习小节id + 各小节学习记录
        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());
        dto.setLatestSectionId(lesson.getLatestSectionId());
        dto.setRecords(BeanUtils.copyList(records, LearningRecordDTO.class));
        return dto;
    }

    /**
     * 提交学习记录
     * <p>流程:按小节类型分流处理(视频/考试)→ 得到"本次是否新学完"的结论,
     * 只有新学完才需要刷新课表进度(状态/已学节数)。事务保证写记录+改课表要么都成、要么都回滚</p>
     *
     * @param recordDTO 学习记录表单(sectionType/lessonId/sectionId/moment/commitTime)
     */
    @Override
    @Transactional
    public void addLearningRecord(LearningRecordFormDTO recordDTO) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();
        // 2.处理学习记录
        boolean finished = false;
        if (recordDTO.getSectionType() == SectionType.VIDEO) {
            // 2.1.处理视频
            finished = handleVideoRecord(userId, recordDTO);
        } else {
            // 2.2.处理考试
            finished = handleExamRecord(userId, recordDTO);
        }
        if (!finished) {
            // 没有新学完的小节,无需更新课表中的学习进度
            return;
        }
        // 3.处理课表数据
        handleLearningLessonsChanges(recordDTO);
    }

    /**
     * 处理课表数据(刚学完一个新小节后调用)
     * <p>刷新课表:从未学习→学习中、某节课全部学完→已学完、已学小节数+1</p>
     *
     * @param recordDTO 学习记录表单(取课表id)
     */
    private void handleLearningLessonsChanges(LearningRecordFormDTO recordDTO) {
        // 1.查询课表
        LearningLesson lesson = lessonService.getById(recordDTO.getLessonId());
        if (lesson == null) {
            throw new BizIllegalException("课程不存在，无法更新数据！");
        }
        // 2.查询课程详情,为了拿到课程总小节数(判断是否全部学完)
        CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
        if (cInfo == null) {
            throw new BizIllegalException("课程不存在，无法更新数据！");
        }
        // 3.比较课程是否全部学完:已学习小节(本次会+1) >= 课程总小节
        boolean allLearned = lesson.getLearnedSections() + 1 >= cInfo.getSectionNum();
        // 4.更新课表:首次学习→学习中;全部学完→已学完;已学小节数+1
        lessonService.lambdaUpdate()
                .set(lesson.getLearnedSections() == 0, LearningLesson::getStatus, LessonStatus.LEARNING.getValue())
                .set(allLearned, LearningLesson::getStatus, LessonStatus.FINISHED.getValue())
                .setSql("learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, lesson.getId())
                .update();
    }

    /**
     * 处理视频学习记录
     * <p>核心逻辑:</p>
     * <ul>
     *   <li>该小节没有记录 → 新增一条,只记录开始学习,不算完成</li>
     *   <li>已有记录 → 更新观看进度;若本次观看时长已达视频总长,标记完成</li>
     *   <li><b>未学完</b> → 丢给延迟任务处理器:进度写Redis缓存,停顿20秒再落库一次(防抖),
     *       避免高频进度上报每次都直打数据库</li>
     * </ul>
     *
     * @param userId    用户id
     * @param recordDTO 学习记录表单(moment:当前观看秒数、duration:视频总长、commitTime:提交时间)
     * @return 是否本次刚学完该小节(决定是否刷新课表)
     */
    private boolean handleVideoRecord(Long userId, LearningRecordFormDTO recordDTO) {
        // 1.查询旧的学习记录(课表id+小节id唯一定位)
        LearningRecord old = getOne(new LambdaQueryWrapper<LearningRecord>()
                .eq(LearningRecord::getLessonId, recordDTO.getLessonId())
                .eq(LearningRecord::getSectionId, recordDTO.getSectionId()));
        // 2.判断是否存在学习记录
        if (old == null) {
            // 3.不存在 → 新增一条,仅记录开始学习,不算完成
            LearningRecord record = BeanUtils.copyBean(recordDTO, LearningRecord.class);
            record.setUserId(userId);
            record.setFinished(false);
            boolean success = save(record);
            if (!success) {
                throw new DbException("新增学习记录失败");
            }
            return false;
        }
        // 4.判断本次是否学完:之前未完成 && 本次观看时长已达到视频总长
        boolean finished = !old.getFinished() && recordDTO.getMoment() >= recordDTO.getDuration();
        // 5.更新观看进度;学完时同时标记完成状态与完成时间
        boolean success = lambdaUpdate()
                .set(LearningRecord::getMoment, recordDTO.getMoment())
                .set(finished, LearningRecord::getFinished, true)
                .set(finished, LearningRecord::getFinishTime, recordDTO.getCommitTime())
                .eq(LearningRecord::getId, old.getId())
                .update();
        if (!success) {
            throw new DbException("更新学习记录失败");
        }
        // 6.未学完 → 交给延迟任务处理器做防抖落库(进度写Redis + 20秒延迟任务)
        if (!finished) {
            LearningRecord record = new LearningRecord();
            record.setId(old.getId());
            record.setLessonId(recordDTO.getLessonId());
            record.setSectionId(recordDTO.getSectionId());
            record.setMoment(recordDTO.getMoment());
            taskHandler.addLearningRecordTask(record);
        }
        return finished;
    }

    /**
     * 处理考试学习记录
     * <p>考试交卷即视为完成,直接新增一条已完成的记录</p>
     *
     * @param userId    用户id
     * @param recordDTO 学习记录表单(取课表id/小节id/提交时间)
     * @return 恒为 true(考试交卷 = 该小节学完)
     */
    private boolean handleExamRecord(Long userId, LearningRecordFormDTO recordDTO) {
        // 1.拷贝表单数据生成记录,标记完成 + 完成时间
        LearningRecord record = BeanUtils.copyBean(recordDTO, LearningRecord.class);
        record.setUserId(userId);
        record.setFinished(true);
        record.setFinishTime(recordDTO.getCommitTime());
        // 2.保存考试记录
        boolean success = save(record);
        if (!success) {
            throw new DbException("新增学习记录失败");
        }
        return true;
    }
}