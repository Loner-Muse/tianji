package com.tianji.learning.service;

import com.tianji.learning.domain.po.PointsBoard;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.learning.domain.query.PointsBoardQuery;
import com.tianji.learning.domain.vo.PointsBoardVO;

import java.util.List;

/**
 * <p>
 * 学霸天梯榜 服务类
 * </p>
 *
 * @author author
 * @since 2026-09-08
 */
public interface IPointsBoardService extends IService<PointsBoard> {

    /**
     * 查询积分榜
     *
     * @param query 查询条件，season为null或0代表当前赛季（实时榜）
     */
    PointsBoardVO queryPointsBoard(PointsBoardQuery query);

    /**
     * 为指定赛季创建榜单表
     *
     * @param season 赛季id，表名为 points_board_{赛季id}
     */
    void createPointsBoardTableBySeason(Integer season);

    /**
     * 从 Redis 实时榜单中分页查询榜单数据（积分降序）
     *
     * @param key      榜单 key，形如 boards:202608
     * @param pageNo   页码
     * @param pageSize 页大小
     * @return 榜单数据，排名从 1 开始
     */
    List<PointsBoard> queryCurrentBoardList(String key, int pageNo, int pageSize);
}
