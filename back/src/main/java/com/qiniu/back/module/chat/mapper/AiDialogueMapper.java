package com.qiniu.back.module.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiniu.back.domain.chat.AiDialogue;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiDialogueMapper extends BaseMapper<AiDialogue> {
}
