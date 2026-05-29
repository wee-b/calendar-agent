package com.qiniu.back.module.user.controller;

import com.qiniu.back.annotation.NoNeedLogin;
import com.qiniu.back.domain.ResponseDTO;
import com.qiniu.back.domain.user.dto.UserLoginDTO;
import com.qiniu.back.domain.user.dto.UserRegisterDTO;
import com.qiniu.back.domain.user.vo.LoginVO;
import com.qiniu.back.domain.user.vo.UserInfoVO;
import com.qiniu.back.module.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用户模块")
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    @NoNeedLogin
    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public ResponseDTO<Void> register(@RequestBody @Valid UserRegisterDTO request) {
        userService.register(request);
        return ResponseDTO.ok();
    }

    @NoNeedLogin
    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public ResponseDTO<LoginVO> login(@RequestBody @Valid UserLoginDTO request) {
        LoginVO result = userService.login(request);
        return ResponseDTO.ok(result);
    }

    @GetMapping("/info")
    @Operation(summary = "获取当前用户信息")
    public ResponseDTO<UserInfoVO> info() {
        UserInfoVO userInfo = userService.getUserInfo();
        return ResponseDTO.ok(userInfo);
    }

    @PutMapping("/info")
    @Operation(summary = "修改用户信息")
    public ResponseDTO<UserInfoVO> updateInfo(@RequestBody UserInfoVO request) {
        UserInfoVO userInfo = userService.updateUserInfo(request);
        return ResponseDTO.ok(userInfo);
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录")
    public ResponseDTO<Void> logout() {
        userService.logout();
        return ResponseDTO.ok();
    }
}
