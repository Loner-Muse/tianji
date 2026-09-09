package com.tianji.learning.service;

import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.enums.PointsRecordType;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.query.PointsRecordQuery;
import com.tianji.learning.domain.vo.PointsRecordVO;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.baomidou.mybatisplus.extension.service.IService;

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
}
