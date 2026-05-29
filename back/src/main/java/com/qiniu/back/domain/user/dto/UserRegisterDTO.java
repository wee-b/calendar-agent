package com.qiniu.back.domain.user.dto;

import lombok.Data;

@Data
public class UserRegisterDTO {
    private String userName;
    private String phone;
    private String password;
}
