package com.qiniu.back.module.assistant.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanDraftServiceTest {

    private final PlanDraftService service = new PlanDraftService();

    @Test
    void confirmationRequiresACompleteKnownReply() {
        assertTrue(service.isConfirmMessage("好的"));
        assertTrue(service.isConfirmMessage("请同步到日历"));
        assertFalse(service.isConfirmMessage("不可以"));
        assertFalse(service.isConfirmMessage("不要同步"));
        assertFalse(service.isConfirmMessage("好的，但是改成每天2小时"));
    }
}
