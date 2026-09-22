package com.qiniu.back.module.assistant.service;

import com.qiniu.back.module.memory.service.UserMemoryService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanClarificationPolicyTest {

    private final UserMemoryService memoryService = mock(UserMemoryService.class);
    private final PlanClarificationPolicy policy = new PlanClarificationPolicy(memoryService);

    @Test
    void concreteTimeAndDestinationSkipClarification() {
        assertFalse(policy.shouldAsk(1L, "s1", "中秋节去杭州旅游，帮我规划时间安排", 1L));
    }

    @Test
    void fourOrMoreDetailsAlwaysSkipClarification() {
        String detailed = "国庆去北京玩5天，预算5000元，两个人，喜欢美食和博物馆，坐高铁";
        assertTrue(policy.countConcreteInformation(detailed) >= 4);
        assertFalse(policy.shouldAsk(1L, "s1", detailed, 1L));
    }

    @Test
    void savedPreferenceSkipsClarificationEvenForSparseRequest() {
        when(memoryService.hasActivePlanningPreference(1L)).thenReturn(true);
        assertFalse(policy.shouldAsk(1L, "s1", "帮我做个计划", 1L));
    }

    @Test
    void onlyOneOfThreeSparseRequestsAsks() {
        when(memoryService.hasActivePlanningPreference(1L)).thenReturn(false);
        assertTrue(policy.shouldAsk(1L, "s1", "帮我做个计划", 1L));
        assertFalse(policy.shouldAsk(1L, "s2", "帮我做个计划", 2L));
        assertFalse(policy.shouldAsk(1L, "s3", "帮我做个计划", 3L));
    }
}
