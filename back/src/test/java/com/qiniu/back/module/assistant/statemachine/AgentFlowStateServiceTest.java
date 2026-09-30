package com.qiniu.back.module.assistant.statemachine;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.qiniu.back.exception.BusinessException;
import com.qiniu.back.module.assistant.domain.result.PendingTask;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentFlowStateServiceTest {
    private AgentFlowStateMapper mapper;
    private AgentFlowStateService service;

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), AgentFlowState.class);
        mapper = mock(AgentFlowStateMapper.class);
        service = new AgentFlowStateService(mapper);
    }

    @Test void claimUsesUserSessionVersionAndSeparateProcessingFlag() {
        AgentFlowState state = state();
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        assertTrue(service.claim(state));
        assertEquals(5, state.getVersion());
        assertEquals("PLAN", state.getStage());
        ArgumentCaptor<Wrapper> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), update.capture());
        String where = update.getValue().getSqlSegment();
        for (String field : new String[]{"state_id", "user_id", "session_id", "version", "processing"}) {
            assertTrue(where.contains(field), where);
        }
        LambdaUpdateWrapper<?> wrapper = (LambdaUpdateWrapper<?>) update.getValue();
        assertFalse(wrapper.getSqlSet().contains("stage"));
    }

    @Test void lostClaimCannotCommitAndClearNewerTask() {
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        AgentFlowState state = state();
        assertFalse(service.claim(state));
        assertEquals(4, state.getVersion());
        assertThrows(BusinessException.class,
                () -> service.complete(state, ConversationStage.CHAT, PendingTask.empty(), AgentType.EXECUTOR));
    }

    @Test void activeClaimIsNotAutomaticallyExpired() {
        AgentFlowState state = state();
        state.setProcessing(true);
        state.setUpdateTime(java.time.LocalDateTime.now().minusDays(2));
        assertFalse(service.claim(state));
        verifyNoInteractions(mapper);
    }

    private AgentFlowState state() {
        AgentFlowState state = new AgentFlowState();
        state.setStateId(3L);
        state.setUserId(1L);
        state.setSessionId("session");
        state.setStage("PLAN");
        state.setVersion(4L);
        return state;
    }
}
