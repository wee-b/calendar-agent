package com.qiniu.back.module.assistant.service.impl;

import com.qiniu.back.module.assistant.agent.*;
import com.qiniu.back.module.assistant.domain.model.PlanDraft;
import com.qiniu.back.module.assistant.domain.result.*;
import com.qiniu.back.module.assistant.domain.vo.RouteDecision;
import com.qiniu.back.module.assistant.service.*;
import com.qiniu.back.module.assistant.statemachine.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用真实转换表和四 Agent 分派器验证完整多轮流程，外部模型与数据库使用替身。 */
class ChatServiceImplTest {
    private ChatAgent chat;
    private PlannerAgent planner;
    private ExecutorAgent executor;
    private ImageAgent image;
    private RouteAgent route;
    private PlanDraftService drafts;
    private PlanClarificationPolicy clarification;
    private MemoryStates states;
    private ChatServiceImpl service;

    @BeforeEach void setup() {
        chat = mock(ChatAgent.class);
        planner = mock(PlannerAgent.class);
        executor = mock(ExecutorAgent.class);
        image = mock(ImageAgent.class);
        route = mock(RouteAgent.class);
        drafts = mock(PlanDraftService.class);
        clarification = mock(PlanClarificationPolicy.class);
        states = new MemoryStates();
        ProgressReporter reporter = new ProgressReporter();
        service = new ChatServiceImpl(mock(ChatDialogueService.class), route, states, new ChatTransitionTable(),
                new ConversationAgentDispatcher(chat, planner, executor, image, drafts, clarification, reporter), reporter);
    }

    @Test void planQueryImageRevisionThenSyncUsesTheOriginalDraft() {
        signal("做计划", UserSignal.NEW_PLAN);
        when(planner.generateDraft("做计划")).thenReturn(new PlannerAgent.GeneratedPlan(17L, "规划正文"));
        assertEquals("PLAN", send("做计划").flowStage());

        signal("查明天", UserSignal.NEW_QUERY);
        when(chat.query("查明天")).thenReturn("明天有两项待办");
        assertEquals("PLAN", send("查明天").flowStage());
        assertEquals(17L, state().getPendingDraftId());

        signal("好的", UserSignal.CONFIRM);
        assertEquals("PLAN_FEEDBACK", send("好的").dispatchType());
        verifyNoInteractions(executor, image);

        PlanDraft draft = new PlanDraft();
        draft.setDraftId(17L);
        when(drafts.findPending(1L, "s", 17L)).thenReturn(Optional.of(draft));
        when(image.generate(eq(draft), anyString())).thenReturn("图片");
        signal("画图", UserSignal.GENERATE_PLAN_IMAGE);
        assertEquals("IMAGE", send("画图").flowStage());
        signal("换成蓝色", UserSignal.MODIFY);
        assertEquals("IMAGE", send("换成蓝色").flowStage());
        assertTrue(state().getImageInstruction().contains("换成蓝色"));
        assertTrue(state().getImageInstruction().contains("画图"));
        assertEquals(17L, state().getPendingDraftId());
        assertEquals("规划正文", state().getPendingPayload());

        signal("同步", UserSignal.SYNC_PLAN);
        when(executor.applyPlan(draft)).thenReturn("已同步");
        assertEquals("CHAT", send("同步").flowStage());
        assertNull(state().getPendingDraftId());
        verify(executor).applyPlan(draft);
        verify(drafts, never()).findLatestPending(anyLong(), anyString());
    }

