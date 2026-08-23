package com.qiniu.back.module.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiniu.back.domain.todo.Todo;
import com.qiniu.back.domain.todo.TodoDate;
import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.module.todo.mapper.TodoDateMapper;
import com.qiniu.back.module.todo.mapper.TodoMapper;
import com.qiniu.back.module.todo.service.TodoService;
import com.qiniu.back.util.LoginUserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DirectCommandService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Pattern FULL_DATE = Pattern.compile("(20\\d{2})[-/\\.年](\\d{1,2})[-/\\.月](\\d{1,2})日?");
    private static final Pattern MONTH_DAY = Pattern.compile("(?<!\\d)(\\d{1,2})[-/\\.月](\\d{1,2})日?");

    @Autowired
    private TodoMapper todoMapper;

    @Autowired
    private TodoDateMapper todoDateMapper;

    @Autowired
    private TodoService todoService;

    public DirectCommandResult tryHandle(String message) {
        if (message == null || message.isBlank()) return DirectCommandResult.fallback();

        String text = message.trim();
        List<DateMention> dates = parseDates(text);
        LocalDate firstDate = dates.stream().findFirst().map(DateMention::date).orElse(null);

        if (isMoveCommand(text)) {
            if (dates.size() < 2) return DirectCommandResult.fallback();
            String keyword = extractKeyword(text);
            if (keyword.isBlank()) return DirectCommandResult.fallback();
            log.info("[DirectCommand] move todo day: {}", text);
            return executeSafely(() -> moveTodoDayByTitle(dates.get(0).date(), dates.get(1).date(), keyword));
        }

        if (isDayQuery(text) && firstDate != null) {
            log.info("[DirectCommand] query day: {}", text);
            return executeSafely(() -> queryDay(firstDate));
        }

        if (isCreateCommand(text)) {
            if (firstDate == null) return DirectCommandResult.fallback();
            String keyword = extractKeyword(text);
            if (keyword.isBlank()) return DirectCommandResult.fallback();
            log.info("[DirectCommand] create todo: {}", text);
            return executeSafely(() -> createSingleDayTodo(firstDate, keyword));
        }

        if (isDeleteWholeTodoCommand(text)) {
            String keyword = extractKeyword(text);
            if (keyword.isBlank()) return DirectCommandResult.fallback();
            log.info("[DirectCommand] delete todo: {}", text);
            return executeSafely(() -> deleteTodoByTitle(keyword));
        }

        if (isRemoveDayCommand(text)) {
            if (firstDate == null) return DirectCommandResult.fallback();
            String keyword = extractKeyword(text);
            if (keyword.isBlank()) return DirectCommandResult.fallback();
            log.info("[DirectCommand] remove todo day: {}", text);
            return executeSafely(() -> removeTodoDayByTitle(firstDate, keyword));
        }

        if (isToggleDoneCommand(text)) {
            LocalDate date = firstDate != null ? firstDate : LocalDate.now();
            String keyword = extractKeyword(text);
            if (keyword.isBlank()) return DirectCommandResult.fallback();
            log.info("[DirectCommand] toggle todo day: {}", text);
            return executeSafely(() -> toggleTodoDateByTitle(date, keyword));
        }

        return DirectCommandResult.fallback();
    }

    private DirectCommandResult executeSafely(Supplier<String> action) {
        try {
            return DirectCommandResult.handled(action.get());
        } catch (Exception e) {
            log.warn("[DirectCommand] execution failed, stop fast path: {}", e.getMessage(), e);
            return DirectCommandResult.failed("我已经理解你的操作意图，但直接执行时失败了：" + e.getMessage());
        }
    }

    private String queryDay(LocalDate date) {
        List<TodoMatch> matches = findTodosByDate(date, "");
        if (matches.isEmpty()) {
            return date.format(DATE_FORMATTER) + " 暂无待办。";
        }

        String items = matches.stream()
                .sorted(Comparator.comparing(match -> match.todo().getCreateTime()))
                .map(match -> "- " + statusMark(match.todoDate()) + " " + match.todo().getTitle()
                        + dayContentSuffix(match.todoDate()))
                .collect(Collectors.joining("\n"));
        return date.format(DATE_FORMATTER) + " 的待办：\n" + items;
    }

    private String createSingleDayTodo(LocalDate date, String title) {
        TodoCreateDTO dto = new TodoCreateDTO();
        dto.setTitle(title);
        dto.setDayContent(title);
        dto.setColor("#5c4b37");
        dto.setStartDate(date);
        dto.setEndDate(date);
        dto.setWeekDays(List.of(date.getDayOfWeek().getValue()));
        todoService.create(dto);
        return "已添加 " + date.format(DATE_FORMATTER) + " 的「" + title + "」。";
    }

    private String removeTodoDayByTitle(LocalDate date, String keyword) {
        List<TodoMatch> matches = findTodosByDate(date, keyword);
        if (matches.isEmpty()) {
            return "未找到 " + date.format(DATE_FORMATTER) + " 包含「" + keyword + "」的待办。";
        }
        if (matches.size() > 1) {
            return buildAmbiguousReply(date, keyword, matches);
        }

        TodoMatch match = matches.get(0);
        todoService.removeTodoDay(match.todo().getTodoId(), date);
        return "已删除 " + date.format(DATE_FORMATTER) + " 的「" + match.todo().getTitle() + "」。";
    }

    private String toggleTodoDateByTitle(LocalDate date, String keyword) {
        List<TodoMatch> matches = findTodosByDate(date, keyword);
        if (matches.isEmpty()) {
            return "未找到 " + date.format(DATE_FORMATTER) + " 包含「" + keyword + "」的待办。";
        }
        if (matches.size() > 1) {
            return buildAmbiguousReply(date, keyword, matches);
        }

        TodoMatch match = matches.get(0);
        int status = todoService.toggleDateStatus(match.todo().getTodoId(), date);
        String action = status == 1 ? "已完成" : "已取消完成";
        return action + " " + date.format(DATE_FORMATTER) + " 的「" + match.todo().getTitle() + "」。";
    }

    private String moveTodoDayByTitle(LocalDate fromDate, LocalDate toDate, String keyword) {
        List<TodoMatch> matches = findTodosByDate(fromDate, keyword);
        if (matches.isEmpty()) {
            return "未找到 " + fromDate.format(DATE_FORMATTER) + " 包含「" + keyword + "」的待办。";
        }
        if (matches.size() > 1) {
            return buildAmbiguousReply(fromDate, keyword, matches);
        }

        TodoMatch match = matches.get(0);
        todoService.removeTodoDay(match.todo().getTodoId(), fromDate);
        todoService.addTodoDay(match.todo().getTodoId(), toDate, match.todoDate().getDayContent());
        return "已将「" + match.todo().getTitle() + "」从 "
                + fromDate.format(DATE_FORMATTER) + " 移到 " + toDate.format(DATE_FORMATTER) + "。";
    }

    private String deleteTodoByTitle(String keyword) {
        Long userId = LoginUserContext.getUserId();
        List<Todo> matches = todoMapper.selectList(new LambdaQueryWrapper<Todo>()
                .eq(Todo::getUserId, userId)
                .eq(Todo::getDeletedFlag, 0)
                .like(Todo::getTitle, keyword));

        if (matches.isEmpty()) {
            return "未找到包含「" + keyword + "」的待办目标。";
        }
        if (matches.size() > 1) {
            String items = matches.stream()
                    .map(todo -> "- [" + todo.getTodoId() + "] " + todo.getTitle())
                    .collect(Collectors.joining("\n"));
            return "找到多个匹配目标，请说得更具体一点：\n" + items;
        }

        Todo todo = matches.get(0);
        todoService.delete(todo.getTodoId());
        return "已删除待办目标「" + todo.getTitle() + "」。";
    }

    private List<TodoMatch> findTodosByDate(LocalDate date, String keyword) {
        Long userId = LoginUserContext.getUserId();
        List<TodoDate> todoDates = todoDateMapper.selectList(new LambdaQueryWrapper<TodoDate>()
                .eq(TodoDate::getTodoDate, date));
        if (todoDates.isEmpty()) return List.of();

        List<Long> todoIds = todoDates.stream().map(TodoDate::getTodoId).distinct().toList();
        List<Todo> todos = todoMapper.selectList(new LambdaQueryWrapper<Todo>()
                .in(Todo::getTodoId, todoIds)
                .eq(Todo::getUserId, userId)
                .eq(Todo::getDeletedFlag, 0));
        Map<Long, Todo> todoMap = todos.stream().collect(Collectors.toMap(Todo::getTodoId, todo -> todo));

        return todoDates.stream()
                .map(todoDate -> new TodoMatch(todoMap.get(todoDate.getTodoId()), todoDate))
                .filter(match -> match.todo() != null)
                .filter(match -> keyword == null || keyword.isBlank()
                        || match.todo().getTitle().contains(keyword)
                        || (match.todoDate().getDayContent() != null && match.todoDate().getDayContent().contains(keyword)))
                .toList();
    }

    private List<DateMention> parseDates(String text) {
        LocalDate today = LocalDate.now();
        Map<Integer, DateMention> mentions = new LinkedHashMap<>();

        addRelativeDate(text, mentions, "今天", today);
        addRelativeDate(text, mentions, "明天", today.plusDays(1));
        addRelativeDate(text, mentions, "后天", today.plusDays(2));

        Matcher full = FULL_DATE.matcher(text);
        while (full.find()) {
            LocalDate date = LocalDate.of(
                    Integer.parseInt(full.group(1)),
                    Integer.parseInt(full.group(2)),
                    Integer.parseInt(full.group(3)));
            mentions.putIfAbsent(full.start(), new DateMention(full.start(), full.group(), date));
        }

        Matcher monthDay = MONTH_DAY.matcher(text);
        while (monthDay.find()) {
            LocalDate date = LocalDate.of(today.getYear(),
                    Integer.parseInt(monthDay.group(1)),
                    Integer.parseInt(monthDay.group(2)));
            if (date.isBefore(today)) {
                date = date.plusYears(1);
            }
            mentions.putIfAbsent(monthDay.start(), new DateMention(monthDay.start(), monthDay.group(), date));
        }

        return mentions.values().stream()
                .sorted(Comparator.comparing(DateMention::index))
                .toList();
    }

    private void addRelativeDate(String text, Map<Integer, DateMention> mentions, String token, LocalDate date) {
        int index = text.indexOf(token);
        while (index >= 0) {
            mentions.putIfAbsent(index, new DateMention(index, token, date));
            index = text.indexOf(token, index + token.length());
        }
    }

    private String extractKeyword(String text) {
        String keyword = text
                .replaceAll("20\\d{2}[-/\\.年]\\d{1,2}[-/\\.月]\\d{1,2}日?", "")
                .replaceAll("\\d{1,2}[-/\\.月]\\d{1,2}日?", "")
                .replace("今天", "")
                .replace("明天", "")
                .replace("后天", "");

        keyword = keyword
                .replaceAll("[　\\s,，.。!！?？]", "")
                .replace("删除", "")
                .replace("删掉", "")
                .replace("取消", "")
                .replace("跳过", "")
                .replace("完成", "")
                .replace("打卡", "")
                .replace("安排一个", "")
                .replace("安排一场", "")
                .replace("安排一次", "")
                .replace("安排下", "")
                .replace("安排", "")
                .replace("预约一个", "")
                .replace("预约一场", "")
                .replace("预约一次", "")
                .replace("预约", "")
                .replace("约一个", "")
                .replace("约一场", "")
                .replace("约一次", "")
                .replace("添加", "")
                .replace("新增", "")
                .replace("创建", "")
                .replace("加一个", "")
                .replace("有一个", "")
                .replace("还有一个", "")
                .replace("一个", "")
                .replace("改到", "")
                .replace("挪到", "")
                .replace("移到", "")
                .replace("调整到", "")
                .replace("延后到", "")
                .replace("整个", "")
                .replace("这个目标", "")
                .replace("目标", "")
                .replace("以后不做", "")
                .replace("不再做", "")
                .replace("帮我", "")
                .replace("帮忙", "")
                .replace("请", "")
                .replace("把", "")
                .replace("将", "")
                .replace("给我", "")
                .replace("一下", "")
                .replace("的", "")
                .replace("待办", "")
                .replace("日程", "")
                .replace("任务", "");
        return keyword.trim();
    }

    private boolean isDayQuery(String text) {
        return (text.contains("查询") || text.contains("看看") || text.contains("有什么")
                || text.contains("有哪些") || text.contains("哪些事")
                || text.contains("什么事") || text.contains("安排")
                || text.contains("日程"))
                && !isRemoveDayCommand(text)
                && !isToggleDoneCommand(text)
                && !isCreateCommand(text)
                && !isMoveCommand(text);
    }

    private boolean isCreateCommand(String text) {
        if (text.contains("还有一个")
                || text.contains("有一个")
                || text.contains("加一个")
                || text.contains("添加")
                || text.contains("新增")
                || text.contains("创建")) {
            return true;
        }

        boolean scheduleVerb = text.contains("安排")
                || text.contains("预约")
                || text.contains("约一个")
                || text.contains("约一场")
                || text.contains("约一次");
        return scheduleVerb && !looksLikeQuery(text);
    }

    private boolean looksLikeQuery(String text) {
        return text.contains("查询")
                || text.contains("看看")
                || text.contains("有什么")
                || text.contains("有哪些")
                || text.contains("哪些事")
                || text.contains("什么事");
    }

    private boolean isMoveCommand(String text) {
        return text.contains("改到")
                || text.contains("挪到")
                || text.contains("移到")
                || text.contains("调整到")
                || text.contains("延后到");
    }

    private boolean isDeleteWholeTodoCommand(String text) {
        return (text.contains("删除") || text.contains("删掉"))
                && (text.contains("整个") || text.contains("目标") || text.contains("以后不做") || text.contains("不再做"));
    }

    private boolean isRemoveDayCommand(String text) {
        return text.contains("删除") || text.contains("删掉")
                || text.contains("取消") || text.contains("跳过");
    }

    private boolean isToggleDoneCommand(String text) {
        return text.contains("完成") || text.contains("打卡");
    }

    private String statusMark(TodoDate todoDate) {
        return todoDate.getStatus() != null && todoDate.getStatus() == 1 ? "[x]" : "[ ]";
    }

    private String dayContentSuffix(TodoDate todoDate) {
        return todoDate.getDayContent() == null || todoDate.getDayContent().isBlank()
                ? ""
                : " - " + todoDate.getDayContent();
    }

    private String buildAmbiguousReply(LocalDate date, String keyword, List<TodoMatch> matches) {
        String items = matches.stream()
                .map(match -> "- [" + match.todo().getTodoId() + "] " + match.todo().getTitle())
                .collect(Collectors.joining("\n"));
        return "找到多个匹配待办，请说得更具体一点："
                + date.format(DATE_FORMATTER) + " 「" + keyword + "」\n" + items;
    }

    public record DirectCommandResult(Status status, String reply) {
        public static DirectCommandResult handled(String reply) {
            return new DirectCommandResult(Status.HANDLED, reply);
        }

        public static DirectCommandResult fallback() {
            return new DirectCommandResult(Status.FALLBACK, null);
        }

        public static DirectCommandResult failed(String reply) {
            return new DirectCommandResult(Status.FAILED, reply);
        }

        public boolean shouldReturnDirectly() {
            return status == Status.HANDLED || status == Status.FAILED;
        }
    }

    public enum Status {
        HANDLED,
        FALLBACK,
        FAILED
    }

    private record TodoMatch(Todo todo, TodoDate todoDate) {
    }

    private record DateMention(int index, String token, LocalDate date) {
    }
}
