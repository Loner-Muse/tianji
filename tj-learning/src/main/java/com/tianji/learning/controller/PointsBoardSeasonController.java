package com.tianji.learning.controller;


import com.tianji.learning.domain.vo.PointsBoardSeasonVO;
import com.tianji.learning.service.IPointsBoardSeasonService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 积分榜赛季 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-09-08
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/boards/seasons")
@Api(tags = "积分榜赛季相关接口")
public class PointsBoardSeasonController {
    private final IPointsBoardSeasonService pointsBoardSeasonService;

    @ApiOperation("查询赛季列表")
    @GetMapping("/list")
    public List<PointsBoardSeasonVO> getPointsBoardSeason() {
        return pointsBoardSeasonService.getPointsBoardSeason();
    }
}
