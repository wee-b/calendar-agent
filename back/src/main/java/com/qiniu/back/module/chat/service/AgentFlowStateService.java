package com.qiniu.back.module.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiniu.back.domain.chat.AgentFlowState;
import com.qiniu.back.module.chat.mapper.AgentFlowStateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AgentFlowStateService {

    public static final String AGENT_SUPERVISOR = "SUPERVISOR";
    public static final String AGENT_PLANNER = "PLANNER";
    public static final String AGENT_EXECUTOR = "EXECUTOR";

    public static final String STAGE_IDLE = "IDLE";
    public static final String STAGE_WAIT_CONFIRM = "WAIT_CONFIRM";
    public static final String STAGE_WAIT_FEEDBACK = "WAIT_FEEDBACK";

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

    public AgentFlowState waitPlanFeedback(Long userId, String sessionId, String requirement, String planResult) {
        AgentFlowState state = baseState(userId, sessionId);
        state.setCurrentAgent(AGENT_PLANNER);
        state.setNextAgent(AGENT_EXECUTOR);
        state.setStage(STAGE_WAIT_FEEDBACK);
        state.setPendingTask(requirement);
        state.setPendingPayload(planResult);
        return save(state);
    }

    public AgentFlowState updatePendingTask(Long userId, String sessionId, AgentFlowState oldState, String userFeedback) {
        oldState.setPendingTask(oldState.getPendingTask() + "\n用户补充/修改：" + userFeedback);
        return save(oldState);
    }

    public void clear(Long userId, String sessionId) {
        stateMapper.delete(baseQuery(userId, sessionId));
    }

    public boolean isRejectMessage(String message) {
        if (message == null) return false;
        String text = normalize(message);
        return text.equals("不")
                || text.equals("不用")
                || text.equals("不需要")
                || text.equals("不要")
                || text.equals("算了")
                || text.equals("取消")
                || text.equals("先不用")
                || text.equals("不用了");
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

    private String normalize(String message) {
        return message.trim().replaceAll("[　\\s,，.。!！?？~～]", "");
    }
}
