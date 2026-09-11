package com.qiniu.back.module.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiniu.back.domain.memory.UserMemory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemory> {
}
