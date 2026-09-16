package com.tianji.learning.controller;


import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.service.IPointsBoardService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 学霸天梯榜 前端控制器
 * </p>
 *
 * @author author
 * @since 2026-09-08
 */
@RestController
@RequestMapping("/boards")
@Api(tags = "学霸天梯榜相关接口")
@RequiredArgsConstructor
public class PointsBoardController {

    private final IPointsBoardService pointsBoardService;

    @ApiOperation("查询积分榜")
    @GetMapping
    public PointsBoardVO queryPointsBoard(PointsBoardQuery query) {
        return pointsBoardService.queryPointsBoard(query);
    }
}
