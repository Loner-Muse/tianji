package com.tianji.learning.service.impl;

import com.tianji.common.utils.BeanUtils;
import com.tianji.learning.domain.po.PointsBoardSeason;
import com.tianji.learning.domain.vo.PointsBoardSeasonVO;
import com.tianji.learning.mapper.PointsBoardSeasonMapper;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-08
 */
@Service
public class PointsBoardSeasonServiceImpl extends ServiceImpl<PointsBoardSeasonMapper, PointsBoardSeason> implements IPointsBoardSeasonService {

    @Override
    public List<PointsBoardSeasonVO> getPointsBoardSeason() {
        // 1.查询所有赛季（按开始时间排序，先开的在前）
        List<PointsBoardSeason> list = lambdaQuery()
                .orderByAsc(PointsBoardSeason::getBeginTime)
                .list();
        // 2.转VO返回
        return BeanUtils.copyList(list, PointsBoardSeasonVO.class);
    }

    @Override
    public Integer querySeasonByTime(LocalDateTime time) {
        return lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, time)
                .ge(PointsBoardSeason::getEndTime, time)
                .oneOpt().map(PointsBoardSeason::getId).orElse(null);
    }
}
