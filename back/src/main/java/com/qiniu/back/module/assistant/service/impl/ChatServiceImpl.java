package com.qiniu.back.module.assistant.service.impl;

import com.qiniu.back.module.assistant.statemachine.AgentFlowState;
import com.qiniu.back.module.assistant.domain.vo.SupervisorDecision;
import com.qiniu.back.module.assistant.agent.ChatAgent;
import com.qiniu.back.module.assistant.domain.result.ChatDispatchResult;
import com.qiniu.back.module.assistant.statemachine.AgentFlowStateService;
import com.qiniu.back.module.assistant.statemachine.ChatNode;
import com.qiniu.back.module.assistant.statemachine.ChatTransitionTable;
import com.qiniu.back.module.assistant.statemachine.ConversationStage;
import com.qiniu.back.module.assistant.service.ChatService;
import com.qiniu.back.module.assistant.service.ChatContextSummaryService;
import com.qiniu.back.module.assistant.statemachine.ExistingFlowStateProcessor;
import com.qiniu.back.module.assistant.statemachine.UserSignal;
import com.qiniu.back.module.assistant.statemachine.UserSignalResolver;
import com.qiniu.back.module.assistant.service.ProgressReporter;
import com.qiniu.back.util.ChatSessionContext;
import dev.langchain4j.data.message.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Service("assistantChatService")
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements ChatService {

    private final ExistingFlowStateProcessor flowStateProcessor;
    private final ChatContextSummaryService contextSummaryService;
    private final ChatAgent chatAgent;
    private final AgentFlowStateService flowStateService;
    private final ChatTransitionTable transitionTable;
    private final UserSignalResolver signalResolver;
    private final ProgressReporter reporter;

    @Override
    public ChatDispatchResult process(Long userId, String sessionId, String message, Long userDialogueId) {
        return process(userId, sessionId, message, userDialogueId, null);
    }

    @Override
    public ChatDispatchResult process(Long userId, String sessionId, String message, Long userDialogueId,
                                      Consumer<String> progress) {
        try {
            ChatSessionContext.setSessionId(sessionId);

            Optional<ChatDispatchResult> pending = flowStateProcessor.tryHandle(
                    userId, sessionId, message, progress);
            if (pending.isPresent()) return pending.get();

            ensureReadyRoute(message);
            return routeNewRequest(userId, sessionId, message, userDialogueId, progress);
        } catch (Exception exception) {
            log.error("Assistant chat processing failed", exception);
            return failureResult(userId, sessionId);
        } finally {
            ChatSessionContext.remove();
        }
    }

    private void ensureReadyRoute(String message) {
        UserSignal signal = signalResolver.resolve(ConversationStage.READY_FOR_INPUT, null, message);
        ChatNode target = transitionTable.resolve(
                ConversationStage.READY_FOR_INPUT, signal).targetNode();
        if (target != ChatNode.ROUTE_MESSAGE) {
            throw new IllegalStateException("READY 状态必须路由新消息，实际节点: " + target);
        }
    }

    private ChatDispatchResult routeNewRequest(Long userId, String sessionId, String message,
                                               Long userDialogueId, Consumer<String> progress) {
        List<ChatMessage> history = reporter.report(progress, "构建压缩历史上下文",
                () -> contextSummaryService.buildCompressedReadonlyHistory(userId, sessionId, userDialogueId));
        SupervisorDecision decision = reporter.report(progress, "调用 Chat Agent 判断任务类型",
                () -> chatAgent.route(message, history));
        String type = decision.getDispatchType() == null ? "NONE" : decision.getDispatchType();
        String task = isBlank(decision.getTask()) ? message : decision.getTask();

        if (!decision.isNeedDispatchAgent() || "NONE".equals(type)) {
            return result(decision.getReply(), false, "NONE", AgentFlowStateService.AGENT_SUPERVISOR,
                    AgentFlowStateService.AGENT_SUPERVISOR, AgentFlowStateService.STAGE_IDLE);
        }
        return switch (type) {
            case "QUERY" -> result(reporter.report(progress, "调用 Chat Agent 查询日程",
                            () -> chatAgent.query(task)), true, "QUERY",
                    AgentFlowStateService.AGENT_SUPERVISOR, AgentFlowStateService.AGENT_SUPERVISOR,
                    AgentFlowStateService.STAGE_IDLE);
            case "CHAT_ACTION" -> result(
                    reporter.report(progress, "调用 Chat Agent 执行明确的单日操作",
                            () -> chatAgent.executeSingleDay(task)),
                    true, "CHAT_ACTION", AgentFlowStateService.AGENT_CHAT,
                    AgentFlowStateService.AGENT_SUPERVISOR, AgentFlowStateService.STAGE_IDLE);
            case "CHAT_ACTION_CONFIRM" -> {
                reporter.report(progress, "写入单日操作确认状态",
                        () -> flowStateService.waitChatConfirm(userId, sessionId, task));
                String reply = "这个请求的操作意图还不够确定。需要我执行以下单日操作吗？\n" + task;
                yield result(reply, false, "CHAT_ACTION_CONFIRM", AgentFlowStateService.AGENT_CHAT,
                        AgentFlowStateService.AGENT_CHAT, AgentFlowStateService.STAGE_WAIT_CONFIRM);
            }
            case "EXECUTE" -> {
                reporter.report(progress, "写入执行确认状态",
                        () -> flowStateService.waitExecutorConfirm(userId, sessionId, task));
                String reply = isBlank(decision.getReply()) ? "我理解为要执行：" + task + "\n确认要执行吗？"
                        : decision.getReply();
                yield result(reply, false, "EXECUTE_CONFIRM", AgentFlowStateService.AGENT_EXECUTOR,
                        AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_CONFIRM);
            }
            case "PLAN_CONFIRM" -> {
                reporter.report(progress, "写入规划确认状态",
                        () -> flowStateService.waitPlannerConfirm(userId, sessionId, task));
                String reply = isBlank(decision.getReply()) ? "这是一个规划类任务，需要我来帮你规划一下吗？"
                        : decision.getReply();
                yield result(reply, false, "PLAN_CONFIRM", AgentFlowStateService.AGENT_PLANNER,
                        AgentFlowStateService.AGENT_PLANNER, AgentFlowStateService.STAGE_WAIT_CONFIRM);
            }
            default -> result(decision.getReply(), false, "NONE", AgentFlowStateService.AGENT_SUPERVISOR,
                    AgentFlowStateService.AGENT_SUPERVISOR, AgentFlowStateService.STAGE_IDLE);
        };
    }

    private ChatDispatchResult failureResult(Long userId, String sessionId) {
        try {
            AgentFlowState state = flowStateService.get(userId, sessionId).orElse(null);
            if (state != null) {
                if (flowStateService.isProcessing(state)) {
                    return result("这一步的执行结果暂时无法确认。为避免重复操作，系统不会自动重试；"
                                    + "请先检查日历结果，或稍后重新发起任务。",
                            false, "ERROR", state.getCurrentAgent(), state.getNextAgent(), state.getStage());
                }
                return result("抱歉，这一步处理失败了，原任务仍然保留。你可以重试、修改或取消。",
                        false, "ERROR", state.getCurrentAgent(), state.getNextAgent(), state.getStage());
            }
        } catch (Exception stateException) {
            log.warn("Failed to reload conversation state after assistant error", stateException);
        }
        return result("抱歉，我暂时无法处理这个请求，请稍后再试。", false, "NONE",
                AgentFlowStateService.AGENT_SUPERVISOR, AgentFlowStateService.AGENT_SUPERVISOR,
                AgentFlowStateService.STAGE_IDLE);
    }

    private ChatDispatchResult result(String reply, boolean dispatched, String type,
                                      String currentAgent, String nextAgent, String stage) {
        return new ChatDispatchResult(reply, dispatched, type, currentAgent, nextAgent, stage);
    }

    private boolean isBlank(String value) { return value == null || value.isBlank(); }
}
