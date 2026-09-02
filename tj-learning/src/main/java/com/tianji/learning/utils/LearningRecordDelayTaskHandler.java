package com.tianji.learning.utils;

import com.tianji.common.utils.JsonUtils;
import com.tianji.learning.domain.po.LearningLesson;
import com.tianji.learning.domain.po.LearningRecord;
import com.tianji.learning.mapper.LearningRecordMapper;
import com.tianji.learning.service.ILearningLessonService;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.DelayQueue;

/**
 * 学习记录延迟任务处理器(防抖合并写)
 * <p>设计思想:播放器在学习过程中会<b>高频上报</b>观看进度(每秒都可能上报),
 * 如果每次都直接写数据库,数据库压力会非常大。</p>
 * <p>这里的做法分三步:</p>
 * <ul>
 *   <li>1.进度先写入 <b>Redis 缓存</b>(写缓存毫秒级,扛住高频流量);</li>
 *   <li>2.再往 <b>延迟队列</b> 塞一个 20 秒后到期的任务;</li>
 *   <li>3.任务到期后,若这 20 秒内进度<b>没有再变</b>(用户已停住/关页面),才真正落库一次;
 *       若进度又有新上报,则跳过本次,等最新一次上报的任务落库。</li>
 * </ul>
 * <p>最终效果:高频写 Redis、低频写 DB,数据库只在用户"停顿 20 秒以上"时落一版最新进度 —— 这就是<b>防抖合并写</b>。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningRecordDelayTaskHandler {

    /** Redis 缓存 key 前缀,完整 key = 前缀 + lessonId,例如 learning:record:5 */
    private static final String REDIS_KEY_PREFIX = "learning:record:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ILearningLessonService learningLessonService;
    private final LearningRecordMapper recordMapper;

    // 延迟队列:存放待落库的学习记录任务(DelayQueue 本身线程安全)
    private final DelayQueue<DelayTask<RecordTaskData>> queue = new DelayQueue<>();

    /** 运行开关:false 时消费线程退出(应用关闭时由 destroy() 置为 false) */
    private volatile boolean running = true;

    /**
     * 添加学习记录延迟任务(进度上报入口)
     * <p>每收到一次进度上报,就调用一次本方法:</p>
     * <p>1.先把进度写入 Redis 缓存(高频写缓存,避免频繁打库);</p>
     * <p>2.再往延迟队列塞一个 20 秒后到期的任务。若 20 秒内进度又被新的上报覆盖,
     * 到期时校验发现"任务里的进度 ≠ 缓存里的最新进度",就会跳过本次落库,等最新那次上报的任务落库。</p>
     *
     * @param record 学习记录(含观看进度 moment)
     */
    public void addLearningRecordTask(LearningRecord record) {
        // 1.添加数据到Redis缓存
        writeRecordCache(record);
        // 2.提交延迟任务到延迟队列,20秒后到期
        queue.add(new DelayTask<>(new RecordTaskData(record), Duration.ofSeconds(20)));
    }

    /**
     * 将学习记录写入 Redis 缓存
     * <p>Hash 结构:key = learning:record:{lessonId},field = sectionId,value = 记录 JSON。
     * 这样按"课表id + 小节id"两个维度定位一条记录,和 readRecordCache 成对使用。</p>
     *
     * @param record 学习记录
     */
    private void writeRecordCache(LearningRecord record) {
        String json = JsonUtils.toJsonStr(new RecordCacheData(record));
        String key = REDIS_KEY_PREFIX + record.getLessonId();
        // field 用小节id(转为String,保证和读取/删除时一致)
        stringRedisTemplate.opsForHash().put(key, record.getSectionId().toString(), json);
        // 缓存1分钟自动过期:防止"任务死掉/积压"导致缓存永驻
        stringRedisTemplate.expire(key, Duration.ofMinutes(1));
    }

    /**
     * 从 Redis 缓存读取学习记录
     *
     * @param lessonId  课表id(用于拼 key)
     * @param sectionId 小节id(用于定位 field)
     * @return Redis 里的学习记录;缓存不存在/已过期返回 null
     */
    private LearningRecord readRecordCache(Long lessonId, Long sectionId) {
        String key = REDIS_KEY_PREFIX + lessonId;
        Object value = stringRedisTemplate.opsForHash().get(key, sectionId.toString());
        if (value == null) {
            return null;
        }
        // 缓存中存的是 RecordCacheData 的 JSON,转回 LearningRecord 时只有 id/moment 有值,其余字段为 null
        return JsonUtils.toBean(value.toString(), LearningRecord.class);
    }

    /**
     * 应用启动后自动运行:开启一个后台消费线程,不断从延迟队列取任务处理
     */
    @PostConstruct
    public void start() {
        Thread thread = new Thread(this::handleDelayTask, "learning-record-delay-task");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 消费延迟任务的主循环(由 start() 启动的线程执行)
     * <p>防抖落库的关键判断:任务记录的时刻(moment)与缓存中最新时刻一致,
     * 说明这 20 秒内没有新的进度上报,本次就是"最新状态",才允许落库。</p>
     */
    public void handleDelayTask() {
        while (running) {
            try {
                // 1.阻塞等待一个到期的延迟任务(队列空时线程挂起,不占CPU)
                DelayTask<RecordTaskData> task = queue.take();
                RecordTaskData data = task.getData();
                // 2.从 Redis 读回最新进度
                LearningRecord record = readRecordCache(data.getLessonId(), data.getSectionId());
                // 2.1.缓存已过期被清掉 → 放弃本次落库,等下一次上报重新走
                if (record == null) {
                    continue;
                }
                // 2.2.防抖校验失败:缓存进度 ≠ 任务时刻,说明期间又有新的上报,等最新任务落库
                if (!Objects.equals(data.getMoment(), record.getMoment())) {
                    continue;
                }
                // 3.落库学习记录:把最新 moment(观看进度)写回数据库。
                //    finished 置 null 是特意为之:MyBatis-Plus 更新时跳过 null 字段,即不动完成状态
                record.setFinished(null);
                recordMapper.updateById(record);
                // 4.同步更新课表的"最近学习小节/最近学习时间"
                learningLessonService.lambdaUpdate()
                        .eq(LearningLesson::getId, data.getLessonId())
                        .set(LearningLesson::getLatestSectionId, data.getSectionId())
                        .set(LearningLesson::getLatestLearnTime, LocalDateTime.now())
                        .update();
                // 5.落库成功后清掉缓存,避免残留脏数据
                cleanRecordCache(data.getLessonId(), data.getSectionId());
            } catch (Exception e) {
                // 单个任务异常不能拖垮整个消费线程,打日志后继续循环
                log.error("处理学习记录延迟任务异常", e);
            }
        }
    }

    /**
     * 删除 Redis 缓存中的学习记录
     * <p>key 的拼接方式必须与 writeRecordCache 保持一致,否则删不到数据。</p>
     *
     * @param lessonId  课表id
     * @param sectionId 小节id
     */
    public void cleanRecordCache(Long lessonId, Long sectionId) {
        String key = REDIS_KEY_PREFIX + lessonId;
        stringRedisTemplate.opsForHash().delete(key, sectionId.toString());
    }

    /**
     * Redis 缓存中存储的数据载体
     * <p>只缓存定位+进度三个关键字段,避免把整个实体塞进 Redis</p>
     */
    @Data
    @NoArgsConstructor
    private static class RecordCacheData {
        private Long id;
        private Integer moment;
        private Boolean finished;

        public RecordCacheData(LearningRecord record) {
            this.id = record.getId();
            this.moment = record.getMoment();
            this.finished = record.getFinished();
        }
    }

    /**
     * 延迟任务的数据载体
     * <p>记录任务创建那一刻的进度 moment,用于到期时和缓存中的最新进度做防抖比对</p>
     */
    @Data
    @NoArgsConstructor
    private static class RecordTaskData {
        private Long lessonId;
        private Long sectionId;
        private Integer moment;

        public RecordTaskData(LearningRecord record) {
            this.lessonId = record.getLessonId();
            this.sectionId = record.getSectionId();
            this.moment = record.getMoment();
        }
    }
}