    @Test void allNewWritesWaitAndConfirmationUsesTheRevisedTask() {
        signal("删除明天待办", UserSignal.NEW_EXECUTE);
        assertEquals("EXECUTE", send("删除明天待办").flowStage());
        verifyNoInteractions(executor);
        signal("只删除早上的", UserSignal.MODIFY);
        assertEquals("REFINE", send("只删除早上的").dispatchType());
        String revised = state().getPendingTask();
        assertTrue(revised.contains("只删除早上的"));
        verifyNoInteractions(executor);
        signal("确认", UserSignal.CONFIRM);
        when(executor.executeConfirmed(revised)).thenReturn(new ExecutorAgent.ExecutionResult("完成", true));
        assertEquals("CHAT", send("确认").flowStage());
        verify(executor).executeConfirmed(revised);
        assertNull(state().getPendingTask());
        // 再次确认没有活动任务，不能再次进入 Executor。
        send("确认");
        verify(executor, times(1)).executeConfirmed(anyString());
    }

    @Test void rejectedOrReplacedTasksCanNoLongerBeExecuted() {
        signal("旧操作", UserSignal.NEW_EXECUTE);
        send("旧操作");
        signal("新计划", UserSignal.NEW_PLAN);
        when(planner.generateDraft("新计划")).thenReturn(new PlannerAgent.GeneratedPlan(19L, "新草稿"));
        send("新计划");
        signal("确认", UserSignal.CONFIRM);
        send("确认");
        verifyNoInteractions(executor);
        signal("取消", UserSignal.REJECT);
        assertEquals("CHAT", send("取消").flowStage());
        assertNull(state().getPendingDraftId());
    }

    @Test void plannerClarificationAndModificationStayInPlan() {
        signal("做计划", UserSignal.NEW_PLAN);
        when(clarification.shouldAsk(1L, "s", "做计划", 10L)).thenReturn(true);
        assertEquals("PLAN_CLARIFICATION", send("做计划").dispatchType());
        assertNull(state().getPendingDraftId());
        signal("每天两小时", UserSignal.MODIFY);
        when(planner.generateDraft(contains("每天两小时"))).thenReturn(new PlannerAgent.GeneratedPlan(20L, "草稿"));
        assertEquals("PLAN", send("每天两小时").flowStage());
        assertEquals(20L, state().getPendingDraftId());
    }

    @Test void imageFailureRetainsPlanAndCanBeRetried() {
        states.seed(1L, "s", "PLAN", new PendingTask("计划", 22L, "正文", null));
        PlanDraft draft = new PlanDraft();
        when(drafts.findPending(1L, "s", 22L)).thenReturn(Optional.of(draft));
        when(image.generate(eq(draft), anyString())).thenThrow(new IllegalStateException("timeout"));
        signal("画图", UserSignal.GENERATE_PLAN_IMAGE);
        assertEquals("ERROR", send("画图").dispatchType());
        assertEquals("PLAN", state().getStage());
        assertFalse(state().isProcessing());
        assertEquals(22L, state().getPendingDraftId());
    }

    @Test void missingDraftCannotClearStateOrCallExecutor() {
        states.seed(1L, "s", "IMAGE", new PendingTask("计划", 23L, "正文", "蓝色"));
        when(drafts.findPending(1L, "s", 23L)).thenReturn(Optional.empty());
        signal("同步", UserSignal.SYNC_PLAN);
        assertEquals("IMAGE", send("同步").flowStage());
        verifyNoInteractions(executor);
    }

    @Test void uncertainWriteRetainsClaimAndBlocksRepeat() {
        states.seed(1L, "s", "EXECUTE", PendingTask.instruction("删除任务"));
        signal("确认", UserSignal.CONFIRM);
        when(executor.executeConfirmed("删除任务")).thenThrow(new IllegalStateException("connection lost"));
        assertEquals("ERROR", send("确认").dispatchType());
        assertTrue(state().isProcessing());
        assertEquals("PROCESSING", send("确认").dispatchType());
        verify(executor, times(1)).executeConfirmed("删除任务");
    }

    @Test void modelClarificationWithoutAWrittenToolKeepsConfirmation() {
        states.seed(1L, "s", "EXECUTE", PendingTask.instruction("删除任务"));
        signal("确认", UserSignal.CONFIRM);
        when(executor.executeConfirmed("删除任务")).thenReturn(new ExecutorAgent.ExecutionResult("请说明哪一项", false));
        assertEquals("EXECUTE", send("确认").flowStage());
        assertFalse(state().isProcessing());
    }

