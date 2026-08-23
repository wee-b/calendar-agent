package com.qiniu.back.domain.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yl_agent_flow_state")
public class AgentFlowState {
    @TableId(value = "state_id", type = IdType.AUTO)
    private Long stateId;
    private Long userId;
    private String sessionId;
    private String currentAgent;
    private String nextAgent;
    private String stage;
    private String pendingTask;
    private String pendingPayload;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
