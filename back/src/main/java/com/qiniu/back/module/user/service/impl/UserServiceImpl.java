package com.qiniu.back.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiniu.back.domain.ErrorCode;
import com.qiniu.back.domain.user.User;
import com.qiniu.back.domain.user.dto.UserLoginDTO;
import com.qiniu.back.domain.user.dto.UserRegisterDTO;
import com.qiniu.back.domain.user.vo.LoginVO;
import com.qiniu.back.domain.user.vo.UserInfoVO;
import com.qiniu.back.enumeration.DeletedFlagEnum;
import com.qiniu.back.enumeration.GenderEnum;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.user.mapper.UserMapper;
import com.qiniu.back.module.user.service.UserService;
import com.qiniu.back.util.SaTokenUtil;
import com.qiniu.back.util.LoginUserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public void register(UserRegisterDTO request) {
        // 校验手机号是否已注册
        boolean exists = userMapper.exists(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, request.getPhone()));
        if (exists) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该手机号已注册");
        }

        // 生成 userCode
        String userCode = generateUserCode();

        User user = new User();
        user.setUserCode(userCode);
        user.setUserName(request.getUserName());
        user.setPhone(request.getPhone());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setGender(request.getGender() != null ? request.getGender() : GenderEnum.UNKNOWN.getValue());
        user.setBirthday(request.getBirthday());
        user.setStatus(1);
        user.setDeletedFlag(DeletedFlagEnum.NORMAL_STATUS.getValue());

        userMapper.insert(user);
    }

    @Override
    public LoginVO login(UserLoginDTO request) {
        // 查询用户
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, request.getPhone()));
        if (user == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号未注册");
        }

        // 校验密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码错误");
        }

        // 检查用户状态
        if (user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }

        // 生成 token
        String token = SaTokenUtil.generateClientToken(user.getUserId());

        return new LoginVO(token, toUserInfoVO(user));
    }

    @Override
    public UserInfoVO getUserInfo() {
        Long userId = LoginUserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return toUserInfoVO(user);
    }

    @Override
    public UserInfoVO updateUserInfo(UserInfoVO request) {
        Long userId = LoginUserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        if (request.getUserName() != null) {
            user.setUserName(request.getUserName());
        }
        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }
        if (request.getGender() != null) {
            user.setGender(request.getGender());
        }
        if (request.getBirthday() != null) {
            user.setBirthday(request.getBirthday());
        }

        userMapper.updateById(user);

        return toUserInfoVO(user);
    }

    @Override
    public void logout() {
        Long userId = LoginUserContext.getUserId();
        if (userId == null) {
            return;
        }
        SaTokenUtil.logoutClientTokenByUserId(userId);
    }

    private String generateUserCode() {
        User latestUser = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .orderByDesc(User::getUserId)
                .last("LIMIT 1"));
        if (latestUser == null || latestUser.getUserCode() == null) {
            return "U00001";
        }
        String code = latestUser.getUserCode().substring(1);
        int num = Integer.parseInt(code) + 1;
        return "U" + String.format("%05d", num);
    }

    private UserInfoVO toUserInfoVO(User user) {
        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(user.getUserId());
        vo.setUserCode(user.getUserCode());
        vo.setUserName(user.getUserName());
        vo.setPhone(user.getPhone());
        vo.setAvatar(user.getAvatar());
        vo.setGender(user.getGender());
        vo.setBirthday(user.getBirthday());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
