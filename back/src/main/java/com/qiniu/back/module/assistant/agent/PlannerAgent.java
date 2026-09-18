package com.qiniu.back.module.assistant.agent;

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

    public PlannerAgent(@Qualifier("plannerChatModel") ChatModel plannerChatModel,
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
        String planJson = runner.execute(buildContext(requirement) + requirement);
        Long draftId = planDraftService.savePendingDraft(
                LoginUserContext.getUserId(), ChatSessionContext.getSessionId(), requirement, planJson);
        return new GeneratedPlan(draftId, planDraftService.buildPreviewReply(draftId, planJson));
    }

    public record GeneratedPlan(Long draftId, String preview) { }

    private String buildContext(String requirement) {
        return memoryContext(requirement) + ragContext(requirement) + "## User requirement\n";
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
