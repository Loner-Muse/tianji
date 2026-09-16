package com.tianji.learning.handler;

import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.IPointsBoardService;
import com.tianji.learning.utils.TableInfoContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static com.tianji.learning.constants.LearningConstants.POINTS_BOARD_TABLE_PREFIX;

/**
 * <p>
 * 学霸天梯榜 历史榜单持久化任务
 * </p>
 * 三个任务按「创建表 -> 持久化 -> 清理缓存」顺序执行，
 * 这里用错开的 cron 表达式保证先后顺序（改用 XXL-JOB 后可换成子任务链）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsBoardPersistentHandler {

    private final IPointsBoardSeasonService seasonService;

    private final IPointsBoardService pointsBoardService;

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
     * 2.每月1号凌晨3点30：把上赛季 Redis 榜单持久化到数据库
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
     * 3.每月1号凌晨4点：清理 Redis 中上赛季榜单，释放内存
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
