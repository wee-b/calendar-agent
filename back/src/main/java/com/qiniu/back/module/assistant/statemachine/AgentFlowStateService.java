package com.qiniu.back.module.assistant.statemachine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AgentFlowStateService {

    public static final String AGENT_SUPERVISOR = "SUPERVISOR";
    public static final String AGENT_CHAT = "CHAT";
    public static final String AGENT_PLANNER = "PLANNER";
    public static final String AGENT_EXECUTOR = "EXECUTOR";

    public static final String STAGE_IDLE = "IDLE";
    public static final String STAGE_WAIT_CONFIRM = "WAIT_CONFIRM";
    public static final String STAGE_WAIT_FEEDBACK = "WAIT_FEEDBACK";
    public static final String STAGE_PROCESSING = "PROCESSING";

    private static final int EXPIRE_MINUTES = 30;

    @Autowired
    private AgentFlowStateMapper stateMapper;

    public Optional<AgentFlowState> get(Long userId, String sessionId) {
        AgentFlowState state = stateMapper.selectOne(baseQuery(userId, sessionId).last("LIMIT 1"));
        if (state == null) return Optional.empty();
        if (state.getUpdateTime() != null
                && state.getUpdateTime().plusMinutes(EXPIRE_MINUTES).isBefore(LocalDateTime.now())) {
            clear(userId, sessionId);
            return Optional.empty();
        }
        return Optional.of(state);
    }

    public AgentFlowState waitExecutorConfirm(Long userId, String sessionId, String task) {
        AgentFlowState state = baseState(userId, sessionId);
        state.setCurrentAgent(AGENT_EXECUTOR);
        state.setNextAgent(AGENT_EXECUTOR);
        state.setStage(STAGE_WAIT_CONFIRM);
        state.setPendingTask(task);
        return save(state);
    }

    public AgentFlowState waitPlannerConfirm(Long userId, String sessionId, String requirement) {
        AgentFlowState state = baseState(userId, sessionId);
        state.setCurrentAgent(AGENT_PLANNER);
        state.setNextAgent(AGENT_PLANNER);
        state.setStage(STAGE_WAIT_CONFIRM);
        state.setPendingTask(requirement);
        return save(state);
    }

    public AgentFlowState waitPlanFeedback(Long userId, String sessionId, String requirement,
                                           Long draftId, String planResult) {
        AgentFlowState state = baseState(userId, sessionId);
        state.setCurrentAgent(AGENT_PLANNER);
        state.setNextAgent(AGENT_EXECUTOR);
        state.setStage(STAGE_WAIT_FEEDBACK);
        state.setPendingTask(requirement);
        state.setPendingPayload(planResult);
        state.setPendingDraftId(draftId);
        return save(state);
    }

    public AgentFlowState updatePendingTask(Long userId, String sessionId, AgentFlowState oldState, String userFeedback) {
        oldState.setPendingTask(oldState.getPendingTask() + "\n用户补充/修改：" + userFeedback);
        return save(oldState);
    }

    public void clear(Long userId, String sessionId) {
        stateMapper.delete(baseQuery(userId, sessionId));
    }

    /**
     * Atomically owns a pending state before executing its transition.
     * Only one concurrent request can change the expected stage to PROCESSING.
     */
    public boolean claim(AgentFlowState state) {
        if (state == null || state.getStateId() == null || state.getStage() == null) return false;
        int updated = stateMapper.update(null, new LambdaUpdateWrapper<AgentFlowState>()
                .eq(AgentFlowState::getStateId, state.getStateId())
                .eq(AgentFlowState::getStage, state.getStage())
                .set(AgentFlowState::getStage, STAGE_PROCESSING)
                .set(AgentFlowState::getUpdateTime, LocalDateTime.now()));
        return updated == 1;
    }

    public boolean isProcessing(AgentFlowState state) {
        return state != null && STAGE_PROCESSING.equals(state.getStage());
    }

    public void releaseClaim(AgentFlowState originalState) {
        if (originalState == null || originalState.getStateId() == null) return;
        stateMapper.update(null, new LambdaUpdateWrapper<AgentFlowState>()
                .eq(AgentFlowState::getStateId, originalState.getStateId())
                .eq(AgentFlowState::getStage, STAGE_PROCESSING)
                .set(AgentFlowState::getStage, originalState.getStage())
                .set(AgentFlowState::getUpdateTime, LocalDateTime.now()));
    }

    public ConversationStage resolveStage(AgentFlowState state) {
        if (state == null) return ConversationStage.READY_FOR_INPUT;
        if (STAGE_WAIT_FEEDBACK.equals(state.getStage())) {
            return ConversationStage.AWAITING_PLAN_FEEDBACK;
        }
        if (STAGE_WAIT_CONFIRM.equals(state.getStage())
                && AGENT_PLANNER.equals(state.getNextAgent())) {
            return ConversationStage.AWAITING_PLAN_CONFIRMATION;
        }
        if (STAGE_WAIT_CONFIRM.equals(state.getStage())
                && (AGENT_EXECUTOR.equals(state.getNextAgent())
                || AGENT_CHAT.equals(state.getNextAgent()))) {
            return ConversationStage.AWAITING_EXECUTION_CONFIRMATION;
        }
        throw new IllegalStateException("无法识别会话状态: stage=" + state.getStage()
                + ", currentAgent=" + state.getCurrentAgent() + ", nextAgent=" + state.getNextAgent());
    }

    private AgentFlowState save(AgentFlowState state) {
        state.setUpdateTime(LocalDateTime.now());
        AgentFlowState old = stateMapper.selectOne(baseQuery(state.getUserId(), state.getSessionId()).last("LIMIT 1"));
        if (old == null) {
            state.setCreateTime(LocalDateTime.now());
            stateMapper.insert(state);
            return state;
        }

        stateMapper.update(null, new LambdaUpdateWrapper<AgentFlowState>()
                .eq(AgentFlowState::getStateId, old.getStateId())
                .set(AgentFlowState::getCurrentAgent, state.getCurrentAgent())
                .set(AgentFlowState::getNextAgent, state.getNextAgent())
                .set(AgentFlowState::getStage, state.getStage())
                .set(AgentFlowState::getPendingTask, state.getPendingTask())
                .set(AgentFlowState::getPendingPayload, state.getPendingPayload())
                .set(AgentFlowState::getPendingDraftId, state.getPendingDraftId())
                .set(AgentFlowState::getUpdateTime, state.getUpdateTime()));
        state.setStateId(old.getStateId());
        state.setCreateTime(old.getCreateTime());
        return state;
    }

    private AgentFlowState baseState(Long userId, String sessionId) {
        AgentFlowState state = new AgentFlowState();
        state.setUserId(userId);
        state.setSessionId(sessionId);
        return state;
    }

    private LambdaQueryWrapper<AgentFlowState> baseQuery(Long userId, String sessionId) {
        return new LambdaQueryWrapper<AgentFlowState>()
                .eq(AgentFlowState::getUserId, userId)
                .eq(AgentFlowState::getSessionId, sessionId);
    }

}
