package com.tianji.learning.service.impl;

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
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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

    /**
     * 查询指定课程的学习记录
     * <p>通过 用户id+课程id 定位课表(校验是否报名),再用课表id查出该课名下
     * 每个小节的学习记录(观看进度、完成状态),供播放器恢复进度用</p>
     *
     * @param courseId 课程id
     * @return 课表进度信息(含各小节学习记录);未报名返回 null
     */
    @Override
    public LearningLessonDTO queryLesson(Long courseId) {

        //1.获取用户id

        Long userId = UserContext.getUser();
        //2.根据用户id和课程id查询课表,校验是否报名了该课程=
        LearningLesson lesson = lessonService.lambdaQuery()
                .eq(LearningLesson::getUserId, userId)
                .eq(LearningLesson::getCourseId, courseId)
                .one();
        if (lesson == null) {
            // 未报名该课程,无学习记录,直接返回空
            return null;
        }
        //3.根据课表id查询学习记录
        List<LearningRecord> records = lambdaQuery()
                .eq(LearningRecord::getLessonId, lesson.getId())
                .list();
        //4.封装结果:课表id + 最近学习小节id + 各小节学习记录
        LearningLessonDTO dto = new LearningLessonDTO();
        dto.setId(lesson.getId());
        dto.setLatestSectionId(lesson.getLatestSectionId());
        dto.setRecords(BeanUtils.copyList(records, LearningRecordDTO.class));
        return dto;
    }

    /**
     * 提交学习记录
     * <p>按小节类型分流处理:视频走进度累计,考试提交即算完成;
     * 最后根据是否新完成小节统一刷新课表进度(状态/已学节数/最近学习)。
     * 事务保证"写记录 + 改课表"要么都成、要么都回滚</p>
     *
     * @param formDTO 学习记录表单(sectionType/lessonId/sectionId/moment/commitTime)
     */
    @Override
    @Transactional
    public void addLearningRecord(LearningRecordFormDTO formDTO) {
        //1.获取用户id
        Long userId = UserContext.getUser();
        boolean finished=false;
        //2.判断学习记录类型
        if(formDTO.getSectionType()==SectionType.VIDEO){
            //视频学习记录
            finished=video(formDTO,userId);
        }else{
            //考试学习记录
            finished=exam(formDTO,userId);
        }
        //3.根据是否完成,更新课表状态
        updaterecord(formDTO,finished);
    }

    /**
     * 根据"是否新完成了某小节"更新课表状态
     * <ul>
     *   <li>从"未学习"第一次产生进度 → 课表状态改为"学习中"</li>
     *   <li>本节课全部小节学完 → 课表状态改为"已学完"</li>
     *   <li>新完成一节 → 已学小节数 +1;未完成时更新最近学习小节/时间</li>
     * </ul>
     *
     * @param recordDTO 学习记录表单(取课表id/小节id/提交时间)
     * @param finished  本次提交是否新完成了某小节
     */
    private void updaterecord(LearningRecordFormDTO recordDTO, boolean finished) {
        // 1.查询课表
        LearningLesson lesson = lessonService.getById(recordDTO.getLessonId());
        if (lesson == null) {
            throw new BizIllegalException("课程不存在，无法更新数据！");
        }
        // 2.判断是否有新的完成小节
        boolean allLearned = false;
        if(finished){
            // 3.如果有新完成的小节，则需要查询课程数据
            CourseFullInfoDTO cInfo = courseClient.getCourseInfoById(lesson.getCourseId(), false, false);
            if (cInfo == null) {
                throw new BizIllegalException("课程不存在，无法更新数据！");
            }
            // 4.比较课程是否全部学完：已学习小节 >= 课程总小节
            allLearned = lesson.getLearnedSections() + 1 >= cInfo.getSectionNum();
        }
        // 5.更新课表
        lessonService.lambdaUpdate()
                .set(lesson.getLearnedSections() == 0, LearningLesson::getStatus, LessonStatus.LEARNING.getValue())
                .set(allLearned, LearningLesson::getStatus, LessonStatus.FINISHED.getValue())
                .set(!finished, LearningLesson::getLatestSectionId, recordDTO.getSectionId())
                .set(!finished, LearningLesson::getLatestLearnTime, recordDTO.getCommitTime())
                .setSql(finished, "learned_sections = learned_sections + 1")
                .eq(LearningLesson::getId, lesson.getId())
                .update();
    }

    /**
     * 处理考试型学习记录:考试提交即完成
     *
     * @param formDTO 学习记录表单
     * @param userId  用户id
     * @return 恒为 true(考试已交卷 = 该小节学完)
     */
    private boolean exam(LearningRecordFormDTO formDTO, Long userId) {
        //考试学习记录
        LearningRecord record = BeanUtils.copyBean(formDTO, LearningRecord.class);
        record.setFinished(true);
        record.setFinishTime(formDTO.getCommitTime());
        record.setUserId(userId);
        boolean success = saveOrUpdate(record);
        if(!success){
            throw new DbException("添加学习记录失败");
        }
        return true;
    }

    /**
     * 处理视频型学习记录
     * <ul>
     *   <li>该小节第一次提交 → 新增记录,只存进度,不算完成</li>
     *   <li>再次提交 → 更新观看时长;若之前未完成且本次观看时长已达视频总长 → 标记完成</li>
     * </ul>
     *
     * @param formDTO 学习记录表单(moment:当前观看秒数、duration:视频总长)
     * @param userId  用户id
     * @return 是否本次刚完成该小节(用于判断是否需要给课表+1)
     */
    private boolean video(LearningRecordFormDTO formDTO, Long userId) {
        //查询是否有学习记录
        LearningRecord record = lambdaQuery()
                .eq(LearningRecord::getLessonId, formDTO.getLessonId())
                .eq(LearningRecord::getSectionId, formDTO.getSectionId())
                .one();
        if(record==null){
            // 第一次看该小节:只记录观看进度,不算学完(和参考工程一致)
            record = BeanUtils.copyBean(formDTO, LearningRecord.class);
            record.setUserId(userId);
            boolean success = saveOrUpdate(record);
            if(!success){
                throw new DbException("添加学习记录失败");
            }
            return false;
        }
        boolean finished = !record.getFinished()&&record.getMoment()>=formDTO.getDuration();
        boolean success = lambdaUpdate()
                .set(LearningRecord::getMoment, formDTO.getMoment())
                .set(finished, LearningRecord::getFinished, true)
                .set(finished, LearningRecord::getFinishTime, formDTO.getCommitTime())
                .eq(LearningRecord::getId, record.getId())
                .update();
        if(!success){
            throw new DbException("更新学习记录失败！");
        }
        return finished;
    }
}
