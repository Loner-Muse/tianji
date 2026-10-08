package com.tianji.api.client.promotion.fallback;

import com.tianji.api.client.promotion.PromotionClient;
import com.tianji.api.dto.promotion.CouponDiscountDTO;
import com.tianji.api.dto.promotion.OrderCouponDTO;
import com.tianji.api.dto.promotion.OrderCourseDTO;
import com.tianji.common.exceptions.BizIllegalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Collections;
import java.util.List;

/**
 * {@link PromotionClient} 的降级（熔断兜底）实现
 * <p>
 * 当促销服务不可用（超时、异常、熔断）时，Feign 会走这里，避免错误扩散到交易服务。
 * <p>
 * ★★ 降级策略不是"一刀切"，而是<b>按业务重要性分两类</b>：
 * <table border="1">
 *   <tr><th>方法</th><th>降级行为</th><th>为什么</th></tr>
 *   <tr>
 *     <td>{@link #findDiscountSolution}</td>
 *     <td>返回<b>空集合</b></td>
 *     <td>只是"推荐优惠方案"，券服务挂了页面就显示"暂无可用优惠"，
 *         用户还能正常浏览下单 —— 属于<b>锦上添花</b>，不该阻断主流程</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #queryDiscountDetailByOrder}</td>
 *     <td>返回 <b>null</b></td>
 *     <td>算不了优惠就按原价下单（{@code discountAmount = 0}），
 *         交易服务那边判了 null，**用户能正常买到东西**，只是没优惠</td>
 *   </tr>
 *   <tr>
 *     <td><b>{@link #writeOffCoupon}</b></td>
 *     <td><b>抛异常</b></td>
 *     <td>核销失败必须让下单失败！否则"订单创建成功、券却没核销"，
 *         用户能拿同一张券反复下单 ⇒ <b>资损</b></td>
 *   </tr>
 *   <tr>
 *     <td><b>{@link #refundCoupon}</b></td>
 *     <td><b>抛异常</b></td>
 *     <td>同理，退还失败要让取消订单失败。否则"订单取消了、券没退回"，
 *         用户白白损失一张券 ⇒ <b>用户资产损失</b></td>
 *   </tr>
 *   <tr>
 *     <td>{@link #queryDiscountRules}</td>
 *     <td>返回<b>空集合</b></td>
 *     <td>只是订单详情里的一行文案，挂了就不展示优惠券描述，不影响看订单</td>
 *   </tr>
 * </table>
 * <p>
 * ★ 一句话原则：<b>「查」类操作可以降级成空值；「改」类操作必须失败（抛异常）</b> ——
 * 因为读到空数据只是体验差，写没成功却当作成功就是数据不一致。
 * <p>
 * ★ 注意：{@link BizIllegalException} 是运行时异常，会被交易服务的全局异常处理器捕获并返回给前端，
 * <b>同时会让本地事务回滚</b>（配合 {@code @Transactional} / {@code @GlobalTransactional}）。
 */
@Slf4j
public class PromotionClientFallback implements FallbackFactory<PromotionClient> {

    @Override
    public PromotionClient create(Throwable cause) {
        log.error("查询促销服务出现异常", cause);
        return new PromotionClient() {

            @Override
            public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses) {
                // 推荐方案失败 → 当作"没有可用优惠"，页面正常展示
                return Collections.emptyList();
            }

            @Override
            public CouponDiscountDTO queryDiscountDetailByOrder(OrderCouponDTO orderCouponDTO) {
                // 算不了优惠 → 返回 null，交易服务会按"不打折"处理，用户能正常下单
                return null;
            }

            @Override
            public void writeOffCoupon(List<Long> userCouponIds) {
                // ★ 核销是"改"操作，不能假装成功 —— 必须抛异常让下单失败
                throw new BizIllegalException(500, "核销优惠券异常", cause);
            }

            @Override
            public void refundCoupon(List<Long> userCouponIds) {
                // ★ 退券也是"改"操作，不能假装成功 —— 必须抛异常让取消订单失败
                throw new BizIllegalException(500, "退还优惠券异常", cause);
            }

            @Override
            public List<String> queryDiscountRules(List<Long> userCouponIds) {
                // 只是详情页的一行文案，查不到就不展示
                return Collections.emptyList();
            }
        };
    }
}
