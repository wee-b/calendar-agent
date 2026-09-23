package com.qiniu.back.module.assistant.statemachine;

import com.qiniu.back.module.assistant.domain.model.PlanDraft;
import com.qiniu.back.module.assistant.agent.ExecutorAgent;
import com.qiniu.back.module.assistant.agent.PlannerAgent;
import com.qiniu.back.module.assistant.agent.ImageAgent;
import com.qiniu.back.module.assistant.domain.result.ChatDispatchResult;
import com.qiniu.back.module.assistant.service.PlanDraftService;
import com.qiniu.back.module.assistant.service.ProgressReporter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Consumer;

/** Executes pending-flow transitions from the explicit conversation state table. */
@Service
@RequiredArgsConstructor
public class ExistingFlowStateProcessor {

    private final AgentFlowStateService flowStateService;
    private final PlanDraftService planDraftService;
    private final ChatTransitionTable transitionTable;
    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final ImageAgent imageAgent;
    private final ProgressReporter reporter;

    public Optional<ChatDispatchResult> tryHandle(Long userId, String sessionId, String message,
                                                  AgentFlowState state, UserSignal signal,
                                                  Consumer<String> progress) {
        if (state == null) return Optional.empty();
        if (flowStateService.isProcessing(state)) {
            return Optional.of(result("上一项操作正在处理中，请不要重复提交。",
                    false, "PROCESSING", state.getCurrentAgent(), state.getNextAgent(), state.getStage()));
        }
        ConversationStage stage = flowStateService.resolveStage(state);
        ChatTransitionTable.TransitionRule rule = transitionTable.resolve(stage, signal);
        Request request = new Request(userId, sessionId, message, progress);
        if (rule.targetNode() == ChatNode.EXPLAIN_PENDING_STATE) {
            return Optional.of(execute(rule, signal, state, request));
        }
        if (!flowStateService.claim(state)) {
            return Optional.of(result("上一项操作已被其他请求处理，请稍候查看结果。",
                    false, "PROCESSING", state.getCurrentAgent(), state.getNextAgent(),
                    AgentFlowStateService.STAGE_PROCESSING));
        }
        try {
            return Optional.of(execute(rule, signal, state, request));
        } catch (RuntimeException exception) {
            if (rule.targetNode() != ChatNode.EXECUTE_PENDING_ACTION) {
                flowStateService.releaseClaim(state);
            }
            throw exception;
        }
    }

    private ChatDispatchResult execute(ChatTransitionTable.TransitionRule rule, UserSignal signal,
                                       AgentFlowState state, Request request) {
        return switch (rule.targetNode()) {
            case EXECUTE_PENDING_ACTION -> executePending(state, request);
            case GENERATE_PLAN -> {
                if (signal == UserSignal.MODIFY) {
                    state.setPendingTask(appendFeedback(state.getPendingTask(), request.message()));
                }
                yield generatePlan(state, request, false);
            }
            case APPLY_PLAN -> applyPlan(state, request);
            case GENERATE_PLAN_IMAGE -> generatePlanImage(state, request);
            case MODIFY_PENDING_ACTION -> modifyPending(state, request);
            case REVISE_PLAN -> revisePlan(state, request);
            case CANCEL_PENDING_ACTION -> cancel(state, request);
            case EXPLAIN_PENDING_STATE -> explainPending(state, signal);
            case CHAT_DIALOGUE, QUERY_CALENDAR, EXECUTE_SINGLE_DAY_ACTION,
                 PREPARE_SINGLE_DAY_CONFIRMATION, PREPARE_EXECUTION_CONFIRMATION,
                 PREPARE_PLAN_CONFIRMATION ->
                    throw new IllegalStateException("pending 状态不能执行 READY 节点");
        };
    }

    /** Starts a newly recognized planning task without an unnecessary confirmation round. */
    public ChatDispatchResult startPlan(Long userId, String sessionId, String requirement,
                                        Consumer<String> progress) {
        AgentFlowState state = new AgentFlowState();
        state.setPendingTask(requirement);
        return generatePlan(state, new Request(userId, sessionId, requirement, progress), false);
    }

    private ChatDispatchResult executePending(AgentFlowState state, Request request) {
        String result = reporter.report(request.progress(), "调用 Executor 执行任务",
                () -> executorAgent.execute(state.getPendingTask()));
        clear(request);
        return result(result, true, "EXECUTE", AgentFlowStateService.AGENT_EXECUTOR,
                AgentFlowStateService.AGENT_NONE, AgentFlowStateService.STAGE_IDLE);
    }

    private ChatDispatchResult generatePlan(AgentFlowState state, Request request, boolean revision) {
        String step = revision ? "调用 Planner 重新生成规划草稿" : "调用 Planner 生成规划草稿";
        PlannerAgent.GeneratedPlan generated = reporter.report(request.progress(), step,
                () -> plannerAgent.generateDraft(state.getPendingTask()));
        AgentFlowState nextState = reporter.report(request.progress(), "写入规划反馈状态",
                () -> flowStateService.waitPlanFeedback(request.userId(), request.sessionId(),
                        state.getPendingTask(), generated.draftId(), generated.preview()));
        return result(buildPlanReply(generated.preview()), true, revision ? "PLAN_REFINE" : "PLAN",
                AgentFlowStateService.AGENT_PLANNER, AgentFlowStateService.AGENT_EXECUTOR, nextState.getStage());
    }

