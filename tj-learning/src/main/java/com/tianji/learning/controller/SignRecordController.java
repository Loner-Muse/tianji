package com.tianji.learning.controller;

import com.tianji.learning.domain.vo.SignResultVO;
import com.tianji.learning.service.ISignRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RequestMapping("/sign/records")
@RestController
public class SignRecordController {
    private final ISignRecordService signRecordService;
    @PostMapping
    public SignResultVO signRecord() {
        return signRecordService.signRecord();
    }
    @GetMapping
    public List<Long> getSignRecord() {
        return signRecordService.getSignRecord();
    }
}
