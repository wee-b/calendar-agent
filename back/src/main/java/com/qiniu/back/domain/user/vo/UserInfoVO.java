package com.qiniu.back.domain.user.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserInfoVO {

    private Long userId;
    private String userCode;
    private String userName;
    private String phone;
    private String avatar;
    private Integer gender;
    private LocalDateTime birthday;
    private LocalDateTime createTime;
}
