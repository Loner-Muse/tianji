package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.enums.PointsRecordType;
import com.tianji.learning.domain.po.PointsRecord;
import com.tianji.learning.domain.query.PointsRecordQuery;
import com.tianji.learning.domain.vo.PointsRecordVO;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.mapper.PointsRecordMapper;
import com.tianji.learning.service.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 学习积分记录，每个月底清零 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-08
 */
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord> implements IPointsRecordService {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void addPointsRecord(Long userId, int points, PointsRecordType pointsRecordType) {
        //获取当前时间
        LocalDateTime now = LocalDateTime.now();
        int maxPoints = pointsRecordType.getMaxPoints();
        // 1.判断当前方式有没有积分上限
        int realPoints = points;

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
            // 没超过上限，但本次加分可能超上限，则截断到上限
            if (currentPoints + points > maxPoints) {
                realPoints = maxPoints - currentPoints;
            }
        }
        // 3.没有，直接保存积分记录
        PointsRecord p = new PointsRecord();
        p.setPoints(realPoints);
        p.setUserId(userId);
        p.setType(pointsRecordType.getValue());
        save(p);
        // 4.累加总积分到Redis(实时榜单),以赛季月份为key,用户id为member,积分为score
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + now.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        redisTemplate.opsForZSet().incrementScore(key, userId.toString(), realPoints);
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

    @Override
    public List<PointsStatisticsVO> queryMyTodayPoints() {
        // 1.获取当前用户
        Long userId = UserContext.getUser();
        // 2.获取今日起止时间
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime begin = DateUtils.getDayStartTime(now);
        LocalDateTime end = DateUtils.getDayEndTime(now);
        // 3.查询今日积分记录
        List<PointsRecord> records = lambdaQuery()
                .eq(PointsRecord::getUserId, userId)
                .between(PointsRecord::getCreateTime, begin, end)
                .list();
        if (CollUtils.isEmpty(records)) {
            return CollUtils.emptyList();
        }
        // 4.按积分类型分组
        Map<Integer, List<PointsRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(PointsRecord::getType));
        // 5.封装VO
        List<PointsStatisticsVO> vos = new ArrayList<>();
        grouped.forEach((type, list) -> {
            PointsRecordType recordType = PointsRecordType.of(type);
            if (recordType == null) {
                return;
            }
            PointsStatisticsVO vo = new PointsStatisticsVO();
            vo.setType(PointsStatisticsVO.PointsRecordTypeVO.of(recordType.getValue(), recordType.getDesc()));
            vo.setPoints(list.stream().map(PointsRecord::getPoints).reduce(0, Integer::sum));
            vo.setMaxPoints(recordType.getMaxPoints());
            vos.add(vo);
        });
        return vos;
    }

    @Override
    public PageDTO<PointsRecordVO> queryMyPoints(PointsRecordQuery query) {
        // 1.获取当前用户
        Long userId = UserContext.getUser();
        // 2.构造查询条件
        QueryWrapper<PointsRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(PointsRecord::getUserId, userId)
                .eq(query.getType() != null, PointsRecord::getType, query.getType())
                .orderByDesc(PointsRecord::getCreateTime);
        // 3.分页查询
        Page<PointsRecord> page = page(query.toMpPage(), wrapper);
        List<PointsRecord> records = page.getRecords();
        if (CollUtils.isEmpty(records)) {
            return PageDTO.empty(page);
        }
        // 4.封装VO
        List<PointsRecordVO> vos = new ArrayList<>(records.size());
        for (PointsRecord record : records) {
            PointsRecordVO vo = BeanUtils.copyProperties(record, PointsRecordVO.class);
            PointsRecordType type = PointsRecordType.of(record.getType());
            vo.setType(type == null ? null : type.getDesc());
            vos.add(vo);
        }
        return PageDTO.of(page, vos);
    }
}