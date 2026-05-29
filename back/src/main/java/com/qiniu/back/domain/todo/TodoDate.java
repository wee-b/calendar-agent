package com.qiniu.back.domain.todo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

@Data
@TableName("yl_todo_date")
public class TodoDate {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long todoId;
    private LocalDate todoDate;
    private String dayContent;
    private Integer status;
}
