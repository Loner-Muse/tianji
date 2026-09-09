package com.tianji.learning.controller;


import com.tianji.learning.domain.vo.PointsBoardSeasonVO;
import com.tianji.learning.service.IPointsBoardSeasonService;
import com.tianji.learning.service.impl.PointsBoardSeasonServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author author
 * @since 2026-09-08
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/points-boards-seasons")
public class PointsBoardSeasonController {
    private final IPointsBoardSeasonService pointsBoardSeasonService;
    public List<PointsBoardSeasonVO> getPointsBoardSeason() {
        return pointsBoardSeasonService.getPointsBoardSeason();
    }
}
