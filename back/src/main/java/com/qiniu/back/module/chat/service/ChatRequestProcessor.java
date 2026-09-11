package com.qiniu.back.module.chat.service;

import com.qiniu.back.domain.chat.AgentFlowState;
import com.qiniu.back.domain.chat.PlanDraft;
import com.qiniu.back.domain.chat.vo.SupervisorDecision;
import com.qiniu.back.module.chat.agent.AgentOrchestrator;
import com.qiniu.back.module.chat.agent.SupervisorTools;
import com.qiniu.back.module.chat.service.DirectCommandService.DirectCommandResult;
import com.qiniu.back.util.ChatSessionContext;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
@Service
public class ChatRequestProcessor {

    @Autowired
    private AgentOrchestrator agentOrchestrator;

    @Autowired
    private PlanDraftService planDraftService;

    @Autowired
    private DirectCommandService directCommandService;

    @Autowired
    private AgentFlowStateService agentFlowStateService;

    @Autowired
    private SupervisorTools supervisorTools;

    @Autowired
    private ChatContextSummaryService chatContextSummaryService;

    public ChatDispatchResult process(Long userId, String sessionId, String message, Long userDialogueId) {
        return process(userId, sessionId, message, userDialogueId, null);
    }

    public ChatDispatchResult process(Long userId, String sessionId, String message, Long userDialogueId,
                                      Consumer<String> progress) {
        try {
            ChatSessionContext.setSessionId(sessionId);

            Optional<ChatDispatchResult> flowResult = handleExistingFlowState(userId, sessionId, message, progress);
            if (flowResult.isPresent()) return flowResult.get();

            Optional<ChatDispatchResult> draftResult = syncPendingDraftIfConfirmed(userId, sessionId, message, progress);
            if (draftResult.isPresent()) return draftResult.get();

            Optional<ChatDispatchResult> directResult = handleDirectCommand(message, progress);
            if (directResult.isPresent()) return directResult.get();

            List<ChatMessage> history = report(progress, "构建压缩历史上下文",
                    () -> chatContextSummaryService.buildCompressedReadonlyHistory(userId, sessionId, userDialogueId));
            return dispatchBySupervisor(userId, sessionId, message, history, progress);
        } catch (Exception e) {
            log.error("Agent orchestration failed", e);
            return new ChatDispatchResult("抱歉，我暂时无法处理这个请求，请稍后再试。", false, "NONE",
                    AgentFlowStateService.AGENT_SUPERVISOR,
                    AgentFlowStateService.AGENT_SUPERVISOR,
                    AgentFlowStateService.STAGE_IDLE);
        } finally {
            ChatSessionContext.remove();
        }
    }

    private Optional<ChatDispatchResult> syncPendingDraftIfConfirmed(Long userId, String sessionId, String message,
                                                                     Consumer<String> progress) {
        boolean confirmPlanDraft = report(progress, "检查规划草稿确认语义",
                () -> planDraftService.isConfirmMessage(message));
        if (!confirmPlanDraft) return Optional.empty();

        Optional<PlanDraft> pendingDraft = report(progress, "读取待同步规划草稿",
                () -> planDraftService.findLatestPending(userId, sessionId));
        if (pendingDraft.isEmpty()) return Optional.empty();

        String aiResult = report(progress, "同步规划草稿到日历",
                () -> planDraftService.buildSyncReply(planDraftService.syncDraft(pendingDraft.get())));
        return Optional.of(new ChatDispatchResult(aiResult, true, "EXECUTE",
                AgentFlowStateService.AGENT_EXECUTOR,
                AgentFlowStateService.AGENT_SUPERVISOR,
                AgentFlowStateService.STAGE_IDLE));
    }

    private Optional<ChatDispatchResult> handleDirectCommand(String message, Consumer<String> progress) {
        DirectCommandResult directResult = report(progress, "尝试快捷指令匹配",
                () -> directCommandService.tryHandle(message));
        if (directResult.shouldReturnDirectly()) {
            return Optional.of(new ChatDispatchResult(directResult.reply(), false, "DIRECT",
                    AgentFlowStateService.AGENT_SUPERVISOR,
                    AgentFlowStateService.AGENT_SUPERVISOR,
                    AgentFlowStateService.STAGE_IDLE));
        }
        if (directResult.shouldFallbackToSupervisor()) {
            log.info("[DirectCommand] fallback to supervisor: {}", directResult.reply());
            sendStepStart(progress, "交给 Supervisor 兜底");
            sendStepSuccess(progress, "交给 Supervisor 兜底");
        }
        return Optional.empty();
    }

