package com.qiniu.back.module.todo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiniu.back.domain.todo.TodoDate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TodoDateMapper extends BaseMapper<TodoDate> {
}
