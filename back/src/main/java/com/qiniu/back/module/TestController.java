package com.qiniu.back.module;

import cn.dev33.satoken.stp.StpUtil;
import com.qiniu.back.annotation.NoNeedLogin;
import com.qiniu.back.util.SaTokenUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "测试接口")
@RestController
public class TestController {

    @NoNeedLogin
    @GetMapping("/test/testConnection")
    @Operation(summary = "测试后端连接")
    public String testConnection() {
        return "hello world";
    }

    @GetMapping("/test/testLogin")
    @Operation(summary = "测试登录拦截器")
    public String testLogin() {
        long userId = StpUtil.getLoginIdAsLong();
        return "you are logged in, userId: " + userId;
    }

    @NoNeedLogin
    @GetMapping("/test/getToken")
    @Operation(summary = "获取测试Token")
    public String getToken(@RequestParam(defaultValue = "1") Long userId) {
        return SaTokenUtil.generateClientToken(userId);
    }
}
