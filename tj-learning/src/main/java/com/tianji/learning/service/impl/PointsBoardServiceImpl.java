package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.DateUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.constants.RedisConstants;
import com.tianji.learning.domain.po.PointsBoard;
import com.tianji.learning.domain.vo.PointsBoardItemVO;
import com.tianji.learning.domain.vo.PointsBoardVO;
import com.tianji.learning.mapper.PointsBoardMapper;
import com.tianji.learning.service.IPointsBoardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.BoundZSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 学霸天梯榜 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-09-08
 */
@Service
@RequiredArgsConstructor
public class PointsBoardServiceImpl extends ServiceImpl<PointsBoardMapper, PointsBoard> implements IPointsBoardService {

    private final StringRedisTemplate redisTemplate;

    private final UserClient userClient;

    @Override
    public PointsBoardVO queryPointsBoard(Integer season) {
        Long userId = UserContext.getUser();
        // 1.确定赛季：null或0代表当前赛季
        boolean isCurrent = season == null || season == 0;
        // 2.当前赛季从Redis实时榜单查询,历史赛季查points_board赛季表
        LocalDateTime now = LocalDateTime.now();
        String key = RedisConstants.POINTS_BOARD_KEY_PREFIX + now.format(DateUtils.POINTS_BOARD_SUFFIX_FORMATTER);

        // 2.1.我的积分和排名
        PointsBoard myBoard = isCurrent
                ? queryMyCurrentBoard(key)
                : lambdaQuery().eq(PointsBoard::getSeason, season)
                        .eq(PointsBoard::getUserId, userId).one();
        // 2.2.榜单列表
        List<PointsBoard> list = isCurrent
                ? queryCurrentBoardList(key, 1, 100)
                : lambdaQuery().eq(PointsBoard::getSeason, season)
                        .orderByAsc(PointsBoard::getRank).last("limit 100").list();

        // 3.组装VO
        PointsBoardVO vo = new PointsBoardVO();
        // 3.1.我的积分和排名
        if (myBoard != null) {
            vo.setPoints(myBoard.getPoints());
            vo.setRank(myBoard.getRank());
        } else {
            vo.setPoints(0);
            vo.setRank(0);
        }
        if (CollUtils.isEmpty(list)) {
            vo.setBoardList(CollUtils.emptyList());
            return vo;
        }
        // 3.2.批量填充用户姓名、头像
        fillStudentInfo(list);
        // 3.3.榜单列表
        List<PointsBoardItemVO> items = list.stream().map(b -> {
            PointsBoardItemVO item = new PointsBoardItemVO();
            item.setRank(b.getRank());
            item.setPoints(b.getPoints());
            item.setStudent(PointsBoardItemVO.StudentVO.of(
                    b.getName() == null ? String.valueOf(b.getUserId()) : b.getName(),
                    b.getIcon()));
            return item;
        }).collect(Collectors.toList());
        vo.setBoardList(items);
        return vo;
    }

    /**
     * 从Redis实时榜单中查询当前用户的积分和排名
     *
     * @param key 当前赛季榜单key
     * @return 我的积分排名信息
     */
    private PointsBoard queryMyCurrentBoard(String key) {
        // 1.绑定key
        BoundZSetOperations<String, String> ops = redisTemplate.boundZSetOps(key);
        // 2.获取当前用户id
        String userId = UserContext.getUser().toString();
        // 3.查询积分
        Double points = ops.score(userId);
        // 4.查询排名(从0开始,榜单显示需要+1)
        Long rank = ops.reverseRank(userId);
        // 5.封装返回
        PointsBoard p = new PointsBoard();
        p.setPoints(points == null ? 0 : points.intValue());
        p.setRank(rank == null ? 0 : rank.intValue() + 1);
        return p;
    }

    /**
     * 从Redis实时榜单中分页查询榜单数据(积分降序)
     *
     * @param key      当前赛季榜单key
     * @param pageNo   页码
     * @param pageSize 页大小
     * @return 榜单数据
     */
    private List<PointsBoard> queryCurrentBoardList(String key, int pageNo, int pageSize) {
        // 1.计算分页起始位置
        int from = (pageNo - 1) * pageSize;
        // 2.降序查询当前页数据
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(key, from, from + pageSize - 1);
        if (CollUtils.isEmpty(tuples)) {
            return CollUtils.emptyList();
        }
        // 3.封装为PointsBoard,排名从from+1开始
        int rank = from + 1;
        List<PointsBoard> list = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String member = tuple.getValue();
            Double points = tuple.getScore();
            if (member == null || points == null) {
                continue;
            }
            PointsBoard p = new PointsBoard();
            p.setUserId(Long.valueOf(member));
            p.setPoints(points.intValue());
            p.setRank(rank++);
            list.add(p);
        }
        return list;
    }

    private void fillStudentInfo(List<PointsBoard> boards) {
        if (CollUtils.isEmpty(boards)) {
            return;
        }
        Set<Long> userIds = boards.stream().map(PointsBoard::getUserId).collect(Collectors.toSet());
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        try {
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            if (CollUtils.isNotEmpty(users)) {
                userMap = users.stream().collect(Collectors.toMap(UserDTO::getId, u -> u, (a, b) -> a));
            }
        } catch (Exception ignored) {
            // 用户服务不可用时榜单仍然展示,姓名降级为用户id
        }
        for (PointsBoard b : boards) {
            UserDTO u = userMap.get(b.getUserId());
            if (u != null) {
                b.setName(u.getName());
                b.setIcon(u.getIcon());
            }
        }
    }
}
