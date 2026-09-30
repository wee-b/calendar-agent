package com.qiniu.back.module.assistant.service.impl;

import com.qiniu.back.module.assistant.agent.RouteAgent;
import com.qiniu.back.module.assistant.domain.result.AgentTurnResult;
import com.qiniu.back.module.assistant.domain.result.ChatDispatchResult;
import com.qiniu.back.module.assistant.domain.vo.RouteDecision;
import com.qiniu.back.module.assistant.service.*;
import com.qiniu.back.module.assistant.statemachine.*;
import com.qiniu.back.util.ChatSessionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.function.Consumer;

/** 认领会话、识别信号、查表、调用 Agent、提交状态；不包含第二套路由分支。 */
@Service("assistantChatService")
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements ChatService {
    private final ChatDialogueService chatDialogueService;
    private final RouteAgent routeAgent;
    private final AgentFlowStateService flowStateService;
    private final ChatTransitionTable transitionTable;
    private final ConversationAgentDispatcher dispatcher;
    private final ProgressReporter reporter;

    @Override
    public ChatDispatchResult process(Long userId, String sessionId, String message, Long dialogueId) {
        return process(userId, sessionId, message, dialogueId, null);
    }

    @Override
    public ChatDispatchResult process(Long userId, String sessionId, String message, Long dialogueId,
                                      Consumer<String> progress) {
        AgentFlowState state = null;
        ConversationTurn turn = null;
        boolean claimed = false;
        ConversationStage stage = ConversationStage.CHAT;
        try {
            ChatSessionContext.setSessionId(sessionId);
            state = flowStateService.getOrCreate(userId, sessionId);
            stage = flowStateService.resolveStage(state);
            if (!flowStateService.claim(state)) {
                return result("上一项操作正在处理或结果待核实，请不要重复提交。",
                        false, "PROCESSING", "NONE", stage);
            }
            claimed = true;
            String previous = chatDialogueService.findLatestAssistantReply(userId, sessionId, dialogueId);
            AgentFlowState current = state;
            RouteDecision decision = reporter.report(progress, "Route 识别本轮信号",
                    () -> routeAgent.route(message, previous, current));
            UserSignal signal = decision.getUserSignal() == null ? UserSignal.UNKNOWN : decision.getUserSignal();
            ChatTransitionTable.TransitionRule rule = transitionTable.resolve(stage, signal);
            turn = new ConversationTurn(userId, sessionId, message, decision.getTask(), dialogueId,
                    stage, flowStateService.pending(state), progress);
            AgentTurnResult reply = dispatcher.dispatch(rule.agent(), signal, turn);
            if (reply.reply() == null || reply.reply().isBlank()) {
                throw new IllegalStateException("Agent 未返回有效回复");
            }
            ConversationStage next = reply.accepted() ? rule.nextStage() : stage;
            flowStateService.complete(state, next, reply.pending(), rule.agent());
            claimed = false;
            log.info("[会话流转] sessionId={} | {} + {} -> {} | next={}",
                    sessionId, stage, signal, rule.agent(), next);
            return result(reply.reply(), reply.dispatched(), reply.dispatchType(), rule.agent().name(), next);
        } catch (Exception exception) {
            log.error("Assistant chat processing failed", exception);
            boolean uncertain = turn != null && turn.writeStarted();
            if (claimed && !uncertain) {
                try { flowStateService.releaseClaim(state); }
                catch (Exception releaseError) { log.error("Failed to release conversation claim", releaseError); }
            }
            return result(uncertain
                            ? "本次写操作的结果暂时无法确认，系统不会自动重试。请先核实日历结果，再恢复会话。"
                            : "本轮处理失败，原任务仍然保留。你可以重试、修改或取消。",
                    false, "ERROR", "NONE", stage);
        } finally {
            ChatSessionContext.remove();
        }
    }

    private ChatDispatchResult result(String reply, boolean dispatched, String type,
                                      String agent, ConversationStage stage) {
        return new ChatDispatchResult(reply, dispatched, type, agent, flowStateService.owner(stage), stage.name());
    }
}
