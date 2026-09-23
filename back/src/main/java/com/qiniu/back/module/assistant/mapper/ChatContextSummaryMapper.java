package com.qiniu.back.module.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiniu.back.module.assistant.domain.model.ChatContextSummary;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatContextSummaryMapper extends BaseMapper<ChatContextSummary> {
}
