package com.qiniu.back.domain.dailyNote;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("yl_daily_note")
public class DailyNote {
    @TableId(value = "note_id", type = IdType.AUTO)
    private Long noteId;
    private Long userId;
    private LocalDate noteDate;
    private String content;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
