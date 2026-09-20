package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.enums.PointsRecordType;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.query.PointsRecordQuery;
import com.tianji.learning.domain.vo.PointsRecordVO;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-08
 */
public interface IPointsRecordService extends IService<PointsRecord> {

    void addPointsRecord(Long userId, int i, PointsRecordType pointsRecordType);

    /**
     * 查询当前用户今日获取的积分统计
     */
    List<PointsStatisticsVO> queryMyTodayPoints();

    /**
     * 分页查询当前用户积分明细
     */
    PageDTO<PointsRecordVO> queryMyPoints(PointsRecordQuery query);

    /**
     * 为指定赛季创建积分明细表，表名为 points_record_{赛季id}
     *
     * @param season 赛季id
     */
    void createPointsRecordTableBySeason(Integer season);

    /**
     * 把指定时间区间的积分明细从 points_record 迁移到 points_record_{赛季id}
     * <p>
     * 迁移完成后原表对应记录会被删除
     *
     * @param season 赛季id
     * @param begin  区间开始时间（含）
     * @param end    区间结束时间（不含）
     * @return 实际迁移的记录条数
     */
    int migratePointsRecordBySeason(Integer season, LocalDateTime begin, LocalDateTime end);
}