    @Test void parallelConfirmationOnlyCallsExecutorOnce() throws Exception {
        states.seed(1L, "s", "EXECUTE", PendingTask.instruction("删除任务"));
        signal("确认", UserSignal.CONFIRM);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(executor.executeConfirmed("删除任务")).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return new ExecutorAgent.ExecutionResult("完成", true);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ChatDispatchResult> first = pool.submit(() -> send("确认"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("PROCESSING", send("确认").dispatchType());
            release.countDown();
            assertEquals("CHAT", first.get(5, TimeUnit.SECONDS).flowStage());
            verify(executor, times(1)).executeConfirmed("删除任务");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void sameSessionIdIsIsolatedByUser() {
        states.seed(1L, "s", "EXECUTE", PendingTask.instruction("用户一操作"));
        signal("确认", UserSignal.CONFIRM);
        assertEquals("CHAT", service.process(2L, "s", "确认", 10L).flowStage());
        assertEquals("EXECUTE", state().getStage());
        verifyNoInteractions(executor);
    }

    private void signal(String message, UserSignal signal) {
        RouteDecision decision = new RouteDecision();
        decision.setTask(message);
        decision.setUserSignal(signal);
        when(route.route(eq(message), nullable(String.class), any(AgentFlowState.class))).thenReturn(decision);
    }
    private ChatDispatchResult send(String message) { return service.process(1L, "s", message, 10L); }
    private AgentFlowState state() { return states.getOrCreate(1L, "s"); }

    private static class MemoryStates extends AgentFlowStateService {
        private record Key(Long user, String session) {}
        private final Map<Key, AgentFlowState> rows = new HashMap<>();
        MemoryStates() { super(null); }
        synchronized void seed(Long user, String session, String stage, PendingTask pending) {
            AgentFlowState row = new AgentFlowState();
            row.setStateId((long) rows.size() + 1);
            row.setUserId(user);
            row.setSessionId(session);
            row.setStage(stage);
            apply(row, pending);
            rows.put(new Key(user, session), row);
        }
        @Override public synchronized AgentFlowState getOrCreate(Long user, String session) {
            if (!rows.containsKey(new Key(user, session))) seed(user, session, "CHAT", PendingTask.empty());
            AgentFlowState copy = new AgentFlowState();
            BeanUtils.copyProperties(rows.get(new Key(user, session)), copy);
            return copy;
        }
        @Override public synchronized boolean claim(AgentFlowState snapshot) {
            AgentFlowState row = row(snapshot);
            if (row.isProcessing() || row.getVersion() != snapshot.getVersion()) return false;
            row.setProcessing(true);
            row.setVersion(row.getVersion() + 1);
            snapshot.setVersion(row.getVersion());
            return true;
        }
        @Override public synchronized void complete(AgentFlowState snapshot, ConversationStage next,
                                                    PendingTask pending, AgentType agent) {
            AgentFlowState row = row(snapshot);
            assertTrue(row.isProcessing());
            assertEquals(snapshot.getVersion(), row.getVersion());
            row.setStage(next.name());
            apply(row, pending);
            row.setProcessing(false);
            row.setVersion(row.getVersion() + 1);
        }
        @Override public synchronized void releaseClaim(AgentFlowState snapshot) {
            AgentFlowState row = row(snapshot);
            assertEquals(snapshot.getVersion(), row.getVersion());
            row.setProcessing(false);
            row.setVersion(row.getVersion() + 1);
        }
        private AgentFlowState row(AgentFlowState snapshot) { return rows.get(new Key(snapshot.getUserId(), snapshot.getSessionId())); }
        private void apply(AgentFlowState row, PendingTask pending) {
            row.setPendingTask(pending.task());
            row.setPendingDraftId(pending.draftId());
            row.setPendingPayload(pending.planPreview());
            row.setImageInstruction(pending.imageInstruction());
        }
    }
}
