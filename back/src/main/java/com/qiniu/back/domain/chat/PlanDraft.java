package com.qiniu.back.domain.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yl_plan_draft")
public class PlanDraft {
    @TableId(value = "draft_id", type = IdType.AUTO)
    private Long draftId;
    private Long userId;
    private String sessionId;
    private String goal;
    private String planJson;
    private String status;
    private String sourceMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
