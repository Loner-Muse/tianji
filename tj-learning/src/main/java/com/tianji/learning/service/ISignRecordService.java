package com.tianji.learning.service;

import com.tianji.learning.domain.vo.SignRecordVO;
import com.tianji.learning.domain.vo.SignResultVO;

public interface ISignRecordService {
    SignResultVO signRecord();

    SignRecordVO getSignRecord();
}
