package com.qiniu.back.module.assistant.service;

import com.qiniu.back.module.assistant.agent.*;
import com.qiniu.back.module.assistant.domain.model.PlanDraft;
import com.qiniu.back.module.assistant.domain.result.AgentTurnResult;
import com.qiniu.back.module.assistant.domain.result.PendingTask;
import com.qiniu.back.module.assistant.statemachine.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Optional;

/** 执行转换表选中的四种 Agent；不另行选路，不直接写会话阶段。 */
@Service
@RequiredArgsConstructor
public class ConversationAgentDispatcher {
    private final ChatAgent chatAgent;
    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final ImageAgent imageAgent;
    private final PlanDraftService planDraftService;
    private final PlanClarificationPolicy clarificationPolicy;
    private final ProgressReporter reporter;

    public AgentTurnResult dispatch(AgentType agent, UserSignal signal, ConversationTurn turn) {
        return switch (agent) {
            case CHAT -> chat(signal, turn);
            case PLANNER -> plan(signal, turn);
            case EXECUTOR -> execute(signal, turn);
            case IMAGE -> image(signal, turn);
        };
    }

    private AgentTurnResult chat(UserSignal signal, ConversationTurn turn) {
        return switch (signal) {
            case NEW_CHAT -> AgentTurnResult.success(
                    reporter.report(turn.progress(), "Chat 回复", () -> chatAgent.chat(turn.message())),
                    "CHAT", true, turn.pending());
            case NEW_QUERY -> AgentTurnResult.success(
                    reporter.report(turn.progress(), "Chat 查询日历", () -> chatAgent.query(turn.task())),
                    "QUERY", true, turn.pending());
            case REJECT -> AgentTurnResult.success("已取消当前任务。你可以继续告诉我新的需求。",
                    "CANCEL", false, PendingTask.empty());
            default -> AgentTurnResult.unchanged(explain(turn.stage()), "PENDING_UNKNOWN", turn.pending());
        };
    }

    private AgentTurnResult plan(UserSignal signal, ConversationTurn turn) {
        if (signal == UserSignal.CONFIRM && turn.pending().draftId() != null) {
            return AgentTurnResult.success("规划草稿已保留。请明确选择“同步到日历”或“生成示意图”，也可以继续修改。",
                    "PLAN_FEEDBACK", false, turn.pending());
        }
        String requirement = signal == UserSignal.NEW_PLAN ? turn.task() : turn.pending().task();
        if (signal == UserSignal.MODIFY) requirement = append(requirement, turn.message());
        if (requirement == null || requirement.isBlank()) {
            return AgentTurnResult.unchanged("请先说明希望制定什么规划。", "PENDING_UNKNOWN", turn.pending());
        }
        if (signal == UserSignal.NEW_PLAN && clarificationPolicy.shouldAsk(
                turn.userId(), turn.sessionId(), requirement, turn.dialogueId())) {
            return AgentTurnResult.success("为了让规划更贴合你，请补充时间、目标或偏好，也可以直接让我按通用方案规划。",
                    "PLAN_CLARIFICATION", false, PendingTask.instruction(requirement));
        }
        String finalRequirement = requirement;
        PlannerAgent.GeneratedPlan generated = reporter.report(turn.progress(), "Planner 生成规划草稿",
                () -> plannerAgent.generateDraft(finalRequirement));
        return AgentTurnResult.success(generated.preview() +
                        "\n\n接下来可以“同步到日历”或“生成示意图”，也可以修改规划。",
                signal == UserSignal.MODIFY ? "PLAN_REFINE" : "PLAN", true,
                new PendingTask(requirement, generated.draftId(), generated.preview(), null));
    }

    private AgentTurnResult execute(UserSignal signal, ConversationTurn turn) {
        if (signal == UserSignal.NEW_EXECUTE || signal == UserSignal.MODIFY) {
            String task = signal == UserSignal.NEW_EXECUTE ? turn.task() : append(turn.pending().task(), turn.message());
            return AgentTurnResult.success("待执行内容：\n" + task + "\n确认执行吗？",
                    signal == UserSignal.NEW_EXECUTE ? "EXECUTE_CONFIRM" : "REFINE",
                    false, PendingTask.instruction(task));
        }
        if (signal == UserSignal.SYNC_PLAN) {
            Optional<PlanDraft> draft = currentDraft(turn);
            if (draft.isEmpty()) return missingDraft(turn);
            turn.markWriteStarted();
            String reply = reporter.report(turn.progress(), "Executor 同步当前规划",
                    () -> executorAgent.applyPlan(draft.get()));
            return AgentTurnResult.success(reply, "EXECUTE", true, PendingTask.empty());
        }
        if (turn.pending().task() == null || turn.pending().task().isBlank()) {
            return AgentTurnResult.unchanged("没有找到待确认的操作，请重新描述任务。",
                    "PENDING_UNKNOWN", turn.pending());
        }
        turn.markWriteStarted();
        ExecutorAgent.ExecutionResult result = reporter.report(turn.progress(), "Executor 执行已确认任务",
                () -> executorAgent.executeConfirmed(turn.pending().task()));
        // 模型可能继续追问；没有实际写工具成功调用时，不清理待确认内容。
        return result.executed()
                ? AgentTurnResult.success(result.reply(), "EXECUTE", true, PendingTask.empty())
                : AgentTurnResult.unchanged(result.reply(), "EXECUTE_CONFIRM", turn.pending());
    }

    private AgentTurnResult image(UserSignal signal, ConversationTurn turn) {
        Optional<PlanDraft> draft = currentDraft(turn);
        if (draft.isEmpty()) return missingDraft(turn);
        String instruction = append(turn.pending().imageInstruction(), turn.message());
        String reply = reporter.report(turn.progress(), "Image 生成规划示意图",
                () -> imageAgent.generate(draft.get(), instruction));
        // 生图和重绘始终绑定原 draftId，后续同步不会读“最新的其他草稿”。
        return AgentTurnResult.success(reply + "\n\n可以继续修改图片，或说“同步到日历”同步原规划。",
                "PLAN_IMAGE", true, new PendingTask(turn.pending().task(), turn.pending().draftId(),
                        turn.pending().planPreview(), instruction));
    }

    private Optional<PlanDraft> currentDraft(ConversationTurn turn) {
        return planDraftService.findPending(turn.userId(), turn.sessionId(), turn.pending().draftId());
    }

    private AgentTurnResult missingDraft(ConversationTurn turn) {
        return AgentTurnResult.unchanged("当前没有可用的规划草稿，请先生成或重新制定规划。",
                "PENDING_UNKNOWN", turn.pending());
    }

    private String append(String original, String feedback) {
        return (original == null ? "" : original) + "\n用户补充/修改：" + feedback;
    }

    private String explain(ConversationStage stage) {
        return switch (stage) {
            case CHAT -> "当前没有待处理任务，请说明你想查询、规划或执行什么操作。";
            case PLAN -> "当前正在处理规划。可以补充条件、同步已有草稿、生成示意图，或取消。";
            case EXECUTE -> "当前有待执行内容，请确认、修改或取消。生成图片和同步规划需要先处理这项任务。";
            case IMAGE -> "图片和原规划均已保留。可以说明图片修改要求、同步原规划，或取消。";
        };
    }
}
