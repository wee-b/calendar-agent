package com.qiniu.back.module.assistant.domain.vo;

import com.qiniu.back.module.assistant.statemachine.UserSignal;
import lombok.Data;

@Data
public class SupervisorDecision {
    private boolean needDispatchAgent;
    private String dispatchType;
    private String nextAgent;
    private String reply;
    private String task;
    private UserSignal userSignal;

    public static SupervisorDecision fallback(String reply) {
        SupervisorDecision decision = new SupervisorDecision();
        decision.setNeedDispatchAgent(false);
        decision.setDispatchType("NONE");
        decision.setNextAgent("SUPERVISOR");
        decision.setReply(reply);
        return decision;
    }
}
