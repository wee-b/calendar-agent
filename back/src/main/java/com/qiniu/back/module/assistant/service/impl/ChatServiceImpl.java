package com.qiniu.back.module.assistant.service.impl;

import com.qiniu.back.module.assistant.statemachine.AgentFlowState;
import com.qiniu.back.module.assistant.domain.vo.RouteDecision;
import com.qiniu.back.module.assistant.agent.ChatAgent;
import com.qiniu.back.module.assistant.agent.ExecutorAgent;
import com.qiniu.back.module.assistant.agent.RouteAgent;
import com.qiniu.back.module.assistant.domain.result.ChatDispatchResult;
import com.qiniu.back.module.assistant.statemachine.AgentFlowStateService;
import com.qiniu.back.module.assistant.statemachine.ChatNode;
import com.qiniu.back.module.assistant.statemachine.ChatTransitionTable;
import com.qiniu.back.module.assistant.statemachine.ConversationStage;
import com.qiniu.back.module.assistant.service.ChatService;
import com.qiniu.back.module.assistant.service.ChatDialogueService;
import com.qiniu.back.module.assistant.statemachine.ExistingFlowStateProcessor;
import com.qiniu.back.module.assistant.statemachine.UserSignal;
import com.qiniu.back.module.assistant.service.ProgressReporter;
import com.qiniu.back.module.assistant.service.PlanClarificationPolicy;
import com.qiniu.back.util.ChatSessionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Consumer;

