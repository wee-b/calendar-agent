package com.qiniu.back.domain.user.dto;

import com.qiniu.back.annotation.CheckPhone;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserRegisterDTO {

    @CheckPhone
    @NotBlank(message = "手机号不能为空")
    @Schema(description = "手机号", requiredMode = Schema.RequiredMode.REQUIRED, example = "13800138000")
    private String phone;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "登录密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "123456")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Schema(description = "用户名", requiredMode = Schema.RequiredMode.REQUIRED, example = "测试注册")
    private String userName;

    @Schema(description = "性别（0-保密，1-男，2-女）", example = "1")
    private Integer gender;

    @Schema(description = "生日", example = "")
    private LocalDateTime birthday;
}
