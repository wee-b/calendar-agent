package com.qiniu.back.module.assistant.statemachine;

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
    private Long pendingDraftId;
    private String imageInstruction;
    /** 与业务阶段独立；异常写调用会保留该标记以阻止重复执行。 */
    private boolean processing;
    private long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
