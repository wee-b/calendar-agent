package com.qiniu.back.module.assistant.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.module.assistant.config.AgentModelBeans;
import com.qiniu.back.module.assistant.domain.vo.RouteDecision;
import com.qiniu.back.module.assistant.statemachine.AgentFlowState;
import com.qiniu.back.module.assistant.statemachine.ConversationStage;
import com.qiniu.back.module.assistant.statemachine.UserSignal;
import com.qiniu.back.util.PromptLoader;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.time.LocalDate;

/** 只识别十种用户信号；四种 Agent 和下一阶段均由程序转换表选择。 */
@Component
@Slf4j
public class RouteAgent {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ChatModel chatModel;

    public RouteAgent(@Qualifier(AgentModelBeans.ROUTE) ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public RouteDecision route(String message, String previousReply, AgentFlowState state) {
        String system = PromptLoader.load("route-system.txt")
                + "\n当前日期：" + LocalDate.now()
                + "\n会话阶段：" + (state == null ? ConversationStage.CHAT.name() : state.getStage())
                + "\n当前任务：" + (state == null ? "无" : text(state.getPendingTask()))
                + "\n是否已有规划草稿：" + (state != null && state.getPendingDraftId() != null)
                + "\n规划摘要：" + (state == null ? "" : tail(state.getPendingPayload(), 2000))
                + "\n图片要求：" + (state == null ? "" : text(state.getImageInstruction()));
        String current = "上一轮助手回复：\n" + tail(previousReply, 1500)
                + "\n\n本轮用户消息：\n" + message;
        String reply = chatModel.chat(ChatRequest.builder()
                .messages(new SystemMessage(system), new UserMessage(current))
                .temperature(0.1).build()).aiMessage().text();
        return parse(reply);
    }

    private RouteDecision parse(String reply) {
        try {
            if (reply == null) return fallback();
            int start = reply.indexOf('{'), end = reply.lastIndexOf('}');
            if (start < 0 || end <= start) return fallback();
            RouteDecision result = MAPPER.readValue(reply.substring(start, end + 1), RouteDecision.class);
            if (result.getUserSignal() == null) result.setUserSignal(UserSignal.UNKNOWN);
            return result;
        } catch (Exception exception) {
            log.warn("Route signal parse failed: {}", exception.getMessage());
            return fallback();
        }
    }

    private RouteDecision fallback() {
        RouteDecision result = new RouteDecision();
        result.setUserSignal(UserSignal.UNKNOWN);
        return result;
    }
    private String text(String value) { return value == null ? "" : value; }
    private String tail(String value, int length) {
        String text = text(value);
        return text.length() <= length ? text : text.substring(text.length() - length);
    }
}
