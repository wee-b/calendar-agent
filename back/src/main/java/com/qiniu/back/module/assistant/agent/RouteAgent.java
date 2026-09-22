package com.qiniu.back.module.assistant.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.module.assistant.config.AgentModelBeans;
import com.qiniu.back.module.assistant.domain.vo.RouteDecision;
import com.qiniu.back.module.assistant.statemachine.AgentFlowState;
import com.qiniu.back.module.assistant.statemachine.AgentFlowStateService;
import com.qiniu.back.module.assistant.statemachine.UserSignal;
import com.qiniu.back.util.PromptLoader;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 把用户消息转成结构化事件，交给状态机执行。 */
@Component
@Slf4j
public class RouteAgent {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final Set<UserSignal> READY_SIGNALS = Set.of(
            UserSignal.READY_CHAT, UserSignal.READY_QUERY, UserSignal.READY_SINGLE_DAY_ACTION,
            UserSignal.READY_SINGLE_DAY_CONFIRM, UserSignal.READY_EXECUTE, UserSignal.READY_PLAN);
    private static final Set<UserSignal> PENDING_SIGNALS = Set.of(
            UserSignal.CONFIRM, UserSignal.REJECT, UserSignal.MODIFY,
            UserSignal.NEW_REQUEST, UserSignal.UNKNOWN);

    private final ChatModel chatModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public RouteAgent(@Qualifier(AgentModelBeans.ROUTE) ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public RouteDecision route(String message, String previousAssistantReply, AgentFlowState state) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(state)));
        messages.add(new UserMessage(currentTurn(previousAssistantReply, message)));
        String text = chatModel.chat(ChatRequest.builder()
                .messages(messages)
                .temperature(0.1)
                .build()).aiMessage().text();
        return parseDecision(text, state);
    }

    private String buildSystemPrompt(AgentFlowState state) {
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();
        String dateContext = today.format(DATE_FORMAT) + "（"
                + dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE) + "）";
        return PromptLoader.load("route-system.txt") + "\n\n## 时间上下文\n当前日期: " + dateContext
                + "\n用户说\"今天\"就是" + today.format(DATE_FORMAT)
                + "，\"明天\"就是" + today.plusDays(1).format(DATE_FORMAT)
                + flowContext(state)
                + decisionProtocol();
    }

    private String flowContext(AgentFlowState state) {
        if (state == null) {
            return "\n\n## 当前流程状态\nREADY：当前没有待处理任务。";
        }
        return "\n\n## 当前流程状态\n"
                + "stage=" + state.getStage()
                + "\nwaitingFor=" + waitingFor(state)
                + "\ncurrentAgent=" + state.getCurrentAgent()
                + "\nnextAgent=" + state.getNextAgent()
                + "\npendingTask=" + value(state.getPendingTask())
                + "\npendingPayload=" + value(state.getPendingPayload());
    }

    private String waitingFor(AgentFlowState state) {
        if (AgentFlowStateService.STAGE_WAIT_FEEDBACK.equals(state.getStage())) {
            return "用户是否把当前规划草稿同步到日历。短回复“需要/同步/确认/好的/可以”都是 CONFIRM。";
        }
        if (AgentFlowStateService.AGENT_PLANNER.equals(state.getNextAgent())) {
            return "用户是否开始生成规划或是否跳过补充信息直接规划。短回复“需要/开始规划/确认/好的/不补充信息，直接规划”都是 CONFIRM；预算、时间、偏好等补充内容是 MODIFY。";
        }
        return "用户是否执行上一项写操作。短回复“确认/好的/可以/执行”都是 CONFIRM。";
    }

    private String decisionProtocol() {
        return """

                ## 本轮输出协议
                你只负责路由，不直接回复用户，不调用工具。只输出 JSON，不要输出 markdown 或额外解释。
                JSON 格式：
                {"userSignal":"READY_CHAT","task":"明确任务"}

                当前流程为 READY 时，userSignal 只能是：
                - READY_CHAT：闲聊、需要追问或不执行日程操作，交给 ChatAgent 回复
                - READY_QUERY：只读查询
                - READY_SINGLE_DAY_ACTION：意图、日期、目标明确的单日写操作
                - READY_SINGLE_DAY_CONFIRM：用户对单日操作只是推测、试探或不确定
                - READY_EXECUTE：整个待办、周期、跨日或日记写操作
                - READY_PLAN：需要拆解的复杂规划

                当前存在待处理流程时，userSignal 只能是：
                - CONFIRM：同意继续当前等待的动作。包括回答“需要同步吗”“开始规划吗”“是否直接规划”“确认执行吗”的肯定短句，例如“需要”“同步”“确认”“好的”“可以”“是的”“不补充信息，直接规划”
                - REJECT：明确取消上一项任务
                - MODIFY：补充约束、修改内容或回答澄清问题，但不是在回答是否继续
                - NEW_REQUEST：明确提出一项与上一任务无关且可以独立执行的新请求
                - UNKNOWN：内容无法理解

                上一轮如果在问是否同步、是否开始规划、是否跳过补充直接规划或是否执行，用户的肯定短句必须是 CONFIRM，不能标成 MODIFY 或 UNKNOWN。
                只有用户补充条件、改目标、改时间或纠正内容时才用 MODIFY。
                只有明确独立的新任务才能使用 NEW_REQUEST。
                不要因为删除或修改有副作用就自动要求确认，是否确认只取决于用户意图是否明确。
                必须结合上一轮助手回复判断本轮用户消息。上一轮在问是否同步、是否开始规划、是否跳过补充直接规划或是否执行时，用户回答“需要”就是 CONFIRM。
                只使用上一轮助手回复和本轮用户消息，禁止执行历史请求。
                """;
    }

    private RouteDecision parseDecision(String text, AgentFlowState state) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) return normalize(RouteDecision.fallback(), state);
            RouteDecision decision = mapper.readValue(text.substring(start, end + 1), RouteDecision.class);
            return normalize(decision, state);
        } catch (Exception exception) {
            log.warn("Route agent decision parse failed: {}", exception.getMessage());
            return normalize(RouteDecision.fallback(), state);
        }
    }

    private RouteDecision normalize(RouteDecision decision, AgentFlowState state) {
        if (state == null) {
            if (decision.getUserSignal() == null
                    || !READY_SIGNALS.contains(decision.getUserSignal())) {
                decision.setUserSignal(UserSignal.READY_CHAT);
            }
        } else if (decision.getUserSignal() == null
                || !PENDING_SIGNALS.contains(decision.getUserSignal())) {
            decision.setUserSignal(UserSignal.UNKNOWN);
        }
        return decision;
    }

    private String currentTurn(String previousAssistantReply, String currentUserMessage) {
        return "上一轮助手回复：\n" + keepTail(previousAssistantReply, 1500)
                + "\n\n本轮用户消息：\n" + (currentUserMessage == null ? "" : currentUserMessage)
                + "\n\n请结合上一轮助手回复理解本轮用户消息，再输出结构化 JSON。";
    }

    private String keepTail(String text, int maxLen) {
        if (text == null || text.isBlank()) return "（没有上一轮助手回复）";
        if (text.length() <= maxLen) return text;
        return "...(前文已省略)\n" + text.substring(text.length() - maxLen);
    }

    private String value(String text) {
        return text == null || text.isBlank() ? "(empty)" : text;
    }
}
