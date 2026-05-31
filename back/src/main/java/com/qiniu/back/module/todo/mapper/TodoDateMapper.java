package com.qiniu.back.module.todo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiniu.back.domain.todo.TodoDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TodoDateMapper extends BaseMapper<TodoDate> {
    int insertBatch(@Param("list") List<TodoDate> list);
}
