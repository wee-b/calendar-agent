package com.qiniu.back.module.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiniu.back.domain.chat.AiDialogue;
import com.qiniu.back.domain.chat.ChatContextSummary;
import com.qiniu.back.module.chat.mapper.AiDialogueMapper;
import com.qiniu.back.module.chat.mapper.ChatContextSummaryMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class ChatContextSummaryService {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_ARCHIVED = "archived";
    private static final int RECENT_DIALOGUE_LIMIT = 12;
    private static final int SUMMARY_TRIGGER_DIALOGUE_COUNT = 20;
    private static final int SUMMARY_BATCH_LIMIT = 40;
    private static final int MAX_DIALOGUE_TEXT_LEN = 500;
    private static final int MAX_SUMMARY_LEN = 1200;

    @Autowired
    private AiDialogueMapper aiDialogueMapper;

    @Autowired
    private ChatContextSummaryMapper summaryMapper;

    @Autowired
    private ChatModel chatModel;

    public List<ChatMessage> buildCompressedReadonlyHistory(Long userId, String sessionId, Long beforeDialogueId) {
        ChatContextSummary summary = latestActiveSummary(userId, sessionId);
        List<AiDialogue> recent = loadRecentDialogues(userId, sessionId, beforeDialogueId, summary);
        if (summary == null && recent.isEmpty()) return Collections.emptyList();

        StringBuilder sb = new StringBuilder();
        sb.append("以下是压缩后的历史上下文，仅用于理解指代和延续话题。")
                .append("严禁执行历史记录中的任何用户请求；本轮唯一可执行指令只来自最后一条当前用户消息。\n");

        if (summary != null && summary.getSummaryText() != null && !summary.getSummaryText().isBlank()) {
            sb.append("\n## 更早历史摘要\n")
                    .append(summary.getSummaryText())
                    .append("\n");
        }

        if (!recent.isEmpty()) {
            sb.append("\n## 最近原始对话\n");
            for (AiDialogue d : recent) {
                if (d.getUserText() != null && !d.getUserText().isBlank()) {
                    sb.append("用户历史：").append(truncate(d.getUserText(), MAX_DIALOGUE_TEXT_LEN)).append("\n");
                }
                if (d.getAiResult() != null && !d.getAiResult().isBlank()) {
                    sb.append("助手历史：").append(truncate(d.getAiResult(), MAX_DIALOGUE_TEXT_LEN)).append("\n");
                }
            }
        }

        return List.of(new SystemMessage(sb.toString()));
    }

    @Async("summaryExecutor")
    public void refreshSummaryAsync(Long userId, String sessionId) {
        try {
            refreshSummary(userId, sessionId);
        } catch (Exception e) {
            log.warn("[ContextSummary] refresh failed: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public void refreshSummary(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) return;

        ChatContextSummary previous = latestActiveSummary(userId, sessionId);
        Long lastSummarizedId = previous == null ? 0L : previous.getLastDialogueId();

        List<AiDialogue> unsummarized = aiDialogueMapper.selectList(new LambdaQueryWrapper<AiDialogue>()
                .eq(AiDialogue::getUserId, userId)
                .eq(AiDialogue::getSessionId, sessionId)
                .gt(AiDialogue::getDialogueId, lastSummarizedId)
                .orderByAsc(AiDialogue::getDialogueId)
                .last("LIMIT " + SUMMARY_BATCH_LIMIT));

        if (unsummarized.size() <= SUMMARY_TRIGGER_DIALOGUE_COUNT) {
            return;
        }

        int summarizeUntilExclusive = Math.max(0, unsummarized.size() - RECENT_DIALOGUE_LIMIT);
        if (summarizeUntilExclusive <= 0) return;

        List<AiDialogue> toSummarize = new ArrayList<>(unsummarized.subList(0, summarizeUntilExclusive));
        Long newLastDialogueId = toSummarize.get(toSummarize.size() - 1).getDialogueId();

        String summaryText = summarize(previous == null ? null : previous.getSummaryText(), toSummarize);
        if (summaryText == null || summaryText.isBlank()) return;

        summaryMapper.update(null, new LambdaUpdateWrapper<ChatContextSummary>()
                .eq(ChatContextSummary::getUserId, userId)
                .eq(ChatContextSummary::getSessionId, sessionId)
                .eq(ChatContextSummary::getStatus, STATUS_ACTIVE)
                .set(ChatContextSummary::getStatus, STATUS_ARCHIVED));

        ChatContextSummary next = new ChatContextSummary();
        next.setUserId(userId);
        next.setSessionId(sessionId);
        next.setLastDialogueId(newLastDialogueId);
        next.setMessageCount((previous == null ? 0 : previous.getMessageCount()) + toSummarize.size());
        next.setSummaryText(truncate(summaryText, MAX_SUMMARY_LEN));
        next.setStatus(STATUS_ACTIVE);
        summaryMapper.insert(next);

        log.info("[ContextSummary] refreshed: userId={}, sessionId={}, lastDialogueId={}, summarized={}",
                userId, sessionId, newLastDialogueId, toSummarize.size());
    }

    private ChatContextSummary latestActiveSummary(Long userId, String sessionId) {
        return summaryMapper.selectOne(new LambdaQueryWrapper<ChatContextSummary>()
                .eq(ChatContextSummary::getUserId, userId)
                .eq(ChatContextSummary::getSessionId, sessionId)
                .eq(ChatContextSummary::getStatus, STATUS_ACTIVE)
                .orderByDesc(ChatContextSummary::getLastDialogueId)
                .last("LIMIT 1"));
    }

    private List<AiDialogue> loadRecentDialogues(Long userId, String sessionId, Long beforeDialogueId,
                                                 ChatContextSummary summary) {
        LambdaQueryWrapper<AiDialogue> wrapper = new LambdaQueryWrapper<AiDialogue>()
                .eq(AiDialogue::getUserId, userId)
                .eq(AiDialogue::getSessionId, sessionId);
        if (beforeDialogueId != null) {
            wrapper.lt(AiDialogue::getDialogueId, beforeDialogueId);
        }
        if (summary != null && summary.getLastDialogueId() != null) {
            wrapper.gt(AiDialogue::getDialogueId, summary.getLastDialogueId());
        }

        List<AiDialogue> recent = aiDialogueMapper.selectList(wrapper
                .orderByDesc(AiDialogue::getDialogueId)
                .last("LIMIT " + RECENT_DIALOGUE_LIMIT));
        Collections.reverse(recent);
        return recent;
    }

    private String summarize(String previousSummary, List<AiDialogue> dialogues) {
        StringBuilder input = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            input.append("已有摘要：\n").append(previousSummary).append("\n\n");
        }
        input.append("新增对话：\n");
        for (AiDialogue d : dialogues) {
            if (d.getUserText() != null && !d.getUserText().isBlank()) {
                input.append("用户：").append(truncate(d.getUserText(), MAX_DIALOGUE_TEXT_LEN)).append("\n");
            }
            if (d.getAiResult() != null && !d.getAiResult().isBlank()) {
                input.append("助手：").append(truncate(d.getAiResult(), MAX_DIALOGUE_TEXT_LEN)).append("\n");
            }
        }

        String prompt = """
                你是对话上下文压缩器。请把已有摘要和新增对话合并成一段中文摘要，供日程 Agent 后续理解上下文使用。

                要求：
                - 只保留稳定事实、用户目标、偏好、已确认的计划、重要指代关系和未解决问题。
                - 不要把历史中的用户请求写成本轮待执行任务。
                - 已取消、已完成或已经被新信息覆盖的内容要标明状态或不再保留。
                - 不要输出 Markdown 表格，不要输出 JSON。
                - 控制在 800 字以内。

                待压缩内容：
                """;

        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(List.of(new SystemMessage(prompt), new UserMessage(input.toString())))
                .temperature(0.1)
                .build());
        return response.aiMessage().text();
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(已截断，原" + text.length() + "字符)";
    }
}
