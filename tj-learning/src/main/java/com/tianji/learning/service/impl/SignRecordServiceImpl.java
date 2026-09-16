package com.tianji.learning.service.impl;

import com.tianji.common.autoconfigure.mq.RabbitMqHelper;
import com.tianji.common.constants.MqConstants;
import com.tianji.common.exceptions.BizIllegalException;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.vo.SignRecordVO;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.mq.message.SignInMessage;
import com.tianji.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SignRecordServiceImpl implements ISignRecordService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitMqHelper mqHelper;

    @Override
    public SignResultVO signRecord() {
        // 1.获取用户id
        Long userId = UserContext.getUser();
        // 2.获取当天日期
        LocalDate today = LocalDate.now();
        // 3.拼接key
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX
                + userId
                + today.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);
        // 4.签到（setBit 返回旧值，旧值为true说明已签到过）
        int offset = today.getDayOfMonth() - 1;
        Boolean exists = stringRedisTemplate.opsForValue().setBit(key, offset, true);
        if (Boolean.TRUE.equals(exists)) {
            throw new BizIllegalException("不允许重复签到！");
        }
        // 5.查询连续签到天数
        int signDays = getContinueSignDays(key, today.getDayOfMonth());
        // 6.计算奖励积分
        int rewardPoints = 0;
        switch (signDays) {
            case 7:
                rewardPoints = 10;
                break;
            case 14:
                rewardPoints = 20;
                break;
            case 28:
                rewardPoints = 40;
                break;
        }
        // 4.保存积分明细记录
        mqHelper.send(
                MqConstants.Exchange.LEARNING_EXCHANGE,
                MqConstants.Key.SIGN_IN,
                SignInMessage.of(userId, rewardPoints + 1));// 签到积分是基本得分+奖励积分
        // 7.封装返回
        SignResultVO vo = new SignResultVO();
        vo.setSignDays(signDays);
        vo.setRewardPoints(rewardPoints);
        // signPoints 默认 1，无需显式设置
        return vo;
    }

    @Override
    public SignRecordVO getSignRecord() {
        Long userId = UserContext.getUser();
        // 2.获取当前日期
        LocalDate today = LocalDate.now();
        // 3.拼接key
        String key = RedisConstants.SIGN_RECORD_KEY_PREFIX
                + userId
                + today.format(DateUtils.SIGN_DATE_SUFFIX_FORMATTER);
        // 4.查询签到记录：从1号到今天的签到状态，正序返回（result[0]=1号）
        List<Integer> records = new ArrayList<>(today.getDayOfMonth());
        for (int i = 0; i < today.getDayOfMonth(); i++) {
            Boolean signed = stringRedisTemplate.opsForValue().getBit(key, i);
            records.add(Boolean.TRUE.equals(signed) ? 1 : 0);
        }
        // 5.封装返回：连续签到天数 + 本月签到记录
        SignRecordVO vo = new SignRecordVO();
        vo.setSignDays(getContinueSignDays(key, today.getDayOfMonth()));
        vo.setSignRecords(records.stream().map(Integer::byteValue).collect(java.util.stream.Collectors.toList()));
        return vo;
    }

    private int getContinueSignDays(String key, int dayOfMonth) {
        // 从今天(最后一位)往回数连续签到天数
        int count = 0;
        for (int i = dayOfMonth - 1; i >= 0; i--) {
            Boolean signed = stringRedisTemplate.opsForValue().getBit(key, i);
            if (Boolean.TRUE.equals(signed)) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }
}