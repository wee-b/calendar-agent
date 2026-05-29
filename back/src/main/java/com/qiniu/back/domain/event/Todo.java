package com.qiniu.back.domain.event;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yl_todo")
public class Todo {
    @TableId(value = "todo_id", type = IdType.AUTO)
    private Long todoId;
    private Long userId;
    private String title;
    private String content;
    private LocalDateTime todoTime;
    private Integer priority;
    private Integer status;
    private String voiceText;
    private Integer deletedFlag;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
