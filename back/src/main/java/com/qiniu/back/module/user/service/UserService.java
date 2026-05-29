package com.qiniu.back.module.user.service;

import com.qiniu.back.domain.user.dto.UserLoginDTO;
import com.qiniu.back.domain.user.dto.UserRegisterDTO;
import com.qiniu.back.domain.user.vo.LoginVO;
import com.qiniu.back.domain.user.vo.UserInfoVO;

public interface UserService {

    void register(UserRegisterDTO request);

    LoginVO login(UserLoginDTO request);

    UserInfoVO getUserInfo();

    UserInfoVO updateUserInfo(UserInfoVO request);

    void logout();
}
