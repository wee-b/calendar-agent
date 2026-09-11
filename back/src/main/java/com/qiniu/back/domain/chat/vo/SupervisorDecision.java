package com.qiniu.back.domain.chat.vo;

import lombok.Data;

@Data
public class SupervisorDecision {
    private boolean needDispatchAgent;
    private String dispatchType;
    private String nextAgent;
    private String reply;
    private String task;

    public static SupervisorDecision fallback(String reply) {
        SupervisorDecision decision = new SupervisorDecision();
        decision.setNeedDispatchAgent(false);
        decision.setDispatchType("NONE");
        decision.setNextAgent("SUPERVISOR");
        decision.setReply(reply);
        return decision;
    }
}
