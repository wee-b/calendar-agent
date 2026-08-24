package com.qiniu.back.domain.memory.vo;

import com.qiniu.back.domain.memory.UserMemory;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UserMemoryVO {
    private Long memoryId;
    private String memoryType;
    private String content;
    private String normalizedKey;
    private String source;
    private BigDecimal confidence;
    private String status;
    private LocalDateTime lastUsedTime;
    private LocalDateTime expireTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static UserMemoryVO from(UserMemory memory) {
        UserMemoryVO vo = new UserMemoryVO();
        vo.setMemoryId(memory.getMemoryId());
        vo.setMemoryType(memory.getMemoryType());
        vo.setContent(memory.getContent());
        vo.setNormalizedKey(memory.getNormalizedKey());
        vo.setSource(memory.getSource());
        vo.setConfidence(memory.getConfidence());
        vo.setStatus(memory.getStatus());
        vo.setLastUsedTime(memory.getLastUsedTime());
        vo.setExpireTime(memory.getExpireTime());
        vo.setCreateTime(memory.getCreateTime());
        vo.setUpdateTime(memory.getUpdateTime());
        return vo;
    }
}
