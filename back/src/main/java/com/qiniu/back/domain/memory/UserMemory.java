package com.qiniu.back.domain.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("yl_user_memory")
public class UserMemory {
    @TableId(value = "memory_id", type = IdType.AUTO)
    private Long memoryId;
    private Long userId;
    private String memoryType;
    private String content;
    private String normalizedKey;
    private String source;
    private Long sourceId;
    private BigDecimal confidence;
    private String status;
    private LocalDateTime lastUsedTime;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
