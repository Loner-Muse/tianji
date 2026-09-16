package com.tianji.learning.mapper;

import com.tianji.learning.domain.po.PointsBoard;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 学霸天梯榜 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-09-08
 */
public interface PointsBoardMapper extends BaseMapper<PointsBoard> {

    /**
     * 当前赛季实时榜：按用户汇总积分，取前100名
     */
    @Select("SELECT user_id, SUM(points) AS points FROM points_record GROUP BY user_id ORDER BY points DESC LIMIT 100")
    List<PointsBoard> queryCurrentBoard();
    void createPointsBoardTable(@Param("tableName") String tableName);
}
