package com.qiniu.back.domain.user.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserVO {
    private Long userId;
    private String userCode;
    private String userName;
    private String phone;
    private String avatar;
    private Integer gender;
    private LocalDateTime birthday;
    private Integer status;
    private LocalDateTime createTime;
}
