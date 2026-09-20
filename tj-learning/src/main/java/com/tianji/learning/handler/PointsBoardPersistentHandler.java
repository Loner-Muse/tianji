package com.tianji.learning.handler;

import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.service.IPointsRecordService;
import com.tianji.learning.utils.TableInfoContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static com.tianji.learning.constants.LearningConstants.POINTS_BOARD_TABLE_PREFIX;
import static com.tianji.learning.constants.LearningConstants.POINTS_RECORD_TABLE_PREFIX;

/**
 * <p>
 * 学霸天梯榜 历史数据持久化任务
 * </p>
 * 五个任务按「建表 -> 持久化/迁移 -> 清理缓存」顺序执行，用错开的 cron 表达式保证先后顺序：
 * 03:00 建榜单表、03:10 建积分明细表、03:30 榜单持久化、03:40 积分明细迁移、04:00 清理 Redis。
 * 改用 XXL-JOB 后可换成子任务链。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsBoardPersistentHandler {

    private final IPointsBoardSeasonService seasonService;

    private final IPointsBoardService pointsBoardService;

    private final IPointsRecordService pointsRecordService;

    private final StringRedisTemplate redisTemplate;

    /**
     * 1.每月1号凌晨3点：为上赛季创建榜单表
     */
    @Scheduled(cron = "0 0 3 1 * ?")
    public void createPointsBoardTableOfLastSeason() {
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.查询上赛季id
        Integer season = seasonService.querySeasonByTime(time);
        if (season == null) {
            log.warn("上月[{}]没有对应赛季，跳过建表", time);
            return;
        }
        // 3.创建表
        pointsBoardService.createPointsBoardTableBySeason(season);
        log.info("赛季[{}]榜单表创建完成：{}", season, POINTS_BOARD_TABLE_PREFIX + season);
    }

    /**
     * 2.每月1号凌晨3点10：为上赛季创建积分明细表 points_record_{赛季id}
     */
    @Scheduled(cron = "0 10 3 1 * ?")
    public void createPointsRecordTableOfLastSeason() {
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.查询上赛季id
        Integer season = seasonService.querySeasonByTime(time);
        if (season == null) {
            log.warn("上月[{}]没有对应赛季，跳过积分明细建表", time);
            return;
        }
        // 3.创建表
        pointsRecordService.createPointsRecordTableBySeason(season);
        log.info("赛季[{}]积分明细表创建完成：{}", season, POINTS_RECORD_TABLE_PREFIX + season);
    }

    /**
     * 3.每月1号凌晨3点30：把上赛季 Redis 榜单持久化到数据库
     */
    @Scheduled(cron = "0 30 3 1 * ?")
    public void savePointsBoard2DB() {
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.查询上赛季id
        Integer season = seasonService.querySeasonByTime(time);
        if (season == null) {
            log.warn("上月[{}]没有对应赛季，跳过持久化", time);
            return;
        }
        // 3.计算动态表名并存入 ThreadLocal，供 MyBatis-Plus 动态表名插件读取
        TableInfoContext.setInfo(POINTS_BOARD_TABLE_PREFIX + season);
        try {
            // 4.拼接 Redis 中上赛季榜单的 key
            String key = RedisConstants.POINTS_BOARD_KEY_PREFIX
                    + time.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
            // 5.分页读取 Redis 数据并入库
            int pageNo = 1;
            int pageSize = 1000;
            int total = 0;
            while (true) {
                List<PointsBoard> boardList =
                        pointsBoardService.queryCurrentBoardList(key, pageNo, pageSize);
                if (CollUtils.isEmpty(boardList)) {
                    break;
                }
                // 5.1.分表方案下没有 rank 字段，把名次写入 id
                boardList.forEach(b -> {
                    b.setId(b.getRank().longValue());
                    b.setRank(null);
                });
                // 5.2.批量入库
                pointsBoardService.saveBatch(boardList);
                total += boardList.size();
                pageNo++;
            }
            log.info("赛季[{}]榜单持久化完成，共 {} 条", season, total);
        } finally {
            // 6.务必清理 ThreadLocal，避免线程复用导致串表
            TableInfoContext.remove();
        }
    }

    /**
     * 4.每月1号凌晨3点40：把上赛季的积分明细从 points_record 迁移到 points_record_{赛季id}
     * <p>
     * 迁移区间取赛季表里的起止日期，左闭右开。
     * 具体迁移逻辑（取数 -> 写入分表 -> 删除原表）由 service 内部完成。
     */
    @Scheduled(cron = "0 40 3 1 * ?")
    public void migratePointsRecordOfLastSeason() {
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.查询上赛季id
        Integer season = seasonService.querySeasonByTime(time);
        if (season == null) {
            log.warn("上月[{}]没有对应赛季，跳过积分明细迁移", time);
            return;
        }
        // 3.查出赛季信息，拿到起止日期
        PointsBoardSeason seasonInfo = seasonService.getById(season);
        if (seasonInfo == null || seasonInfo.getBeginTime() == null || seasonInfo.getEndTime() == null) {
            log.warn("赛季[{}]的起止时间不完整，跳过积分明细迁移", season);
            return;
        }
        // 4.左闭右开区间：begin <= create_time < end，
        //   用 endTime+1 天的零点作为右边界，避免 23:59:59 的精度问题
        LocalDateTime begin = seasonInfo.getBeginTime().atStartOfDay();
        LocalDateTime end = seasonInfo.getEndTime().plusDays(1).atStartOfDay();
        // 5.迁移
        int total = pointsRecordService.migratePointsRecordBySeason(season, begin, end);
        log.info("赛季[{}]积分明细迁移完成，共 {} 条", season, total);
    }

    /**
     * 5.每月1号凌晨4点：清理 Redis 中上赛季榜单，释放内存
     */
    @Scheduled(cron = "0 0 4 1 * ?")
    public void clearPointsBoardFromRedis() {
        // 1.获取上月时间
        LocalDateTime time = LocalDateTime.now().minusMonths(1);
        // 2.拼接 key
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX
                + time.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);
        // 3.删除（大 key 用 unlink 异步释放）
        redisTemplate.unlink(key);
        log.info("历史榜单缓存已清理：{}", key);
    }
}
