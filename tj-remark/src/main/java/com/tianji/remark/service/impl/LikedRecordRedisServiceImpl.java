package com.tianji.remark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.dto.remark.LikedTimesDTO;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.remark.constants.RedisConstants;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.ILikedRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.tianji.common.constants.MqConstants.Exchange.LIKE_RECORD_EXCHANGE;
import static com.tianji.common.constants.MqConstants.Key.LIKED_TIMES_KEY_TEMPLATE;

/**
 * <p>
 * 点赞记录表 服务实现类(数据库版)
 * </p>
 * <p>设计思路:点赞/取消点赞直接落库 liked_record 表,
 * 操作成功后再统计该业务的最新点赞总数,通过 MQ 通知业务方更新计数。
 * (高并发场景应改用 Redis 版,见 LikedRecordServiceRedisImpl)</p>
 *
 * @author author
 * @since 2026-09-06
 */
@Service
@RequiredArgsConstructor
public class LikedRecordRedisServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {

    private final RabbitMqHelper mqHelper;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 点赞或取消点赞
     * <p>按前端传的 liked 区分:true-点赞、false-取消;只有"真的改变了状态"
     * (如首次点赞、确实取消成功)才会统计总数并发送 MQ,避免重复操作触发无效通知</p>
     *
     * @param recordDTO 点赞表单(bizId业务id / bizType业务类型 / liked是否点赞)
     */
    @Override
    public void likes(LikeRecordFormDTO recordDTO) {
        // 1.基于前端的参数,判断是执行点赞还是取消点赞
        boolean success = recordDTO.getLiked() ? like(recordDTO) : unlike(recordDTO);
        // 2.判断是否执行成功,如果失败(如重复点赞)则直接结束
        if (!success) {
            return;
        }
        // 3.统计该业务的点赞总数：统计该bizId下点赞用户数，更新到zset
        String key = RedisConstants.LIKES_BIZ_KEY_PREFIX + recordDTO.getBizId();
        Long likedTimes = stringRedisTemplate.opsForSet().size(key);
        if(likedTimes == null){
            likedTimes = 0L;
        }

        stringRedisTemplate.opsForZSet().add(
                RedisConstants.LIKES_TIMES_KEY_PREFIX + recordDTO.getBizType(),
                recordDTO.getBizId().toString(),
                likedTimes
        );

    }

    @Override
    public Set<Long> isBizLiked(List<Long> bizIds) {
        // 1.获取登录用户id
        Long userId = UserContext.getUser();
        // 2.查询点赞状态
        List<Object> objects = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection src = (StringRedisConnection) connection;
            for (Long bizId : bizIds) {
                String key = RedisConstants.LIKES_BIZ_KEY_PREFIX + bizId;
                src.sIsMember(key, userId.toString());
            }
            return null;
        });
        // 3.返回结果
        return IntStream.range(0, objects.size()) // 创建从0到集合size的流
                .filter(i -> (boolean) objects.get(i)) // 遍历每个元素，保留结果为true的角标i
                .mapToObj(bizIds::get)// 用角标i取bizIds中的对应数据，就是点赞过的id
                .collect(Collectors.toSet());// 收集
    }

    @Override
    public void readLikedTimesAndSendMessage(String bizType, int maxBizSize) {
        // 1.查询点赞总数
        String key = RedisConstants.LIKES_TIMES_KEY_PREFIX + bizType;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().popMin(key, maxBizSize);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return;
        }
        List<LikedTimesDTO> likedTimesDTOs = new ArrayList<>(typedTuples.size());
        //遍历typedTuples,将每个元素的score转换为Long
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String value = typedTuple.getValue();
            Double score = typedTuple.getScore();
            if (value == null) {
                continue;
            }
            LikedTimesDTO likedTimesDTO = new LikedTimesDTO();
            likedTimesDTO.setBizId(Long.parseLong(value));
            likedTimesDTO.setLikedTimes(score != null ? score.intValue() : 0);
            likedTimesDTOs.add(likedTimesDTO);
        }
        if (likedTimesDTOs.isEmpty()) {
            return;
        }
        // 2.发送MQ（逐个发送，避免消费端类型不匹配）
        for (LikedTimesDTO dto : likedTimesDTOs) {
            mqHelper.send(LIKE_RECORD_EXCHANGE, StringUtils.format(LIKED_TIMES_KEY_TEMPLATE, bizType), dto);
        }
    }


    /**
     * 取消点赞:删除当前用户对该业务的点赞记录
     *
     * @param recordDTO 点赞表单
     * @return true-删除成功(取消成功);false-本就没点过(无需处理)
     */
    private boolean unlike(LikeRecordFormDTO recordDTO) {
        //获取登录用户id
        Long userId = UserContext.getUser();
        //redis中查询是否已点赞过：每个bizId一个Set，存点赞用户id
        String key = RedisConstants.LIKES_BIZ_KEY_PREFIX + recordDTO.getBizId();
        Long remove = stringRedisTemplate.opsForSet().remove(key, userId.toString());

        return remove != null && remove > 0;
    }

    /**
     * 点赞:先查是否已点过,已点过返回 false 不重复赞;未点过则插入记录
     *
     * @param recordDTO 点赞表单
     * @return true-首次点赞成功;false-重复点赞
     */
    private boolean like(LikeRecordFormDTO recordDTO) {
        //获取登录用户id
        Long userId = UserContext.getUser();
        //redis中查询是否已点赞过：每个bizId一个Set，存点赞用户id
        String key = RedisConstants.LIKES_BIZ_KEY_PREFIX + recordDTO.getBizId();
        Long add = stringRedisTemplate.opsForSet().add(key, userId.toString());

        return add != null && add > 0;
    }
}
