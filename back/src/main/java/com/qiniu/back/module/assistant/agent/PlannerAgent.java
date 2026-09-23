package com.qiniu.back.module.assistant.agent;

import com.qiniu.back.module.assistant.config.AgentModelBeans;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.assistant.service.PlanDraftService;
import com.qiniu.back.module.assistant.rag.RagHit;
import com.qiniu.back.module.assistant.rag.RagService;
import com.qiniu.back.module.memory.service.UserMemoryService;
import com.qiniu.back.util.ChatSessionContext;
import com.qiniu.back.util.LoginUserContext;
import com.qiniu.back.util.PromptLoader;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** Generates and persists structured plan drafts. */
@Component
@Slf4j
public class PlannerAgent {

    private final ChatModel plannerChatModel;
    private final PlanDraftService planDraftService;
    private final UserMemoryService userMemoryService;
    private final RagService ragService;
    private AgentRunner runner;

    public PlannerAgent(@Qualifier(AgentModelBeans.PLANNER) ChatModel plannerChatModel,
                        PlanDraftService planDraftService,
                        UserMemoryService userMemoryService,
                        RagService ragService) {
        this.plannerChatModel = plannerChatModel;
        this.planDraftService = planDraftService;
        this.userMemoryService = userMemoryService;
        this.ragService = ragService;
    }

    @PostConstruct
    void init() {
        runner = AgentRunner.builder()
                .name("Planner")
                .chatModel(plannerChatModel)
                .systemPrompt(PromptLoader.load("planner-system.txt"))
                .toolExecutor((name, arguments) -> "[Planner] tool calls are not allowed: " + name)
                .temperature(0.1)
                .maxRounds(1)
                .maxRetries(1)
                .correctionHint("\n\nReturn valid raw JSON only. Do not use markdown fences or extra text.")
                .build();
    }

    public String generate(String requirement) {
        return generateDraft(requirement).preview();
    }

    public GeneratedPlan generateDraft(String requirement) {
        String task = buildContext(requirement) + requirement;
        String planJson = runner.execute(task);
        try {
            return persist(requirement, planJson);
        } catch (BusinessException exception) {
            if (!isRecoverableDateError(exception)) throw exception;
            log.warn("[Planner] draft date invalid, retrying with current date: {}", exception.getMessage());
            String corrected = runner.execute(task + dateCorrectionHint(exception.getMessage()));
            return persist(requirement, corrected);
        }
    }

    public record GeneratedPlan(Long draftId, String preview) { }

    private GeneratedPlan persist(String requirement, String planJson) {
        Long draftId = planDraftService.savePendingDraft(
                LoginUserContext.getUserId(), ChatSessionContext.getSessionId(), requirement, planJson);
        return new GeneratedPlan(draftId, planDraftService.buildPreviewReply(draftId, planJson));
    }

    private String buildContext(String requirement) {
        return dateContext() + memoryContext(requirement) + ragContext(requirement) + "## User requirement\n";
    }

    private String dateContext() {
        LocalDate today = LocalDate.now();
        return "## Current date\n"
                + "today=" + today
                + ", tomorrow=" + today.plusDays(1)
                + ", currentYear=" + today.getYear()
                + "\nUse this date to resolve festivals and relative dates. Do not use a past year.\n\n";
    }

    private boolean isRecoverableDateError(BusinessException exception) {
        String message = exception.getMessage();
        return message != null && (message.contains("year is earlier")
                || message.contains("date range is invalid")
                || message.contains("empty date"));
    }

    private String dateCorrectionHint(String error) {
        return "\n\nPrevious JSON was rejected: " + error
                + " Rewrite the plan using currentYear=" + LocalDate.now().getYear()
                + " and dates on or after " + LocalDate.now()
                + ". Return valid raw JSON only.";
    }

    private String memoryContext(String requirement) {
        try {
            Long userId = LoginUserContext.getUserId();
            return userId == null ? "" : userMemoryService.buildPlannerMemoryContext(userId, requirement);
        } catch (Exception exception) {
            log.warn("[Planner] memory context failed: {}", exception.getMessage());
            return "";
        }
    }

    private String ragContext(String requirement) {
        try {
            List<RagHit> hits = ragService.search(requirement);
            if (hits.isEmpty()) return "";
            StringBuilder context = new StringBuilder("## Reference snippets\n");
            for (RagHit hit : hits) {
                context.append("- (").append(hit.getSection()).append(") ")
                        .append(hit.getText()).append('\n');
            }
            return context.append('\n').toString();
        } catch (Exception exception) {
            log.warn("[Planner] RAG search failed: {}", exception.getMessage());
            return "";
        }
    }
}
