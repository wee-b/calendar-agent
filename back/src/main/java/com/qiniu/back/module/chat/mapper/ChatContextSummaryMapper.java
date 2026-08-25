package com.qiniu.back.module.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiniu.back.domain.chat.ChatContextSummary;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatContextSummaryMapper extends BaseMapper<ChatContextSummary> {
}