    private Optional<ChatDispatchResult> handleExistingFlowState(Long userId, String sessionId, String message,
                                                                 Consumer<String> progress) {
        Optional<AgentFlowState> optionalState = report(progress, "读取待确认任务状态",
                () -> agentFlowStateService.get(userId, sessionId));
        if (optionalState.isEmpty()) return Optional.empty();

        AgentFlowState state = optionalState.get();
        if (agentFlowStateService.isRejectMessage(message)) {
            report(progress, "清理待确认任务状态", () -> agentFlowStateService.clear(userId, sessionId));
            return Optional.of(new ChatDispatchResult(
                    "好的，已取消这一步。你可以继续告诉我新的日程需求。",
                    false,
                    "CANCEL",
                    state.getCurrentAgent(),
                    AgentFlowStateService.AGENT_SUPERVISOR,
                    AgentFlowStateService.STAGE_IDLE));
        }

        if (planDraftService.isConfirmMessage(message)) {
            return Optional.of(confirmFlowState(userId, sessionId, state, progress));
        }

        return Optional.of(refineFlowState(userId, sessionId, state, message, progress));
    }

    private ChatDispatchResult confirmFlowState(Long userId, String sessionId, AgentFlowState state,
                                                Consumer<String> progress) {
        String nextAgent = state.getNextAgent();
        if (AgentFlowStateService.AGENT_PLANNER.equals(nextAgent)) {
            String planResult = report(progress, "调用 Planner 生成规划草稿",
                    () -> supervisorTools.planTask(state.getPendingTask()));
            report(progress, "写入规划反馈状态",
                    () -> agentFlowStateService.waitPlanFeedback(userId, sessionId, state.getPendingTask(), planResult));
            return new ChatDispatchResult(
                    buildPlanReply(planResult),
                    true,
                    "PLAN",
                    AgentFlowStateService.AGENT_PLANNER,
                    AgentFlowStateService.AGENT_EXECUTOR,
                    AgentFlowStateService.STAGE_WAIT_FEEDBACK);
        }

        if (AgentFlowStateService.AGENT_EXECUTOR.equals(nextAgent)) {
            String aiResult;
            if (AgentFlowStateService.AGENT_PLANNER.equals(state.getCurrentAgent())) {
                Optional<PlanDraft> pendingDraft = report(progress, "读取待同步规划草稿",
                        () -> planDraftService.findLatestPending(userId, sessionId));
                aiResult = pendingDraft
                        .map(draft -> report(progress, "同步规划草稿到日历",
                                () -> planDraftService.buildSyncReply(planDraftService.syncDraft(draft))))
                        .orElse("没有找到待同步的规划草稿，先回到对话状态。");
            } else {
                aiResult = report(progress, "调用 Executor 执行任务",
                        () -> supervisorTools.executeTask(state.getPendingTask()));
            }
            report(progress, "清理待确认任务状态", () -> agentFlowStateService.clear(userId, sessionId));
            return new ChatDispatchResult(
                    aiResult,
                    true,
                    "EXECUTE",
                    AgentFlowStateService.AGENT_EXECUTOR,
                    AgentFlowStateService.AGENT_SUPERVISOR,
                    AgentFlowStateService.STAGE_IDLE);
        }

        report(progress, "清理待确认任务状态", () -> agentFlowStateService.clear(userId, sessionId));
        return new ChatDispatchResult(
                "好的，我们继续。",
                false,
                "NONE",
                AgentFlowStateService.AGENT_SUPERVISOR,
                AgentFlowStateService.AGENT_SUPERVISOR,
                AgentFlowStateService.STAGE_IDLE);
    }

    private ChatDispatchResult refineFlowState(Long userId, String sessionId, AgentFlowState state, String message,
                                               Consumer<String> progress) {
        AgentFlowState refinedState = report(progress, "更新待确认任务内容",
                () -> agentFlowStateService.updatePendingTask(userId, sessionId, state, message));

        if (AgentFlowStateService.AGENT_PLANNER.equals(refinedState.getCurrentAgent())
                && AgentFlowStateService.AGENT_EXECUTOR.equals(refinedState.getNextAgent())) {
            String planResult = report(progress, "调用 Planner 重新生成规划草稿",
                    () -> supervisorTools.planTask(refinedState.getPendingTask()));
            report(progress, "写入规划反馈状态",
                    () -> agentFlowStateService.waitPlanFeedback(userId, sessionId, refinedState.getPendingTask(), planResult));
            return new ChatDispatchResult(
                    buildPlanReply(planResult),
                    true,
                    "PLAN_REFINE",
                    AgentFlowStateService.AGENT_PLANNER,
                    AgentFlowStateService.AGENT_EXECUTOR,
                    AgentFlowStateService.STAGE_WAIT_FEEDBACK);
        }

        String reply = AgentFlowStateService.AGENT_PLANNER.equals(refinedState.getCurrentAgent())
                ? "已按你的补充更新规划需求。需要我现在开始规划吗？"
                : "已按你的补充更新待执行任务。确认要执行吗？";
        return new ChatDispatchResult(
                reply,
                false,
                "REFINE",
                refinedState.getCurrentAgent(),
                refinedState.getNextAgent(),
                refinedState.getStage());
    }

