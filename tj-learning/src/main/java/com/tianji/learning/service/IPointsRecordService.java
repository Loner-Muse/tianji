package com.tianji.learning.service;

import com.tianji.learning.domain.enums.PointsRecordType;
import com.tianji.learning.domain.po.PointsRecord;
import com.baomidou.mybatisplus.extension.service.IService;

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
}
