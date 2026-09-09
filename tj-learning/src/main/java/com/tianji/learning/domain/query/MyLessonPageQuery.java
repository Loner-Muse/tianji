package com.tianji.learning.domain.query;

import com.tianji.common.domain.query.PageQuery;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@ApiModel(description = "我的课表分页查询条件")
public class MyLessonPageQuery extends PageQuery {
    @ApiModelProperty(value = "课程状态，0-未学习，1-学习中，2-已学完，3-已过期", example = "2")
    private Integer status;
}