@Service("assistantChatService")
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements ChatService {

    private final ExistingFlowStateProcessor flowStateProcessor;
    private final ChatDialogueService chatDialogueService;
    private final ChatAgent chatAgent;
    private final ExecutorAgent executorAgent;
    private final RouteAgent routeAgent;
    private final AgentFlowStateService flowStateService;
    private final ChatTransitionTable transitionTable;
    private final ProgressReporter reporter;
    private final PlanClarificationPolicy planClarificationPolicy;

    @Override
    public ChatDispatchResult process(Long userId, String sessionId, String message, Long userDialogueId) {
        return process(userId, sessionId, message, userDialogueId, null);
    }

    @Override
    public ChatDispatchResult process(Long userId, String sessionId, String message, Long userDialogueId,
                                      Consumer<String> progress) {
        try {
            ChatSessionContext.setSessionId(sessionId);

            AgentFlowState state = reporter.report(progress, "读取当前会话流程状态",
                    () -> flowStateService.get(userId, sessionId).orElse(null));
            String previousAssistantReply = reporter.report(progress, "读取上一轮助手回复",
                    () -> chatDialogueService.findLatestAssistantReply(userId, sessionId, userDialogueId));
            RouteDecision decision = reporter.report(progress, "调用 Route Agent 结合上一轮回复生成结构化路由事件",
                    () -> routeAgent.route(message, previousAssistantReply, state));

            Optional<ChatDispatchResult> pending = reporter.report(progress, "状态机消费结构化路由事件",
                    () -> flowStateProcessor.tryHandle(
                            userId, sessionId, message, state, decision.getUserSignal(), progress));
            ChatDispatchResult result = pending.orElseGet(
                    () -> executeReadyTransition(userId, sessionId, message, userDialogueId, decision, progress));
            logConversationTrace(sessionId, decision, result);
            return result;
        } catch (Exception exception) {
            log.error("Assistant chat processing failed", exception);
            ChatDispatchResult result = failureResult(userId, sessionId);
            logConversationTrace(sessionId, null, result);
            return result;
        } finally {
            ChatSessionContext.remove();
        }
    }

    private ChatDispatchResult executeReadyTransition(Long userId, String sessionId, String message,
                                                      Long userDialogueId,
                                                      RouteDecision decision,
                                                      Consumer<String> progress) {
        UserSignal signal = decision.getUserSignal() == null ? UserSignal.READY_CHAT : decision.getUserSignal();
        ChatNode target = transitionTable.resolve(
                ConversationStage.READY_FOR_INPUT, signal).targetNode();
        String task = isBlank(decision.getTask()) ? message : decision.getTask();

        return switch (target) {
            case CHAT_DIALOGUE -> result(reporter.report(progress, "状态机调用 Chat Agent 回复闲聊",
                            () -> chatAgent.chat(message)), true, "CHAT",
                    AgentFlowStateService.AGENT_CHAT, AgentFlowStateService.AGENT_NONE,
                    AgentFlowStateService.STAGE_IDLE);
            case QUERY_CALENDAR -> result(reporter.report(progress, "状态机调用 Chat Agent 查询日程",
                            () -> chatAgent.query(task)), true, "QUERY",
                    AgentFlowStateService.AGENT_CHAT, AgentFlowStateService.AGENT_NONE,
                    AgentFlowStateService.STAGE_IDLE);
            case EXECUTE_SINGLE_DAY_ACTION -> result(
                    reporter.report(progress, "状态机调用 Executor 执行明确的单日操作",
                            () -> executorAgent.execute(task)),
                    true, "CHAT_ACTION", AgentFlowStateService.AGENT_EXECUTOR,
                    AgentFlowStateService.AGENT_NONE, AgentFlowStateService.STAGE_IDLE);
            case PREPARE_SINGLE_DAY_CONFIRMATION -> {
                reporter.report(progress, "写入单日操作确认状态",
                        () -> flowStateService.waitExecutorConfirm(userId, sessionId, task));
                String reply = "这个请求的操作意图还不够确定。需要我执行以下单日操作吗？\n" + task;
                yield result(reply, false, "CHAT_ACTION_CONFIRM", AgentFlowStateService.AGENT_EXECUTOR,
                        AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.STAGE_WAIT_CONFIRM);
            }
            case PREPARE_EXECUTION_CONFIRMATION -> {
                reporter.report(progress, "写入执行确认状态",
                        () -> flowStateService.waitExecutorConfirm(userId, sessionId, task));
                yield result("我理解为要执行：" + task + "\n确认要执行吗？", false, "EXECUTE_CONFIRM",
                        AgentFlowStateService.AGENT_EXECUTOR, AgentFlowStateService.AGENT_EXECUTOR,
                        AgentFlowStateService.STAGE_WAIT_CONFIRM);
            }
            case PREPARE_PLAN_CONFIRMATION -> {
                if (!planClarificationPolicy.shouldAsk(userId, sessionId, task, userDialogueId)) {
                    yield flowStateProcessor.startPlan(userId, sessionId, task, progress);
                }
                reporter.report(progress, "写入规划确认状态",
                        () -> flowStateService.waitPlannerConfirm(userId, sessionId, task));
                yield result("为了让规划更贴合你，我还缺少一些关键信息。你可以补充，也可以直接让我按通用方案规划。",
                        false, "PLAN_CLARIFICATION",
                        AgentFlowStateService.AGENT_PLANNER, AgentFlowStateService.AGENT_PLANNER,
                        AgentFlowStateService.STAGE_WAIT_CONFIRM);
            }
            default -> throw new IllegalStateException("READY 状态不能执行节点: " + target);
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
                AgentFlowStateService.AGENT_NONE, AgentFlowStateService.AGENT_NONE,
                AgentFlowStateService.STAGE_IDLE);
    }

    private ChatDispatchResult result(String reply, boolean dispatched, String type,
                                      String currentAgent, String nextAgent, String stage) {
        return new ChatDispatchResult(reply, dispatched, type, currentAgent, nextAgent, stage);
    }

    /** 按实际处理节点逐行输出；同一 sessionId 的日志顺序即为 Agent 流转顺序。 */
    private void logConversationTrace(String sessionId, RouteDecision decision, ChatDispatchResult result) {
        log.info("[对话链路] sessionId={} | Agent=RouteAgent | 阶段=路由 | 事件={}",
                sessionId,
                decision == null || decision.getUserSignal() == null ? "ERROR" : decision.getUserSignal());
        log.info("[对话链路] sessionId={} | Agent={} | 阶段=最终回复 | 类型={}",
                sessionId,
                result.needDispatchAgent() ? agentName(result.currentAgent()) : "系统",
                result.dispatchType());
    }

    private String agentName(String agent) {
        if (AgentFlowStateService.AGENT_CHAT.equals(agent)) return "ChatAgent";
        if (AgentFlowStateService.AGENT_PLANNER.equals(agent)) return "PlannerAgent";
        if (AgentFlowStateService.AGENT_EXECUTOR.equals(agent)) return "ExecutorAgent";
        return "系统";
    }

    private boolean isBlank(String value) { return value == null || value.isBlank(); }
}
