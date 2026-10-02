package com.tianji.promotion.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 领券消息 DTO（MQ 消息体）
 * <p>
 * ============================ 为什么只需要这三个字段？ ============================
 * <p>
 * 异步领券的流程是「<b>先校验、后落库</b>」：
 * <pre>
 *   接口层（同步）：查缓存 → 校验发放时间/库存/限领 → 扣库存 → 发 MQ → 立即返回
 *   MQ 消费者（异步）：收消息 → 扣 DB 库存 → 写 user_coupon → 更新兑换码
 * </pre>
 * <b>校验已经在发消息之前全部做完了</b>，所以消费者只需要拿到"落库所需的最小信息"：
 * <ul>
 *   <li>{@code couponId} → 更新券的已发放数量（{@code incrIssueNum}）需要</li>
 *   <li>{@code userId}   → 新增用户券（{@code saveUserCoupon}）需要</li>
 *   <li>{@code serialNum}→ 兑换码场景下，把这张码标记为已使用需要</li>
 * </ul>
 * 注意：<b>不是</b>把整个 {@code Coupon} 对象塞进消息里 ——
 * 那样消息体会很大，而且券的信息可能已经变化（发消息时 vs 消费时不一致）。
 * <p>
 * ============================ 关于 serialNum ============================
 * <p>
 * 它是兑换码的<b>序列号</b>，有三重身份：
 * <ol>
 *   <li>兑换码内容的一部分（50 位明文里的 32 位）</li>
 *   <li>{@code exchange_code} 表的主键 {@code id}</li>
 *   <li>BitMap 里的 offset（用于防重）</li>
 * </ol>
 * <b>手动领取时它是 null</b>（不涉及兑换码）；兑换码兑换时才有值。
 * <p>
 * 本次（文档 2.4）的手动领取流程暂时用不到它，
 * 但为了让后面的练习 3.1（异步兑换码领券）能复用同一个 DTO，这里先留上这个字段。
 */
@Data
@ApiModel(description = "领券消息实体")
public class UserCouponDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("用户id")
    private Long userId;

    @ApiModelProperty("优惠券id")
    private Long couponId;

    @ApiModelProperty("兑换码序列号，手动领取时为 null")
    private Long serialNum;
}
