package com.tianji.learning.mapper;

import com.tianji.api.dto.IdAndNumDTO;
import com.tianji.learning.domain.po.LearningRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 学习记录表 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-08-31
 */
public interface LearningRecordMapper extends BaseMapper<LearningRecord> {

    /**
     * 统计周内【每门课】已学习的小节数量,按课表id分组
     *
     * @param userId 用户id
     * @param begin  本周起始时间
     * @param end    本周结束时间
     * @return 每门课已学习小节数(lessonId -> num)
     */
    List<IdAndNumDTO> countLearnedSections(@Param("userId") Long userId,
                                           @Param("begin") LocalDateTime begin,
                                           @Param("end") LocalDateTime end);
}
