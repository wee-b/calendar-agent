package com.qiniu.back.module.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.domain.ErrorCode;
import com.qiniu.back.domain.chat.PlanDraft;
import com.qiniu.back.domain.chat.dto.PlanDraftDTO;
import com.qiniu.back.domain.chat.dto.PlanTodoDTO;
import com.qiniu.back.domain.chat.vo.PlanSyncResultVO;
import com.qiniu.back.domain.todo.dto.TodoCreateDTO;
import com.qiniu.back.domain.todo.vo.TodoVO;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.chat.mapper.PlanDraftMapper;
import com.qiniu.back.module.todo.service.TodoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PlanDraftService {

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_SYNCED = "synced";
    private static final String STATUS_EXPIRED = "expired";
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final List<String> CONFIRM_WORDS = List.of(
            "可以", "确认", "同步", "添加",
            "帮我添加", "添加到日历", "加入日历", "就按这个",
            "没问题", "好的", "好", "行",
            "需要", "是的", "对", "可以的", "ok", "OK", "yes", "Yes");

    @Autowired
    private PlanDraftMapper planDraftMapper;

    @Autowired
    private TodoService todoService;

    @Autowired
    private ObjectMapper objectMapper;

    public boolean isConfirmMessage(String message) {
        if (message == null) return false;
        String text = message.trim();
        if (text.isEmpty() || text.length() > 20) return false;
        return CONFIRM_WORDS.stream().anyMatch(text::contains);
    }

    public Optional<PlanDraft> findLatestPending(Long userId, String sessionId) {
        PlanDraft draft = planDraftMapper.selectOne(new LambdaQueryWrapper<PlanDraft>()
                .eq(PlanDraft::getUserId, userId)
                .eq(PlanDraft::getSessionId, sessionId)
                .eq(PlanDraft::getStatus, STATUS_PENDING)
                .orderByDesc(PlanDraft::getCreateTime)
                .last("LIMIT 1"));
        return Optional.ofNullable(draft);
    }

    public Long savePendingDraft(Long userId, String sessionId, String sourceMessage, String rawPlanJson) {
        String planJson = extractJson(rawPlanJson);
        PlanDraftDTO dto = parseAndValidate(planJson);

        planDraftMapper.update(null, new LambdaUpdateWrapper<PlanDraft>()
                .eq(PlanDraft::getUserId, userId)
                .eq(PlanDraft::getSessionId, sessionId)
                .eq(PlanDraft::getStatus, STATUS_PENDING)
                .set(PlanDraft::getStatus, STATUS_EXPIRED));

        PlanDraft draft = new PlanDraft();
        draft.setUserId(userId);
        draft.setSessionId(sessionId);
        draft.setGoal(dto.getGoal());
        draft.setPlanJson(planJson);
        draft.setStatus(STATUS_PENDING);
        draft.setSourceMessage(sourceMessage);
        planDraftMapper.insert(draft);
        return draft.getDraftId();
    }

    @Transactional
    public PlanSyncResultVO syncDraft(PlanDraft draft) {
        if (!STATUS_PENDING.equals(draft.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Plan draft has already been handled");
        }

        PlanDraftDTO plan = parseAndValidate(draft.getPlanJson());
        PlanSyncResultVO result = new PlanSyncResultVO();
        result.setDraftId(draft.getDraftId());
        result.setGoal(plan.getGoal());

        result.getCreatedTodos().addAll(todoService.batchCreate(
                plan.getTodos().stream().map(this::toCreateDTO).toList()));

        result.setCreatedCount(result.getCreatedTodos().size());
        draft.setStatus(STATUS_SYNCED);
        planDraftMapper.updateById(draft);
        return result;
    }

    public String buildSyncReply(PlanSyncResultVO result) {
        StringBuilder sb = new StringBuilder();
        sb.append("已同步到日历，共创建 ")
                .append(result.getCreatedCount())
                .append(" 个待办");
        if (result.getGoal() != null && !result.getGoal().isBlank()) {
            sb.append("：").append(result.getGoal());
        }
        sb.append("\n");

        for (TodoVO todo : result.getCreatedTodos()) {
            sb.append("- ")
                    .append(todo.getStartDate());
            if (!todo.getStartDate().equals(todo.getEndDate())) {
                sb.append(" ~ ").append(todo.getEndDate());
            }
            sb.append(" ").append(todo.getTitle()).append("\n");
        }
        return sb.toString().trim();
    }

    private TodoCreateDTO toCreateDTO(PlanTodoDTO item) {
        TodoCreateDTO dto = new TodoCreateDTO();
        dto.setTitle(item.getTitle());
        dto.setColor(isValidColor(item.getColor()) ? item.getColor() : "#5c4b37");
        dto.setDayContent(item.getDayContent() == null || item.getDayContent().isBlank()
                ? item.getTitle() : item.getDayContent());
        dto.setStartDate(item.getStartDate());
        dto.setEndDate(item.getEndDate());
        dto.setWeekDays(item.getWeekDays());
        return dto;
    }

    private PlanDraftDTO parseAndValidate(String planJson) {
        try {
            PlanDraftDTO dto = objectMapper.readValue(planJson, PlanDraftDTO.class);
            validatePlan(dto);
            return dto;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Plan draft JSON parse failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid plan draft JSON");
        }
    }

    private void validatePlan(PlanDraftDTO dto) {
        if (dto == null || dto.getTodos() == null || dto.getTodos().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Plan draft has no todos");
        }
        LocalDate today = LocalDate.now();
        for (PlanTodoDTO item : dto.getTodos()) {
            if (item.getTitle() == null || item.getTitle().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Plan draft contains empty todo title");
            }
            if (item.getStartDate() == null || item.getEndDate() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Plan draft contains empty date");
            }
            if (item.getStartDate().isAfter(item.getEndDate())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Plan draft date range is invalid");
            }
            if (item.getStartDate().getYear() < today.getYear()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Plan draft year is earlier than current year");
            }
            if (item.getWeekDays() == null || item.getWeekDays().isEmpty()
                    || item.getWeekDays().stream().anyMatch(day -> day == null || day < 1 || day > 7)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Plan draft contains invalid weekDays");
            }
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Plan draft is empty");
        }
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Plan draft is not JSON");
        }
        return text.substring(start, end + 1);
    }

    private boolean isValidColor(String color) {
        return color != null && COLOR_PATTERN.matcher(color).matches();
    }
}
