package com.qiniu.back.module.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiniu.back.domain.chat.AiDialogue;
import com.qiniu.back.domain.chat.vo.ChatHistoryItemVO;
import com.qiniu.back.domain.chat.vo.ChatResponseVO;
import com.qiniu.back.domain.chat.vo.ChatSessionVO;
import com.qiniu.back.module.chat.mapper.AiDialogueMapper;
import com.qiniu.back.module.chat.service.ChatService;
import com.qiniu.back.module.chat.service.OpenAiService;
import com.qiniu.back.util.LoginUserContext;
import com.qiniu.back.util.PromptLoader;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private static final int MAX_HISTORY = 20;
    private static final String SYSTEM_PROMPT = PromptLoader.load("chat-system.txt");

    @Autowired
    private OpenAiService openAiService;

    @Autowired
    private AiDialogueMapper aiDialogueMapper;

    @Override
    public ChatResponseVO chat(String sessionId, String message) {
        Long userId = LoginUserContext.getUserId();
        String sid = (sessionId == null || sessionId.isEmpty()) ? UUID.randomUUID().toString() : sessionId;

        saveDialogue(userId, sid, "user", message, null);

        List<AiDialogue> history = loadHistory(userId, sid);
        List<Map<String, String>> messages = buildMessageList(history);

        String aiResult;
        try {
            aiResult = openAiService.chat(buildSystemPrompt(), messages);
        } catch (Exception e) {
            log.error("AI 调用失败", e);
            aiResult = "抱歉，我暂时无法处理这个请求，请稍后再试。";
        }

        saveDialogue(userId, sid, "assistant", null, aiResult);

        ChatResponseVO vo = new ChatResponseVO();
        vo.setSessionId(sid);
        vo.setAiResult(aiResult);
        return vo;
    }

    @Override
    public String newSession() {
        return UUID.randomUUID().toString();
    }

    @Override
    public List<ChatHistoryItemVO> getHistory(String sessionId) {
        Long userId = LoginUserContext.getUserId();
        List<AiDialogue> list = aiDialogueMapper.selectList(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .eq(AiDialogue::getSessionId, sessionId)
                        .orderByAsc(AiDialogue::getCreateTime));

        return list.stream()
                .map(d -> new ChatHistoryItemVO(
                        d.getDialogueId(),
                        d.getRole(),
                        d.getRole().equals("user") ? d.getUserText() : d.getAiResult(),
                        d.getCreateTime()))
                .toList();
    }

    @Override
    public void deleteSession(String sessionId) {
        Long userId = LoginUserContext.getUserId();
        aiDialogueMapper.delete(new LambdaQueryWrapper<AiDialogue>()
                .eq(AiDialogue::getUserId, userId)
                .eq(AiDialogue::getSessionId, sessionId));
    }

    @Override
    public void deleteLastRound(String sessionId) {
        Long userId = LoginUserContext.getUserId();
        // 找到该 session 最后一条消息
        List<AiDialogue> last = aiDialogueMapper.selectList(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .eq(AiDialogue::getSessionId, sessionId)
                        .orderByDesc(AiDialogue::getDialogueId)
                        .last("LIMIT 2"));

        if (last.size() < 2) return;

        // 删最后2条（用户+助手各一条）
        for (AiDialogue d : last) {
            aiDialogueMapper.deleteById(d.getDialogueId());
        }
    }

    @Override
    public List<ChatSessionVO> listSessions() {
        Long userId = LoginUserContext.getUserId();
        // 按 session 分组，取每个 session 的第一条用户消息作为标题
        List<AiDialogue> all = aiDialogueMapper.selectList(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .orderByAsc(AiDialogue::getCreateTime));

        Map<String, List<AiDialogue>> grouped = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(AiDialogue::getSessionId, LinkedHashMap::new, java.util.stream.Collectors.toList()));

        List<ChatSessionVO> result = new ArrayList<>();
        for (Map.Entry<String, List<AiDialogue>> entry : grouped.entrySet()) {
            String sid = entry.getKey();
            List<AiDialogue> msgs = entry.getValue();
            String title = msgs.stream()
                    .filter(d -> "user".equals(d.getRole()) && d.getUserText() != null)
                    .findFirst()
                    .map(d -> d.getUserText().length() > 30 ? d.getUserText().substring(0, 30) + "..." : d.getUserText())
                    .orElse("新对话");
            result.add(new ChatSessionVO(sid, title, msgs.get(0).getCreateTime(), msgs.size()));
        }
        return result;
    }

    @Override
    public List<ChatHistoryItemVO> getLatestSession() {
        Long userId = LoginUserContext.getUserId();
        // 找最新一条记录的 session
        AiDialogue latest = aiDialogueMapper.selectOne(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .orderByDesc(AiDialogue::getCreateTime)
                        .last("LIMIT 1"));

        if (latest == null) return Collections.emptyList();

        return getHistory(latest.getSessionId());
    }

    // ==================== 内部工具 ====================

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy年M月d日");

    private String buildSystemPrompt() {
        LocalDate today = LocalDate.now();
        DayOfWeek dow = today.getDayOfWeek();
        String weekDayCn = dow.getDisplayName(TextStyle.FULL, Locale.CHINESE);
        String todayStr = today.format(DATE_FMT) + "（" + weekDayCn + "）";

        return SYSTEM_PROMPT + "\n\n## 时间上下文\n当前日期是 " + todayStr + "。用户说\"今天\"就是指" + todayStr + "，说\"明天\"就是加一天，以此类推。";
    }

    private List<AiDialogue> loadHistory(Long userId, String sessionId) {
        return aiDialogueMapper.selectList(
                new LambdaQueryWrapper<AiDialogue>()
                        .eq(AiDialogue::getUserId, userId)
                        .eq(AiDialogue::getSessionId, sessionId)
                        .orderByAsc(AiDialogue::getCreateTime)
                        .last("LIMIT " + (MAX_HISTORY * 2)));
    }

    private List<Map<String, String>> buildMessageList(List<AiDialogue> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (AiDialogue d : history) {
            if (d.getUserText() != null && !d.getUserText().isEmpty()) {
                messages.add(Map.of("role", "user", "content", d.getUserText()));
            }
            if (d.getAiResult() != null && !d.getAiResult().isEmpty()) {
                messages.add(Map.of("role", "assistant", "content", d.getAiResult()));
            }
        }
        return messages;
    }

    private void saveDialogue(Long userId, String sessionId, String role, String userText, String aiResult) {
        AiDialogue d = new AiDialogue();
        d.setUserId(userId);
        d.setSessionId(sessionId);
        d.setRole(role);
        d.setUserText(userText);
        d.setAiResult(aiResult);
        aiDialogueMapper.insert(d);
    }
}
