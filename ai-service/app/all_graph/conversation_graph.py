"""跨轮会话主图：认领状态、识别信号、查表分发并提交阶段。"""

from __future__ import annotations

from collections.abc import Awaitable, Callable, Mapping
import asyncio
import logging
from dataclasses import dataclass
from time import perf_counter
from typing import NotRequired, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime

from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException
from app.core.flow_logging import (
    bind_flow_trace, log_session_transition, log_turn_step, pending_summary, record_chat_input,
    record_completed_chat, text_preview,
)
from app.models.agent_flow_state import YlAgentFlowState
from app.service.chat import ChatService
from app.schemas.statemachine.flow import (
    AgentTurnResult, ConversationStage, PendingTask, UserSignal,
)
from app.schemas.statemachine.transitions import AgentType, ChatTransitionTable
from app.all_graph.nodes.route_node import RouteAgent, RouteNode
from app.all_graph.nodes.summary_node import SummaryNode
from app.schemas.chat.model_stream import AgentStatusData, AgentStatusEvent, AssistantDeltaData, AssistantDeltaEvent


class ConversationState(TypedDict):
    """LangGraph 的图内状态：节点返回的 dict 会合并进本轮状态。

    这里没有配置 checkpointer；跨轮状态由 ChatService 调用仓储持久化，
    下一轮 run_turn 会从数据库重新读取 stage 和 pending。
    """

    user_id: int
    session_id: str
    message: str
    stage: ConversationStage
    pending: PendingTask
    signal: NotRequired[UserSignal]
    task: NotRequired[str | None]
    agent: NotRequired[AgentType]
    target_stage: NotRequired[ConversationStage]
    result: NotRequired[AgentTurnResult]
    context_compressed: NotRequired[bool]
    summary_error: NotRequired[str]
    next_stage: NotRequired[ConversationStage]


@dataclass
class TurnContext:
    """LangGraph Runtime.context：本轮节点可访问，但不会写入图状态。

    token、数据库认领对象和写操作标记只在一次 ainvoke 中有效。
    """

    token: str
    flow_state: YlAgentFlowState
    write_started: bool = False
    emit: Callable | None = None
    is_disconnected: Callable | None = None
    text_emitted: bool = False
    rounds: int = 0
    started: float = 0
    agent_label: str | None = None

    def use_model(self, agent: str, client) -> None:
        config = getattr(client, "config", None)
        provider = config.model.provider if config is not None else "unknown"
        self.agent_label = f"{agent}Agent({provider})"

    def mark_write_started(self) -> None:
        """写节点在第一次外部写调用前标记；异常时保留认领待核实。"""
        self.write_started = True
        log_turn_step("WRITE_STARTED", 写入已开始=True)

    async def ensure_connected(self):
        if self.is_disconnected is not None and await self.is_disconnected():
            raise asyncio.CancelledError()

    async def send(self, event):
        await self.ensure_connected()
        if self.emit is not None:
            await self.emit(event)
            if isinstance(event, AssistantDeltaEvent):
                self.text_emitted = True


# 扩展 Agent 时实现这个签名：读取图状态，返回 reply/completed/pending。
# 执行外部写操作的实现须先调用 context.mark_write_started()。
AgentHandler = Callable[[ConversationState, TurnContext], Awaitable[AgentTurnResult]]


@dataclass(frozen=True)
class ConversationResult:
    reply: str
    signal: UserSignal
    agent: AgentType
    stage: ConversationStage
    pending: PendingTask
    dispatch_type: str
    rounds: int = 0
    elapsed_ms: int = 0


