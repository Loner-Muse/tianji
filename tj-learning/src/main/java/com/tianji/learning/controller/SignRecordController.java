package com.tianji.learning.controller;

import com.tianji.learning.domain.vo.SignRecordVO;
import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.service.ISignRecordService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RequestMapping("/sign-records")
@Api(tags = "签到相关接口")
@RestController
public class SignRecordController {
    private final ISignRecordService signRecordService;

    @ApiOperation("签到")
    @PostMapping
    public SignResultVO signRecord() {
        return signRecordService.signRecord();
    }

    @ApiOperation("查询本月签到记录")
    @GetMapping
    public SignRecordVO getSignRecord() {
        return signRecordService.getSignRecord();
    }
}
