package com.tianji.trade.domain.vo;

import com.tianji.api.dto.promotion.CouponDiscountDTO;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

@Data
@ApiModel(description = "订单确认页信息")
public class OrderConfirmVO {
    @ApiModelProperty("订单id")
    private Long orderId;
    @ApiModelProperty("订单总金额")
    private Integer totalAmount;
    @ApiModelProperty("优惠折扣金额")
    private Integer discountAmount;
    @ApiModelProperty("订单中包含的课程")
    private List<OrderCourseVO> courses;
    @ApiModelProperty("可用的优惠方案列表(按优惠金额降序,第一项为最优)")
    private List<CouponDiscountDTO> discounts;
}
