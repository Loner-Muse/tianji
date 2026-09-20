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
import com.tianji.learning.utils.TableInfoContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.tianji.learning.constants.LearningConstants.POINTS_RECORD_TABLE_PREFIX;

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

    /**
     * 单次迁移的批大小
     */
    private static final int MIGRATE_BATCH_SIZE = 1000;

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

    @Override
    public void createPointsRecordTableBySeason(Integer season) {
        getBaseMapper().createPointsRecordTable(POINTS_RECORD_TABLE_PREFIX + season);
    }

    @Override
    public int migratePointsRecordBySeason(Integer season, LocalDateTime begin, LocalDateTime end) {
        String tableName = POINTS_RECORD_TABLE_PREFIX + season;
        int total = 0;
        while (true) {
            // 1.查询原表 points_record 中本区间的记录。
            //   这里每次固定取"从第一条开始的 N 条"，而不是 pageNo 递增：
            //   因为第 3 步会删掉已迁移的数据，后面的记录会往前补位，
            //   如果页码递增就会直接跳过数据，导致只迁了一半。
            //   先 remove 一次，确保本次查询走原表而不是残留的分表。
            TableInfoContext.remove();
            List<PointsRecord> list = lambdaQuery()
                    .ge(PointsRecord::getCreateTime, begin)
                    .lt(PointsRecord::getCreateTime, end)
                    .orderByAsc(PointsRecord::getId)
                    .last("limit " + MIGRATE_BATCH_SIZE)
                    .list();
            if (CollUtils.isEmpty(list)) {
                break;
            }
            // 2.写入历史分表：设置动态表名后，saveBatch 生成的 insert 会被替换到 points_record_{赛季id}
            TableInfoContext.setInfo(tableName);
            try {
                saveBatch(list);
            } finally {
                // 务必清理，否则后面的删除操作也会打到分表上
                TableInfoContext.remove();
            }
            // 3.删除原表已迁移的记录，避免重复迁移
            List<Long> ids = list.stream().map(PointsRecord::getId).collect(Collectors.toList());
            removeByIds(ids);
            total += list.size();
        }
        return total;
    }
}