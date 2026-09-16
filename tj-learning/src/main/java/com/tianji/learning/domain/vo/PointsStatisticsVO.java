package com.tianji.learning.domain.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel(description = "每日积分统计实体")
public class PointsStatisticsVO {
    @ApiModelProperty("获取积分方式")
    private PointsRecordTypeVO type;
    @ApiModelProperty("今日已获取积分值")
    private Integer points;
    @ApiModelProperty("单日积分上限")
    private Integer maxPoints;

    @Data
    @ApiModel(description = "积分类型")
    public static class PointsRecordTypeVO {
        @ApiModelProperty("类型code，1：学习，2：签到，3：问答，4：笔记，5：评价")
        private Integer value;
        @ApiModelProperty("类型描述")
        private String desc;

        public static PointsRecordTypeVO of(Integer value, String desc) {
            PointsRecordTypeVO vo = new PointsRecordTypeVO();
            vo.setValue(value);
            vo.setDesc(desc);
            return vo;
        }
    }
}
