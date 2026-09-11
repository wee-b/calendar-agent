package com.qiniu.back.module.almanac.service;

import com.qiniu.back.domain.almanac.vo.AlmanacDayVO;

public interface AlmanacService {

    AlmanacDayVO getDayAlmanac(String date);
}
