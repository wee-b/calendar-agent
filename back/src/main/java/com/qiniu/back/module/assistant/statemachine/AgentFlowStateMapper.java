package com.qiniu.back.module.assistant.statemachine;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentFlowStateMapper extends BaseMapper<AgentFlowState> {
    @Insert("""
            INSERT INTO yl_agent_flow_state
                (user_id, session_id, current_agent, next_agent, stage, processing, version)
            VALUES (#{userId}, #{sessionId}, 'NONE', 'NONE', 'CHAT', 0, 0)
            ON DUPLICATE KEY UPDATE state_id = state_id
            """)
    int createIfAbsent(@Param("userId") Long userId, @Param("sessionId") String sessionId);
}