class ConversationGraph:
    """用 LangGraph 编排节点；用转换表和仓储定义业务流转规则。"""

    def __init__(
        self,
        handlers: Mapping[AgentType, AgentHandler] | None = None,
        *,
        user_id: int,
        session_id: str,
        chat_service: ChatService | None = None,
        route_agent: RouteAgent | None = None,
        transitions: ChatTransitionTable | None = None,
        summary_node=None,
    ) -> None:
        if user_id <= 0 or not session_id or not session_id.strip():
            raise ValueError("用户和会话不能为空")
        self.user_id = user_id
        self.session_id = session_id
        self.chat_service = chat_service if chat_service is not None else ChatService()
        self.route_agent = route_agent if route_agent is not None else RouteAgent(chat_service=self.chat_service)
        self.transitions = transitions if transitions is not None else ChatTransitionTable()
        if handlers is None:
            from app.all_graph.nodes.chat_node import ChatNode
            from app.all_graph.nodes.plan_node import PlanNode
            from app.all_graph.nodes.execute_node import ExecuteNode
            from app.all_graph.nodes.image_node import ImageNode
            handlers = {AgentType.CHAT: ChatNode(self.chat_service), AgentType.PLANNER: PlanNode(),
                        AgentType.EXECUTOR: ExecuteNode(), AgentType.IMAGE: ImageNode()}
        self.handlers = dict(handlers)
        route_node = RouteNode(self.route_agent)
        # StateGraph/add_node/add_edge/compile 是 LangGraph 提供的图构建能力。
        # 新增 Agent 类型时，先扩展 AgentType 与 ChatTransitionTable，
        # 再提供对应 AgentHandler；下方循环会自动注册同名处理节点。
        builder = StateGraph(ConversationState, context_schema=TurnContext)

        async def route(state: ConversationState, runtime: Runtime[TurnContext]):
            log_turn_step("ROUTE")
            await runtime.context.send(AgentStatusEvent(data=AgentStatusData(agent="route", round=1, stage="model")))
            return await route_node(state)

        builder.add_node("summary", summary_node if summary_node is not None else SummaryNode())
        builder.add_node("route", route)
        builder.add_node("transition", self._transition)
        for agent in AgentType:
            builder.add_node(agent.name.lower(), self._handler_node(agent))
        builder.add_node("commit", self._commit)
        builder.add_edge(START, "summary")
        builder.add_edge("summary", "route")
        builder.add_edge("route", "transition")
        # 条件边由 LangGraph 根据 transition 节点写入的 agent 选择分支；
        # “哪个信号去哪个 Agent”仍由自有转换表决定，不交给模型或框架。
        builder.add_conditional_edges(
            "transition", lambda state: state["agent"].name.lower(),
            {agent.name.lower(): agent.name.lower() for agent in AgentType},
        )
        for agent in AgentType:
            builder.add_edge(agent.name.lower(), "commit")
        builder.add_edge("commit", END)
        self.graph = builder.compile()

    def _transition(self, state: ConversationState) -> dict:
        """普通图节点：查业务转换表，返回值并入 ConversationState。"""
        rule = self.transitions.resolve(state["stage"], state["signal"])
        log_turn_step("TRANSITION", 信号=state["signal"].name, 节点=rule.agent.name)
        log_session_transition(state["stage"].name, rule.next_stage.name, "待执行",
                               信号=state["signal"].name, 节点=rule.agent.name)
        return {"agent": rule.agent, "target_stage": rule.next_stage}

    def _handler_node(self, agent: AgentType):
        # 将统一的 AgentHandler 适配为 LangGraph 节点。
        # 后续实现 chat/plan/execute/image 时，在构造图时传入 handlers 映射即可。
        async def run(state: ConversationState, runtime: Runtime[TurnContext]) -> dict:
            log_turn_step(agent.name)
            handler = self.handlers.get(agent)
            if handler is None:
                raise RuntimeError(f"{agent.name} Agent 节点尚未接入")
            await runtime.context.send(AgentStatusEvent(data=AgentStatusData(
                agent=agent.name.lower(), round=1, stage="model")))
            result = await handler(state, runtime.context)
            if not isinstance(result, AgentTurnResult):
                raise TypeError(f"{agent.name} Agent 未返回 AgentTurnResult")
            log_turn_step(f"{agent.name}_RESULT", 完成=result.completed, 分发类型=result.dispatch_type,
                          产物=pending_summary(result.pending))
            if not runtime.context.text_emitted:
                await runtime.context.send(AssistantDeltaEvent(data=AssistantDeltaData(round=1, delta=result.reply)))
            return {"result": result}

        return run

    async def _commit(self, state: ConversationState, runtime: Runtime[TurnContext]) -> dict:
        """终点前的业务节点：按处理结果提交数据库，LangGraph 不负责持久化。"""
        result = state["result"]
        next_stage = state["target_stage"] if result.completed else state["stage"]
        # 临时查询不能覆盖尚待确认的规划、执行或图片任务。
        pending = state["pending"] if state["signal"] in {UserSignal.NEW_QUERY, UserSignal.NEW_CHAT} else result.pending
        log_turn_step("COMMIT", 目标阶段=next_stage.name)
        await runtime.context.ensure_connected()
        await self.chat_service.complete_flow_state(
            runtime.context.flow_state, next_stage, pending, state["agent"],
            message=state["message"], reply=result.reply,
            elapsed_ms=int((perf_counter() - runtime.context.started) * 1000),
        )
        log_session_transition(state["stage"].name, next_stage.name, "已提交",
                               信号=state["signal"].name, 节点=state["agent"].name,
                               原任务=pending_summary(state["pending"]), 新任务=pending_summary(pending),
                               version=getattr(runtime.context.flow_state, "version", None))
        return {"next_stage": next_stage, "pending": pending}

    async def run_turn(
        self, message: str, token: str, *, emit=None, is_disconnected=None,
    ) -> ConversationResult:
        """为一次用户输入绑定追踪 ID，普通和流式请求使用同一日志链路。"""
        with bind_flow_trace(self.user_id, self.session_id) as trace:
            record_chat_input(message)
            log_turn_step("RECEIVED", 用户输入=text_preview(message), 输入长度=len(message), 流式=emit is not None)
            try:
                result = await self._run_turn(message, token, emit=emit, is_disconnected=is_disconnected)
            except BaseException as exc:
                if trace.step != "BLOCKED":
                    log_turn_step("CANCELLED" if isinstance(exc, asyncio.CancelledError) else "FAILED",
                                  level=logging.WARNING, 异常类型=type(exc).__name__,
                                  错误码=getattr(exc, "code", None))
                raise
            log_turn_step("END", 最终阶段=result.stage.name, 分发类型=result.dispatch_type)
            return result

    async def _run_turn(
        self, message: str, token: str, *, emit=None, is_disconnected=None,
    ) -> ConversationResult:
        """身份和会话在构造时绑定；每轮仅接收新消息与 token。"""
        if not message.strip():
            raise ValueError("本轮消息不能为空")
        started = perf_counter()
        log_turn_step("LOAD_STATE")
        flow_state = await self.chat_service.get_or_create_flow_state(self.user_id, self.session_id)
        initial_stage = self.chat_service.resolve_flow_stage(flow_state).name
        log_session_transition(initial_stage, initial_stage, "已读取",
                               任务=pending_summary(self.chat_service.flow_pending(flow_state)),
                               processing=bool(flow_state.processing), version=getattr(flow_state, "version", None))
        log_turn_step("CLAIM")
        if not await self.chat_service.claim_flow_state(flow_state):
            log_session_transition(initial_stage, initial_stage, "认领冲突", level=logging.WARNING)
            log_turn_step("BLOCKED", level=logging.WARNING, 原因="会话正在处理或版本已变化")
            raise BusinessException(ErrorCode.CHAT_SESSION_PROCESSING)
        context = TurnContext(token=token, flow_state=flow_state, emit=emit,
                              is_disconnected=is_disconnected, started=started)
        try:
            # 主图先检查压缩，再 route -> transition -> Agent -> commit。
            # context 参数通过 Runtime 注入节点，不混入可持久化的图状态。
            state = await self.graph.ainvoke({
                "user_id": self.user_id,
                "session_id": self.session_id,
                "message": message,
                "stage": self.chat_service.resolve_flow_stage(flow_state),
                "pending": self.chat_service.flow_pending(flow_state),
            }, context=context)
            result = state["result"]
            record_completed_chat(context.agent_label or state["agent"].name.lower(), result.reply)
            return ConversationResult(
                reply=result.reply,
                signal=state["signal"],
                agent=state["agent"],
                stage=state["next_stage"],
                pending=state["pending"],
                dispatch_type=result.dispatch_type,
                rounds=context.rounds,
                elapsed_ms=int((perf_counter() - started) * 1000),
            )
        except BaseException as exc:
            # 未开始外部写入时可安全释放认领；已开始写入则保留待人工核实。
            if not context.write_started and flow_state.processing:
                try:
                    await self.chat_service.release_flow_state_claim(flow_state)
                except BaseException:
                    log_session_transition(initial_stage, initial_stage, "释放认领失败", level=logging.ERROR)
                    raise
            log_session_transition(initial_stage, initial_stage,
                                   "写结果待核实" if context.write_started else "未提交",
                                   level=logging.WARNING, 异常类型=type(exc).__name__,
                                   写入已开始=context.write_started, processing=bool(flow_state.processing))
            if context.write_started and isinstance(exc, Exception):
                raise BusinessException(ErrorCode.WRITE_RESULT_UNCERTAIN) from exc
            raise
