package com.qiniu.back.module.almanac.controller;

import com.qiniu.back.annotation.NoNeedLogin;
import com.qiniu.back.domain.ResponseDTO;
import com.qiniu.back.domain.almanac.vo.AlmanacDayVO;
import com.qiniu.back.module.almanac.service.AlmanacService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "现代黄历模块")
@RestController
@RequestMapping("/almanac")
public class AlmanacController {

    @Autowired
    private AlmanacService almanacService;

    @NoNeedLogin
    @GetMapping("/day")
    @Operation(summary = "根据日期获取现代黄历")
    public ResponseDTO<AlmanacDayVO> day(@RequestParam(required = false) String date) {
        return ResponseDTO.ok(almanacService.getDayAlmanac(date));
    }
}
