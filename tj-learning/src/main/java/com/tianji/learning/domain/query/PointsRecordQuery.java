package com.tianji.learning.domain.query;

import com.tianji.common.domain.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@ApiModel(description = "积分记录分页查询条件")
public class PointsRecordQuery extends PageQuery {
    @ApiModelProperty(value = "积分类型：1-课程学习，2-每日签到，3-课程问答，4-课程笔记，5-课程评价")
    private Integer type;
}
