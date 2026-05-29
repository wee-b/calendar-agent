package com.qiniu.back.domain.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yl_ai_dialogue")
public class AiDialogue {
    @TableId(value = "dialogue_id", type = IdType.AUTO)
    private Long dialogueId;
    private Long userId;
    private String sessionId;
    private String role;
    private String userText;
    private String aiResult;
    private String aiAudioUrl;
    private String intent;
    private String executeResult;
    private Integer deletedFlag;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