    private ChatDispatchResult applyPlan(AgentFlowState state, Request request) {
        Optional<PlanDraft> draft = reporter.report(request.progress(), "读取待同步规划草稿",
                () -> planDraftService.findPending(
                        request.userId(), request.sessionId(), state.getPendingDraftId()));
        String reply = draft.map(value -> reporter.report(request.progress(), "同步规划草稿到日历",
                        () -> executorAgent.applyPlan(value)))
                .orElse("没有找到待同步的规划草稿，先回到对话状态。");
        clear(request);
        return result(reply, true, "EXECUTE", AgentFlowStateService.AGENT_EXECUTOR,
                AgentFlowStateService.AGENT_NONE, AgentFlowStateService.STAGE_IDLE);
    }

    private ChatDispatchResult generatePlanImage(AgentFlowState state, Request request) {
        Optional<PlanDraft> draft = reporter.report(request.progress(), "读取待生成示意图的规划草稿",
                () -> planDraftService.findPending(
                        request.userId(), request.sessionId(), state.getPendingDraftId()));
        String reply = draft.map(value -> reporter.report(request.progress(), "生成规划示意图",
                        () -> imageAgent.generate(value)))
                .orElse("没有找到待生成示意图的规划草稿，先回到对话状态。");
        clear(request);
        return result(reply, true, "PLAN_IMAGE", AgentFlowStateService.AGENT_PLANNER,
                AgentFlowStateService.AGENT_NONE, AgentFlowStateService.STAGE_IDLE);
    }

    private ChatDispatchResult modifyPending(AgentFlowState state, Request request) {
        AgentFlowState updated = updatePending(state, request);
        ConversationStage currentStage = flowStateService.resolveStage(updated);
        String reply = currentStage == ConversationStage.AWAITING_PLAN_CONFIRMATION
                ? "已按你的补充更新规划需求。需要我现在开始规划吗？"
                : "已按你的补充更新待执行任务。确认要执行吗？";
        return result(reply, false, "REFINE", updated.getCurrentAgent(), updated.getNextAgent(), updated.getStage());
    }

    private ChatDispatchResult revisePlan(AgentFlowState state, Request request) {
        state.setPendingTask(appendFeedback(state.getPendingTask(), request.message()));
        return generatePlan(state, request, true);
    }

    private ChatDispatchResult cancel(AgentFlowState state, Request request) {
        ConversationStage previousStage = flowStateService.resolveStage(state);
        clear(request);
        String reply = previousStage == ConversationStage.AWAITING_PLAN_FEEDBACK
                ? "好的，已放弃当前规划，不会同步到日历。"
                : "好的，已取消这一步。你可以继续告诉我新的日程需求。";
        return result(reply, false, "CANCEL", state.getCurrentAgent(),
                AgentFlowStateService.AGENT_NONE, AgentFlowStateService.STAGE_IDLE);
    }

    private ChatDispatchResult explainPending(AgentFlowState state, UserSignal signal) {
        ConversationStage currentStage = flowStateService.resolveStage(state);
        String subject = currentStage == ConversationStage.AWAITING_EXECUTION_CONFIRMATION ? "待执行任务" : "规划";
        String action = currentStage == ConversationStage.AWAITING_PLAN_FEEDBACK
                ? "明确选择“同步到日历”或“生成示意图”，也可以取消或修改当前规划"
                : "确认、取消或补充上一项" + subject;
        String prefix = signal == UserSignal.NEW_REQUEST
                ? "当前还有一项未完成的" + subject + "，所以没有处理这条新请求。"
                : "我还在等待你处理上一项" + subject + "。";
        return result(prefix + "请先" + action + "。", false,
                signal == UserSignal.NEW_REQUEST ? "PENDING_NEW_REQUEST" : "PENDING_UNKNOWN",
                state.getCurrentAgent(), state.getNextAgent(), state.getStage());
    }

    private AgentFlowState updatePending(AgentFlowState state, Request request) {
        return reporter.report(request.progress(), "更新待确认任务内容",
                () -> flowStateService.updatePendingTask(request.userId(), request.sessionId(), state, request.message()));
    }

    private String appendFeedback(String pendingTask, String feedback) {
        String base = pendingTask == null ? "" : pendingTask;
        return base + "\n用户补充/修改：" + feedback;
    }

    private void clear(Request request) {
        reporter.report(request.progress(), "清理待确认任务状态",
                () -> flowStateService.clear(request.userId(), request.sessionId()));
    }

    private ChatDispatchResult result(String reply, boolean dispatched, String type,
                                      String currentAgent, String nextAgent, String stage) {
        return new ChatDispatchResult(reply, dispatched, type, currentAgent, nextAgent, stage);
    }

    private String buildPlanReply(String planResult) {
        return planResult + "\n\n接下来需要我**同步到日历**，还是**生成一张规划示意图**？";
    }

    private record Request(Long userId, String sessionId, String message, Consumer<String> progress) { }
}
