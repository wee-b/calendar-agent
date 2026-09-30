package com.qiniu.back.module.assistant.statemachine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiniu.back.domain.ErrorCode;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.assistant.domain.result.PendingTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

/** 会话阶段与执行认领分开保存；每轮通过版本号阻止并发覆盖。 */
@Service
@RequiredArgsConstructor
public class AgentFlowStateService {
    private final AgentFlowStateMapper stateMapper;

    public AgentFlowState getOrCreate(Long userId, String sessionId) {
        // 唯一键保证第一次并发访问也只建立一个会话状态。
        stateMapper.createIfAbsent(userId, sessionId);
        return stateMapper.selectOne(new LambdaQueryWrapper<AgentFlowState>()
                .eq(AgentFlowState::getUserId, userId).eq(AgentFlowState::getSessionId, sessionId));
    }

    public ConversationStage resolveStage(AgentFlowState state) {
        return ConversationStage.valueOf(state.getStage());
    }

    public PendingTask pending(AgentFlowState state) {
        return new PendingTask(state.getPendingTask(), state.getPendingDraftId(),
                state.getPendingPayload(), state.getImageInstruction());
    }

    public boolean claim(AgentFlowState state) {
        if (state.isProcessing()) return false;
        int count = stateMapper.update(null, expected(state)
                .eq(AgentFlowState::isProcessing, false)
                .set(AgentFlowState::isProcessing, true)
                .set(AgentFlowState::getVersion, state.getVersion() + 1)
                .set(AgentFlowState::getUpdateTime, LocalDateTime.now()));
        if (count == 1) state.setVersion(state.getVersion() + 1);
        return count == 1;
    }

    /** 仅当前持有版本的请求可以提交阶段及产物，然后释放认领。 */
    public void complete(AgentFlowState state, ConversationStage next, PendingTask pending, AgentType agent) {
        int count = stateMapper.update(null, expected(state)
                .eq(AgentFlowState::isProcessing, true)
                .set(AgentFlowState::getStage, next.name())
                .set(AgentFlowState::getCurrentAgent, agent.name())
                .set(AgentFlowState::getNextAgent, owner(next))
                .set(AgentFlowState::getPendingTask, pending.task())
                .set(AgentFlowState::getPendingDraftId, pending.draftId())
                .set(AgentFlowState::getPendingPayload, pending.planPreview())
                .set(AgentFlowState::getImageInstruction, pending.imageInstruction())
                .set(AgentFlowState::isProcessing, false)
                .set(AgentFlowState::getVersion, state.getVersion() + 1)
                .set(AgentFlowState::getUpdateTime, LocalDateTime.now()));
        if (count != 1) throw new BusinessException(ErrorCode.BAD_REQUEST, "会话状态已变化，请刷新后重试");
    }

    /** 未进入写调用的失败可重试；数据库中的原阶段和产物一直保留。 */
    public void releaseClaim(AgentFlowState state) {
        stateMapper.update(null, expected(state)
                .eq(AgentFlowState::isProcessing, true)
                .set(AgentFlowState::isProcessing, false)
                .set(AgentFlowState::getVersion, state.getVersion() + 1)
                .set(AgentFlowState::getUpdateTime, LocalDateTime.now()));
    }

    public String owner(ConversationStage stage) {
        return switch (stage) {
            case CHAT -> "NONE";
            case PLAN -> AgentType.PLANNER.name();
            case EXECUTE -> AgentType.EXECUTOR.name();
            case IMAGE -> AgentType.IMAGE.name();
        };
    }

    private LambdaUpdateWrapper<AgentFlowState> expected(AgentFlowState state) {
        return new LambdaUpdateWrapper<AgentFlowState>()
                .eq(AgentFlowState::getStateId, state.getStateId())
                .eq(AgentFlowState::getUserId, state.getUserId())
                .eq(AgentFlowState::getSessionId, state.getSessionId())
                .eq(AgentFlowState::getVersion, state.getVersion());
    }
}
