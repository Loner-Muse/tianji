package com.tianji.promotion.mapper;

import com.tianji.promotion.domain.po.Coupon;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 * 优惠券的规则信息 Mapper 接口
 * </p>
 *
 * @author author
 * @since 2026-09-20
 */
public interface CouponMapper extends BaseMapper<Coupon> {

    /**
     * 扣减优惠券库存：已发放数量 +1
     * <p>
     * 两个关键点：
     * <ol>
     *     <li>用 {@code issue_num = issue_num + 1} 在数据库层面做原子自增，
     *         而不是"先查出来 +1 再写回"，后者在并发下必然超卖</li>
     *     <li>WHERE 中带上 {@code issue_num &lt; total_num} 作为乐观锁条件，
     *         库存不足时影响 0 行，调用方据此判断并抛异常</li>
     * </ol>
     * 相比"版本号式"乐观锁，这里不比对旧值，只比对库存余量，
     * 因此并发时不会出现"N 个线程只有一个成功"的低成功率问题。
     *
     * @param couponId 优惠券id
     * @return 影响行数，返回 0 表示库存不足（WHERE 条件不成立）
     */
    @Update("UPDATE coupon SET issue_num = issue_num + 1 WHERE id = #{couponId} AND issue_num < total_num")
    int incrIssueNum(@Param("couponId") Long couponId);

    @Update("UPDATE coupon SET used_num = used_num + 1 WHERE id = #{couponId}")
    int incrUsedNum(@Param("couponId") Long couponId);

    @Update("UPDATE coupon SET used_num = used_num - 1 WHERE id = #{couponId}")
    int decrUsedNum(@Param("couponId") Long couponId);
}