    private ChatDispatchResult dispatchBySupervisor(Long userId, String sessionId, String message,
                                                    List<ChatMessage> history, Consumer<String> progress) {
        SupervisorDecision decision = report(progress, "调用 Supervisor 判断任务类型",
                () -> agentOrchestrator.supervise(message, history));
        String dispatchType = decision.getDispatchType() == null ? "NONE" : decision.getDispatchType();
        String task = decision.getTask() == null || decision.getTask().isBlank() ? message : decision.getTask();

        if (!decision.isNeedDispatchAgent() || "NONE".equals(dispatchType)) {
            sendStepStart(progress, "生成直接回复");
            sendStepSuccess(progress, "生成直接回复");
            return new ChatDispatchResult(decision.getReply(), false, "NONE",
                    AgentFlowStateService.AGENT_SUPERVISOR,
                    AgentFlowStateService.AGENT_SUPERVISOR,
                    AgentFlowStateService.STAGE_IDLE);
        }

        return switch (dispatchType) {
            case "QUERY" -> {
                String aiResult = report(progress, "调用 Query Agent 查询日程",
                        () -> supervisorTools.queryCalendar(task));
                yield new ChatDispatchResult(aiResult, true, "QUERY",
                        AgentFlowStateService.AGENT_SUPERVISOR,
                        AgentFlowStateService.AGENT_SUPERVISOR,
                        AgentFlowStateService.STAGE_IDLE);
            }
            case "EXECUTE" -> {
                report(progress, "写入执行确认状态",
                        () -> agentFlowStateService.waitExecutorConfirm(userId, sessionId, task));
                String reply = decision.getReply() == null || decision.getReply().isBlank()
                        ? "我理解为要执行：" + task + "\n确认要执行吗？"
                        : decision.getReply();
                yield new ChatDispatchResult(reply, false, "EXECUTE_CONFIRM",
                        AgentFlowStateService.AGENT_EXECUTOR,
                        AgentFlowStateService.AGENT_EXECUTOR,
                        AgentFlowStateService.STAGE_WAIT_CONFIRM);
            }
            case "PLAN_CONFIRM" -> {
                report(progress, "写入规划确认状态",
                        () -> agentFlowStateService.waitPlannerConfirm(userId, sessionId, task));
                String reply = decision.getReply() == null || decision.getReply().isBlank()
                        ? "这是一个规划类任务，需要我来帮你规划一下吗？"
                        : decision.getReply();
                yield new ChatDispatchResult(reply, false, "PLAN_CONFIRM",
                        AgentFlowStateService.AGENT_PLANNER,
                        AgentFlowStateService.AGENT_PLANNER,
                        AgentFlowStateService.STAGE_WAIT_CONFIRM);
            }
            default -> {
                sendStepStart(progress, "生成默认回复");
                sendStepSuccess(progress, "生成默认回复");
                yield new ChatDispatchResult(decision.getReply(), false, "NONE",
                        AgentFlowStateService.AGENT_SUPERVISOR,
                        AgentFlowStateService.AGENT_SUPERVISOR,
                        AgentFlowStateService.STAGE_IDLE);
            }
        };
    }

    private String buildPlanReply(String planResult) {
        return planResult + "\n\n需要同步到日历中吗？";
    }

    private <T> T report(Consumer<String> progress, String step, Supplier<T> action) {
        sendStepStart(progress, step);
        try {
            T result = action.get();
            sendStepSuccess(progress, step);
            return result;
        } catch (RuntimeException e) {
            sendStepFailure(progress, step);
            throw e;
        }
    }

    private void report(Consumer<String> progress, String step, Runnable action) {
        report(progress, step, () -> {
            action.run();
            return null;
        });
    }

    private void sendStepStart(Consumer<String> progress, String step) {
        if (progress != null) progress.accept("开始：" + step);
    }

    private void sendStepSuccess(Consumer<String> progress, String step) {
        if (progress != null) progress.accept("完成：" + step);
    }

    private void sendStepFailure(Consumer<String> progress, String step) {
        if (progress != null) progress.accept("失败：" + step);
    }
}
