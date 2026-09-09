package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.tianji.common.utils.DateUtils;
import com.tianji.learning.domain.enums.PointsRecordType;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.mapper.PointsRecordMapper;
import com.tianji.learning.service.IPointsRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-08
 */
@Service
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsRecordService {

    @Override
    public void addPointsRecord(Long userId, int points, PointsRecordType pointsRecordType) {
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        int maxPoints = pointsRecordType.getMaxPoints();

        LocalDateTime begin = DateUtils.getDayStartTime(now);
        LocalDateTime end = DateUtils.getDayEndTime(now);
        //判断积分是否有上限

        if (maxPoints > 0) {
            //判断是否超过最大积分
            //获取用户当前积分
            Integer currentPoints = GetpointByData(userId, pointsRecordType, begin, end);
            if (currentPoints  >= maxPoints) {
                return;
            }
            
        }
        // 3.没有，直接保存积分记录
        PointsRecord p = new PointsRecord();
        p.setPoints(points);
        p.setUserId(userId);
        p.setType(pointsRecordType.getValue());
        save(p);
    }

    private Integer GetpointByData(Long userId, PointsRecordType pointsRecordType, LocalDateTime begin, LocalDateTime end) {
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(PointsRecord::getUserId, userId)
                .eq(PointsRecord::getType, pointsRecordType.getValue())
                .between(begin != null && end != null, PointsRecord::getCreateTime, begin, end);
        Integer points = getBaseMapper().queryUserPointsByTypeAndDate(wrapper);
        return points == null ? 0 : points;
    }
}