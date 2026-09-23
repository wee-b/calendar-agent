package com.qiniu.back.module.assistant.service;

import com.qiniu.back.module.memory.service.UserMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** Decides whether a planning request genuinely benefits from one clarification turn. */
@Component
@RequiredArgsConstructor
public class PlanClarificationPolicy {

    private static final int ASK_EVERY = 3;
    private static final List<Pattern> CONCRETE_INFORMATION = List.of(
            Pattern.compile("(?:今天|明天|后天|周[一二三四五六日天]|星期[一二三四五六日天]|周末|月底|年[底前]|春节|清明|端午|中秋|国庆|元旦|\\d{1,4}[-/.年]\\d{1,2}(?:[-/.月]\\d{1,2}日?)?|\\d{1,2}月(?:\\d{1,2}日)?)"),
            Pattern.compile("(?:\\d+(?:\\.\\d+)?|[一二两三四五六七八九十百]+)(?:天|周|个月|小时|分钟)"),
            Pattern.compile("(?:预算|费用|花费|人均)?\\s*(?:￥|¥|\\d+(?:\\.\\d+)?)\\s*(?:元|块|人民币|k|K|万)"),
            Pattern.compile("(?:去|到|在|从|目的地(?:是|为)?)[\\p{IsHan}A-Za-z]{2,12}"),
            Pattern.compile("(?:\\d+|[一二两三四五六七八九十]+)(?:人|位|大\\d小)"),
            Pattern.compile("(?:喜欢|偏好|希望|想要|不想|不要|避免|必须|优先|侧重|主要|目标|交通|住宿|酒店|高铁|飞机|自驾|亲子|情侣|老人|孩子|美食|景点|购物|徒步|学习|复习|考试)"),
            Pattern.compile("(?:每天|每周|工作日|早上|上午|中午|下午|晚上|睡前).{0,10}(?:\\d|一|二|两|三|四|五|六|七|八|九|十|小时|分钟|任务)")
    );

    private final UserMemoryService userMemoryService;

    public boolean shouldAsk(Long userId, String sessionId, String requirement, Long dialogueId) {
        if (countConcreteInformation(requirement) > 0) return false;
        if (userMemoryService.hasActivePlanningPreference(userId)) return false;

        long sequence = dialogueId != null ? dialogueId : stableSequence(sessionId);
        return Math.floorMod(sequence, ASK_EVERY) == 1;
    }

    int countConcreteInformation(String requirement) {
        if (requirement == null || requirement.isBlank()) return 0;
        int count = 0;
        for (Pattern pattern : CONCRETE_INFORMATION) {
            if (pattern.matcher(requirement).find()) count++;
        }
        return count;
    }

    private long stableSequence(String sessionId) {
        return sessionId == null ? 0 : sessionId.hashCode();
    }
}
