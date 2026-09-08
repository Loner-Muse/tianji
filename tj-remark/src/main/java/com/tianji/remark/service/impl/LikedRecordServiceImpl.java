package com.tianji.remark.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.api.dto.remark.LikedTimesDTO;
import com.tianji.remark.domain.dto.LikeRecordFormDTO;
import com.tianji.remark.domain.po.LikedRecord;
import com.tianji.remark.mapper.LikedRecordMapper;
import com.tianji.remark.service.ILikedRecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
//@Service
@RequiredArgsConstructor
public class LikedRecordServiceImpl extends ServiceImpl<LikedRecordMapper, LikedRecord> implements ILikedRecordService {

    private final RabbitMqHelper mqHelper;

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
        // 3.统计该业务的点赞总数
        Integer likedTimes = lambdaQuery()
                .eq(LikedRecord::getBizId, recordDTO.getBizId())
                .count();
        // 4.发送MQ通知,让业务方更新点赞数
        mqHelper.send(
                LIKE_RECORD_EXCHANGE,
                StringUtils.format(LIKED_TIMES_KEY_TEMPLATE, recordDTO.getBizType()),
                LikedTimesDTO.of(recordDTO.getBizId(), likedTimes));
    }

    @Override
    public Set<Long> isBizLiked(List<Long> bizIds) {
        // 1.获取登录用户id
        Long userId = UserContext.getUser();
        // 2.查询点赞状态
        List<LikedRecord> list = lambdaQuery()
                .in(LikedRecord::getBizId, bizIds)
                .eq(LikedRecord::getUserId, userId)
                .list();
        // 3.返回结果
        return list.stream().map(LikedRecord::getBizId).collect(Collectors.toSet());
    }
    /**
     * 取消点赞:删除当前用户对该业务的点赞记录
     *
     * @param recordDTO 点赞表单
     * @return true-删除成功(取消成功);false-本就没点过(无需处理)
     */
    private boolean unlike(LikeRecordFormDTO recordDTO) {
        return remove(new QueryWrapper<LikedRecord>().lambda()
                .eq(LikedRecord::getUserId, UserContext.getUser())
                .eq(LikedRecord::getBizId, recordDTO.getBizId()));
    }

    /**
     * 点赞:先查是否已点过,已点过返回 false 不重复赞;未点过则插入记录
     *
     * @param recordDTO 点赞表单
     * @return true-首次点赞成功;false-重复点赞
     */
    private boolean like(LikeRecordFormDTO recordDTO) {
        Long userId = UserContext.getUser();
        // 1.查询当前用户是否已对该业务点过赞
        Integer count = lambdaQuery()
                .eq(LikedRecord::getUserId, userId)
                .eq(LikedRecord::getBizId, recordDTO.getBizId())
                .count();
        // 2.已存在,说明重复点赞,直接返回 false
        if (count > 0) {
            return false;
        }
        // 3.不存在,插入一条点赞记录
        LikedRecord record = new LikedRecord();
        record.setUserId(userId);
        record.setBizId(recordDTO.getBizId());
        record.setBizType(recordDTO.getBizType());
        save(record);
        return true;
    }
}
