package com.qiniu.back.module.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qiniu.back.domain.ErrorCode;
import com.qiniu.back.domain.memory.UserMemory;
import com.qiniu.back.domain.memory.vo.UserMemoryVO;
import com.qiniu.back.domain.todo.Todo;
import com.qiniu.back.domain.todo.TodoDate;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.memory.mapper.UserMemoryMapper;
import com.qiniu.back.module.todo.mapper.TodoDateMapper;
import com.qiniu.back.module.todo.mapper.TodoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class UserMemoryService {

    public static final String TYPE_PREFERENCE_TIME = "PREFERENCE_TIME";
    public static final String TYPE_PREFERENCE_LOAD = "PREFERENCE_LOAD";
    public static final String TYPE_PREFERENCE_STYLE = "PREFERENCE_STYLE";
    public static final String TYPE_AVOIDANCE = "AVOIDANCE";
    public static final String TYPE_DOMAIN_PREFERENCE = "DOMAIN_PREFERENCE";
    public static final String TYPE_LONG_TERM_GOAL = "LONG_TERM_GOAL";
    public static final String TYPE_BEHAVIOR_PATTERN = "BEHAVIOR_PATTERN";

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_ARCHIVED = "archived";
    private static final String SOURCE_CHAT = "chat";
    private static final String SOURCE_BEHAVIOR = "behavior";
    private static final int CONTEXT_LIMIT_PLANNER = 8;
    private static final Pattern MAX_TASK_PATTERN = Pattern.compile("每天(?:最多|不超过|不要超过|至多)(\\d{1,2})个?(?:重点)?(?:任务|待办|事项)?");

    @Autowired
    private UserMemoryMapper userMemoryMapper;

    @Autowired
    private TodoMapper todoMapper;

    @Autowired
    private TodoDateMapper todoDateMapper;

    @Async("memoryExecutor")
    public void extractAndSaveFromUserMessageAsync(Long userId, Long sourceDialogueId, String message) {
        try {
            extractAndSaveFromUserMessage(userId, sourceDialogueId, message);
        } catch (Exception e) {
            log.warn("[Memory] extract failed: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public void extractAndSaveFromUserMessage(Long userId, Long sourceDialogueId, String message) {
        if (userId == null || message == null || message.isBlank()) return;
        String text = normalizeText(message);
        if (text.length() > 300 || isTemporaryExpression(text)) return;

        if (tryForgetMemory(userId, text)) return;

        List<UserMemory> memories = extractRuleBasedMemories(userId, sourceDialogueId, text);
        for (UserMemory memory : memories) {
            upsertActiveMemory(memory);
        }
    }

    public String buildPlannerMemoryContext(Long userId, String requirement) {
        refreshBehaviorMemoriesIfStale(userId);

        List<UserMemory> memories = listRelevantMemories(userId, requirement, CONTEXT_LIMIT_PLANNER);
        if (memories.isEmpty()) return "";

        LocalDateTime now = LocalDateTime.now();
        userMemoryMapper.update(null, new LambdaUpdateWrapper<UserMemory>()
                .in(UserMemory::getMemoryId, memories.stream().map(UserMemory::getMemoryId).toList())
                .set(UserMemory::getLastUsedTime, now));

        StringBuilder sb = new StringBuilder();
        sb.append("## 用户长期记忆\n");
        sb.append("以下内容是用户长期偏好、目标或行为习惯。生成计划时应优先参考；如果本轮用户明确提出相反要求，以本轮要求为准。\n");
        for (UserMemory memory : memories) {
            sb.append("- ")
                    .append(memory.getContent())
                    .append("（")
                    .append(label(memory.getMemoryType()))
                    .append("）\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    public List<UserMemoryVO> listActive(Long userId, String type) {
        LambdaQueryWrapper<UserMemory> wrapper = new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getStatus, STATUS_ACTIVE)
                .orderByDesc(UserMemory::getUpdateTime);
        if (type != null && !type.isBlank()) {
            wrapper.eq(UserMemory::getMemoryType, type);
        }
        return userMemoryMapper.selectList(wrapper).stream()
                .map(UserMemoryVO::from)
                .toList();
    }

    public void delete(Long userId, Long memoryId) {
        int updated = userMemoryMapper.update(null, new LambdaUpdateWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getMemoryId, memoryId)
                .set(UserMemory::getStatus, "deleted"));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "记忆不存在");
        }
    }

    @Transactional
    public int refreshBehaviorMemories(Long userId) {
        List<UserMemory> behaviorMemories = computeBehaviorMemories(userId);
        userMemoryMapper.update(null, new LambdaUpdateWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getMemoryType, TYPE_BEHAVIOR_PATTERN)
                .eq(UserMemory::getStatus, STATUS_ACTIVE)
                .set(UserMemory::getStatus, STATUS_ARCHIVED));

        for (UserMemory memory : behaviorMemories) {
            userMemoryMapper.insert(memory);
        }
        return behaviorMemories.size();
    }

    private List<UserMemory> listRelevantMemories(Long userId, String query, int limit) {
        List<UserMemory> active = userMemoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getStatus, STATUS_ACTIVE)
                .and(w -> w.isNull(UserMemory::getExpireTime).or().gt(UserMemory::getExpireTime, LocalDateTime.now()))
                .orderByDesc(UserMemory::getConfidence)
                .orderByDesc(UserMemory::getUpdateTime));

        String normalizedQuery = normalizeText(query);
        return active.stream()
                .sorted(Comparator
                        .comparingInt((UserMemory m) -> relevanceScore(m, normalizedQuery)).reversed()
                        .thenComparing(UserMemory::getConfidence, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(UserMemory::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    private int relevanceScore(UserMemory memory, String query) {
        int score = switch (memory.getMemoryType()) {
            case TYPE_AVOIDANCE, TYPE_PREFERENCE_LOAD, TYPE_PREFERENCE_TIME -> 30;
            case TYPE_LONG_TERM_GOAL -> 20;
            case TYPE_BEHAVIOR_PATTERN -> 12;
            default -> 10;
        };
        String content = normalizeText(memory.getContent());
        if (query != null && !query.isBlank()) {
            for (String token : List.of("刷题", "学习", "复习", "考试", "Hot100", "英语", "四级", "六级", "考研", "驾照", "周末", "晚上", "早上")) {
                if (query.contains(token.toLowerCase(Locale.ROOT)) && content.contains(token.toLowerCase(Locale.ROOT))) {
                    score += 20;
                }
            }
        }
        if (memory.getLastUsedTime() != null) score += 2;
        return score;
    }

    private List<UserMemory> extractRuleBasedMemories(Long userId, Long sourceDialogueId, String text) {
        List<UserMemory> result = new ArrayList<>();
        boolean hasLongTermCue = hasLongTermCue(text);

        if (text.contains("周末") && containsAny(text, "不刷题", "不学习", "别刷题", "不要刷题", "避免刷题", "不安排刷题")) {
            result.add(memory(userId, TYPE_AVOIDANCE, "用户周末不安排刷题任务。", "weekend_study", sourceDialogueId, 0.95));
        } else if (text.contains("周末") && containsAny(text, "不安排高强度", "不要高强度", "避免高强度", "不喜欢高强度")) {
            result.add(memory(userId, TYPE_AVOIDANCE, "用户周末避免安排高强度任务。", "weekend_intensity", sourceDialogueId, 0.9));
        } else if (text.contains("周末") && containsAny(text, "可以刷题", "能刷题", "可以学习", "能学习")) {
            result.add(memory(userId, TYPE_DOMAIN_PREFERENCE, "用户周末可以安排学习或刷题任务。", "weekend_study", sourceDialogueId, 0.88));
        }

        if (containsAny(text, "晚上效率高", "晚上学习", "晚上复习", "晚上安排") && containsAny(text, "习惯", "喜欢", "适合", "尽量", "以后")) {
            result.add(memory(userId, TYPE_PREFERENCE_TIME, "用户偏好晚上学习或复习。", "study_time", sourceDialogueId, 0.9));
        }
        if (containsAny(text, "早上效率高", "早上学习", "早上背单词", "早上安排") && containsAny(text, "习惯", "喜欢", "适合", "尽量", "以后")) {
            result.add(memory(userId, TYPE_PREFERENCE_TIME, "用户偏好早上学习或背单词。", "study_time", sourceDialogueId, 0.9));
        }

        Matcher maxTaskMatcher = MAX_TASK_PATTERN.matcher(text);
        if (maxTaskMatcher.find()) {
            result.add(memory(userId, TYPE_PREFERENCE_LOAD,
                    "用户每天最多安排 " + maxTaskMatcher.group(1) + " 个重点任务。",
                    "daily_task_limit", sourceDialogueId, 0.92));
        }

        if (hasLongTermCue && text.contains("番茄钟")) {
            result.add(memory(userId, TYPE_PREFERENCE_STYLE, "用户偏好使用番茄钟方式安排学习。", "planning_style", sourceDialogueId, 0.86));
        }

        extractLongTermGoal(userId, sourceDialogueId, text).ifPresent(result::add);
        return result;
    }

    private Optional<UserMemory> extractLongTermGoal(Long userId, Long sourceDialogueId, String text) {
        if (containsAny(text, "hot100", "hot 100")) {
            return Optional.of(memory(userId, TYPE_LONG_TERM_GOAL, "用户正在推进 LeetCode Hot100 刷题目标。", "goal_hot100", sourceDialogueId, 0.88));
        }
        for (String exam : List.of("考研", "英语四级", "四级", "英语六级", "六级", "期末考试", "期末", "驾照")) {
            if (containsAny(text, "准备" + exam, "备考" + exam, "正在" + exam, exam + "备考", exam + "复习")) {
                return Optional.of(memory(userId, TYPE_LONG_TERM_GOAL, "用户正在准备" + exam + "。", "goal_" + exam, sourceDialogueId, 0.86));
            }
        }
        return Optional.empty();
    }

    private UserMemory memory(Long userId, String type, String content, String normalizedKey,
                              Long sourceDialogueId, double confidence) {
        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setMemoryType(type);
        memory.setContent(content);
        memory.setNormalizedKey(normalizedKey);
        memory.setSource(SOURCE_CHAT);
        memory.setSourceId(sourceDialogueId);
        memory.setConfidence(BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP));
        memory.setStatus(STATUS_ACTIVE);
        return memory;
    }

    private void upsertActiveMemory(UserMemory memory) {
        if (memory.getNormalizedKey() != null && !memory.getNormalizedKey().isBlank()) {
            List<UserMemory> existing = userMemoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getUserId, memory.getUserId())
                    .eq(UserMemory::getNormalizedKey, memory.getNormalizedKey())
                    .eq(UserMemory::getStatus, STATUS_ACTIVE));

            boolean sameContentExists = existing.stream().anyMatch(m -> Objects.equals(m.getContent(), memory.getContent()));
            if (sameContentExists) return;

            if (!existing.isEmpty()) {
                userMemoryMapper.update(null, new LambdaUpdateWrapper<UserMemory>()
                        .eq(UserMemory::getUserId, memory.getUserId())
                        .eq(UserMemory::getNormalizedKey, memory.getNormalizedKey())
                        .eq(UserMemory::getStatus, STATUS_ACTIVE)
                        .set(UserMemory::getStatus, STATUS_ARCHIVED));
            }
        }
        userMemoryMapper.insert(memory);
        log.info("[Memory] saved: userId={}, type={}, key={}, content={}",
                memory.getUserId(), memory.getMemoryType(), memory.getNormalizedKey(), memory.getContent());
    }

    private boolean tryForgetMemory(Long userId, String text) {
        if (!containsAny(text, "不要记住", "别记住", "忘记", "删除记忆", "删掉记忆")) return false;

        String key = null;
        if (text.contains("周末") && containsAny(text, "刷题", "学习")) key = "weekend_study";
        if (text.contains("晚上") || text.contains("早上")) key = "study_time";
        if (text.contains("每天") && containsAny(text, "最多", "不超过")) key = "daily_task_limit";

        if (key == null) return false;
        userMemoryMapper.update(null, new LambdaUpdateWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getNormalizedKey, key)
                .eq(UserMemory::getStatus, STATUS_ACTIVE)
                .set(UserMemory::getStatus, STATUS_ARCHIVED));
        return true;
    }

    private void refreshBehaviorMemoriesIfStale(Long userId) {
        UserMemory latest = userMemoryMapper.selectOne(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getMemoryType, TYPE_BEHAVIOR_PATTERN)
                .orderByDesc(UserMemory::getUpdateTime)
                .last("LIMIT 1"));
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        if (latest != null && latest.getUpdateTime() != null && latest.getUpdateTime().isAfter(todayStart)) {
            return;
        }
        try {
            refreshBehaviorMemories(userId);
        } catch (Exception e) {
            log.warn("[Memory] behavior refresh failed: {}", e.getMessage(), e);
        }
    }

    private List<UserMemory> computeBehaviorMemories(Long userId) {
        LocalDate start = LocalDate.now().minusDays(30);
        List<Todo> todos = todoMapper.selectList(new LambdaQueryWrapper<Todo>()
                .eq(Todo::getUserId, userId)
                .eq(Todo::getDeletedFlag, 0));
        if (todos.isEmpty()) return List.of();

        List<Long> todoIds = todos.stream().map(Todo::getTodoId).toList();
        List<TodoDate> dates = todoDateMapper.selectList(new LambdaQueryWrapper<TodoDate>()
                .in(TodoDate::getTodoId, todoIds)
                .ge(TodoDate::getTodoDate, start)
                .le(TodoDate::getTodoDate, LocalDate.now()));
        if (dates.size() < 10) return List.of();

        int weekdayTotal = 0;
        int weekdayDone = 0;
        int weekendTotal = 0;
        int weekendDone = 0;
        for (TodoDate item : dates) {
            boolean done = item.getStatus() != null && item.getStatus() == 1;
            DayOfWeek dow = item.getTodoDate().getDayOfWeek();
            boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            if (weekend) {
                weekendTotal++;
                if (done) weekendDone++;
            } else {
                weekdayTotal++;
                if (done) weekdayDone++;
            }
        }

        List<UserMemory> result = new ArrayList<>();
        double weekdayRate = rate(weekdayDone, weekdayTotal);
        double weekendRate = rate(weekendDone, weekendTotal);
        double overallRate = rate((int) dates.stream().filter(d -> d.getStatus() != null && d.getStatus() == 1).count(), dates.size());

        if (weekdayTotal >= 6 && weekdayRate >= 0.7) {
            result.add(behavior(userId, "用户最近 30 天工作日任务完成率较高，规划学习任务时可优先安排在工作日。", "behavior_weekday_completion", 0.72));
        }
        if (weekendTotal >= 4 && weekdayTotal >= 6 && weekendRate + 0.25 < weekdayRate) {
            result.add(behavior(userId, "用户最近 30 天周末任务完成率低于工作日，周末高强度任务宜减少。", "behavior_weekend_low_completion", 0.70));
        }
        if (dates.size() >= 15 && overallRate < 0.45) {
            result.add(behavior(userId, "用户最近 30 天整体待办完成率偏低，规划时宜降低每日任务量。", "behavior_low_overall_completion", 0.68));
        }
        if (dates.size() / 30.0 >= 5) {
            result.add(behavior(userId, "用户最近 30 天平均每日待办数量较多，规划时应注意任务负载。", "behavior_high_daily_load", 0.66));
        }
        return result;
    }

    private UserMemory behavior(Long userId, String content, String normalizedKey, double confidence) {
        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setMemoryType(TYPE_BEHAVIOR_PATTERN);
        memory.setContent(content);
        memory.setNormalizedKey(normalizedKey);
        memory.setSource(SOURCE_BEHAVIOR);
        memory.setConfidence(BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP));
        memory.setStatus(STATUS_ACTIVE);
        memory.setExpireTime(LocalDateTime.now().plusDays(14));
        return memory;
    }

    private double rate(int done, int total) {
        return total == 0 ? 0 : (double) done / total;
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private boolean isTemporaryExpression(String text) {
        return containsAny(text, "今天不想", "这周太累", "随便", "我也不确定", "临时", "这一次")
                && !hasLongTermCue(text);
    }

    private boolean hasLongTermCue(String text) {
        return containsAny(text, "以后", "之后", "长期", "一直", "我习惯", "我喜欢", "我不喜欢", "尽量", "每天最多", "正在", "准备", "备考");
    }

    private boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String label(String type) {
        return switch (type) {
            case TYPE_PREFERENCE_TIME -> "时间偏好";
            case TYPE_PREFERENCE_LOAD -> "负载偏好";
            case TYPE_PREFERENCE_STYLE -> "计划风格";
            case TYPE_AVOIDANCE -> "避免事项";
            case TYPE_DOMAIN_PREFERENCE -> "领域偏好";
            case TYPE_LONG_TERM_GOAL -> "长期目标";
            case TYPE_BEHAVIOR_PATTERN -> "行为习惯";
            default -> type;
        };
    }
}
