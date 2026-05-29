package com.qiniu.back.domain.user.dto;

import com.qiniu.back.annotation.CheckPhone;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserLoginDTO {

    @CheckPhone
    @NotBlank(message = "手机号不能为空")
    @Schema(description = "手机号", requiredMode = Schema.RequiredMode.REQUIRED, example = "13800138000")
    private String phone;


    @NotBlank(message = "密码不能为空")
    @Schema(description = "密码 (密码登录时必填)", example = "123456")
    private String password;
}
