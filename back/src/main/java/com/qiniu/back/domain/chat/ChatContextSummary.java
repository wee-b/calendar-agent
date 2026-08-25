package com.qiniu.back.domain.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yl_chat_context_summary")
public class ChatContextSummary {
    @TableId(value = "summary_id", type = IdType.AUTO)
    private Long summaryId;
    private Long userId;
    private String sessionId;
    private Long lastDialogueId;
    private Integer messageCount;
    private String summaryText;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
