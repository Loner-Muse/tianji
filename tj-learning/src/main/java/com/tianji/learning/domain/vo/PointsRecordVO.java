package com.tianji.learning.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@ApiModel(description = "积分记录")
public class PointsRecordVO {
    @ApiModelProperty("积分记录id")
    private Long id;
    @ApiModelProperty("用户id")
    private Long userId;
    @ApiModelProperty("积分方式")
    private String type;
    @ApiModelProperty("积分值")
    private Integer points;
    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}
