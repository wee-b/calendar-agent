package com.qiniu.back.module.assistant.domain.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qiniu.back.module.assistant.statemachine.UserSignal;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RouteDecision {
    private String task;
    private UserSignal userSignal;

    public static RouteDecision fallback() {
        return new RouteDecision();
    }
}
