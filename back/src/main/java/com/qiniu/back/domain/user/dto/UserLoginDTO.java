package com.qiniu.back.domain.user.dto;

import lombok.Data;

@Data
public class UserLoginDTO {
    private String phone;
    private String password;
}
