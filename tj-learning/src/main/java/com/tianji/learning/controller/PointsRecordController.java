package com.tianji.learning.controller;


import com.tianji.common.domain.dto.PageDTO;
import com.tianji.learning.domain.query.PointsRecordQuery;
import com.tianji.learning.domain.vo.PointsRecordVO;
import com.tianji.learning.domain.vo.PointsStatisticsVO;
import com.tianji.learning.service.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 学习积分记录，每个月底清零 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-09-08
 */
@RestController
@RequestMapping("/points")
@RequiredArgsConstructor
public class PointsRecordController {

    private final IPointsRecordService pointsRecordService;

    /**
     * 查询今日积分统计
     */
    @GetMapping("/today")
    public List<PointsStatisticsVO> queryMyTodayPoints() {
        return pointsRecordService.queryMyTodayPoints();
    }

    /**
     * 分页查询我的积分明细
     */
    @GetMapping("/list")
    public PageDTO<PointsRecordVO> queryMyPoints(PointsRecordQuery query) {
        return pointsRecordService.queryMyPoints(query);
    }
}
