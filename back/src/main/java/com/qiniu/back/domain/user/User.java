package com.qiniu.back.domain.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("yl_user")
public class User {
    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;
    private String userCode;
    private String userName;
    private String phone;
    private String password;
    private String avatar;
    private Integer gender;
    private LocalDateTime birthday;


    private Integer status;
    private Integer deletedFlag;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
