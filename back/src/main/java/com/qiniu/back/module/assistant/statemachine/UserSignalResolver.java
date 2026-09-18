package com.qiniu.back.module.assistant.statemachine;

import com.qiniu.back.module.assistant.service.PlanDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 按拒绝、确认、修改、新请求、未知的固定优先级识别 pending 状态下的用户事件。
 */
@Component
@RequiredArgsConstructor
public class UserSignalResolver {

    private static final Pattern DATE_OR_DURATION_PATTERN = Pattern.compile(
            "(今天|明天|后天|大后天|下周|下个月|\\d{1,2}[月/-]\\d{1,2}日?|20\\d{2}[年/-]\\d{1,2}[月/-]\\d{1,2}日?|\\d+\\s*(天|周|个月|小时|分钟))");

    private final AgentFlowStateService flowStateService;
    private final PlanDraftService planDraftService;

    public UserSignal resolve(ConversationStage stage, AgentFlowState state, String message) {
        if (stage == ConversationStage.READY_FOR_INPUT) {
            return UserSignal.NEW_MESSAGE;
        }
        if (flowStateService.isRejectMessage(message)) {
            return UserSignal.REJECT;
        }
        if (planDraftService.isConfirmMessage(message)) {
            return UserSignal.CONFIRM;
        }
        if (isModification(state, message)) {
            return UserSignal.MODIFY;
        }
        if (isNewRequest(message)) {
            return UserSignal.NEW_REQUEST;
        }
        return UserSignal.UNKNOWN;
    }

    private boolean isModification(AgentFlowState state, String message) {
        if (message == null || message.isBlank()) return false;
        String text = normalize(message);
        if (isSmallTalk(text) || isStandaloneQuery(text) || isExplicitNewTask(text)) return false;
        if (DATE_OR_DURATION_PATTERN.matcher(text).find()) return true;
        if (containsAny(text, "开始", "截止", "截至", "持续", "周期", "每天", "每周", "每月", "工作日", "周末",
                "小时", "分钟", "上午", "下午", "晚上", "早上", "中午", "晚间", "早晨",
                "改成", "调整", "换成", "改为", "改一下", "重新", "补充", "加上", "去掉", "删除掉",
                "不要", "别", "避免", "尽量", "优先", "最多", "最少", "太多", "太少", "轻一点", "重一点")) {
            return true;
        }
        if (AgentFlowStateService.AGENT_PLANNER.equals(state.getCurrentAgent())
                && containsAny(text, "考试", "备考", "复习", "刷题", "学习", "hot100", "四级", "六级", "考研", "驾照")) {
            return true;
        }
        return AgentFlowStateService.AGENT_EXECUTOR.equals(state.getCurrentAgent())
                && containsAny(text, "这个", "那个", "标题", "内容", "日期", "待办", "日记", "任务");
    }

    private boolean isNewRequest(String message) {
        if (message == null || message.isBlank()) return false;
        String text = normalize(message);
        return isSmallTalk(text) || isStandaloneQuery(text) || isExplicitNewTask(text);
    }

    private boolean isSmallTalk(String text) {
        return containsAny(text, "你好", "您好", "在吗", "谢谢", "感谢", "哈哈", "早上好", "晚上好", "下午好")
                || text.equals("hi") || text.equals("hello");
    }

    private boolean isStandaloneQuery(String text) {
        return containsAny(text, "有什么", "有哪些", "查一下", "查询", "看看", "日程吗", "待办吗")
                && !containsAny(text, "改成", "调整", "补充", "加上", "去掉");
    }

    private boolean isExplicitNewTask(String text) {
        return containsAny(text, "另外", "顺便", "再帮我", "重新开一个", "新建")
                && containsAny(text, "安排", "添加", "创建", "删除", "查询", "看看", "规划");
    }

    private String normalize(String message) {
        return message.trim().replaceAll("[　\\s,，.。!！?？~～]", "").toLowerCase();
    }

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token.toLowerCase())) return true;
        }
        return false;
    }
}
