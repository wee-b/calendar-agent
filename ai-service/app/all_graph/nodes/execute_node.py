"""写操作只有确认后执行；调用 Java MCP，禁止自动重试及直接写业务表。"""

import asyncio
import json

from pydantic import ValidationError

from app.core.config.agent.agents import executor_config
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException, McpClientError
from app.helper.mcp_client import JavaMcpClient
from app.helper.model_client import ModelClient
from app.repository.plan_draft import PlanDraftRepository
from app.schemas.chat.model_stream import (
    AgentStatusData, AgentStatusEvent, AssistantMessage, ModelDelta,
    ToolCallData, ToolCallLifecycleEvent, ToolResultData, ToolResultEvent,
)
from app.schemas.executor_tools import ARGUMENTS, READ_TOOLS, BatchCreateResult, validate_write_result
from app.schemas.plan import parse_plan, today
from app.schemas.statemachine.flow import AgentTurnResult, ConversationStage, PendingTask, UserSignal
from app.service.model_stream import StreamResponseAccumulator


EXECUTOR_PROMPT = """你是日历执行助手，只执行下面已向用户展示并确认的任务。
通过工具查询真实 ID，不得编造待办 ID、日期或用户身份。不得扩大已确认任务范围。
删除整个目标使用 deleteTodo；删除某一天使用 removeTodoDay。
单日改期使用 removeTodoDay + addTodoDay，修改全天目标范围使用 updateTodo。
每个写操作只调用一次，已成功的写入不能再次执行。最终回复以工具返回结果为准。
缺少明确操作对象或必要参数时向用户追问；不能凭不确定的指代写入。
只输出简洁中文回答，不输出工具参数或 JSON。"""


class ExecuteNode:
    def __init__(self, model=None, mcp_factory=None, drafts=None):
        self.model = model if model is not None else ModelClient(executor_config)
        self.mcp_factory = mcp_factory if mcp_factory is not None else JavaMcpClient
        self._drafts = drafts

    @property
    def drafts(self):
        if self._drafts is None:
            self._drafts = PlanDraftRepository()
        return self._drafts

    async def __call__(self, state, context) -> AgentTurnResult:
        pending, signal = state["pending"], state["signal"]
        if signal in {UserSignal.NEW_EXECUTE, UserSignal.MODIFY}:
            task = ((state.get("task") or state["message"]) if signal == UserSignal.NEW_EXECUTE
                    else (pending.task or "") + "\n用户补充/修改：" + state["message"])
            return AgentTurnResult(reply=f"待执行内容：\n{task}\n确认执行吗？", completed=True,
                                   pending=PendingTask(task=task),
                                   dispatch_type="EXECUTE_CONFIRM" if signal == UserSignal.NEW_EXECUTE else "REFINE")
        if signal == UserSignal.SYNC_PLAN and state["stage"] in {ConversationStage.PLAN, ConversationStage.IMAGE}:
            return await self._sync_plan(state, context)
        if signal != UserSignal.CONFIRM or state["stage"] != ConversationStage.EXECUTE or not pending.task:
            return AgentTurnResult(reply="没有找到待确认的操作，请重新描述任务。", completed=False,
                                   pending=pending, dispatch_type="PENDING_UNKNOWN")
        # Route 的 task 不能替换待确认任务；只使用数据库里的 pending.task。
        answer, writes = await self._execute(pending.task, context)
        return AgentTurnResult(reply=answer, completed=writes > 0,
                               pending=PendingTask() if writes else pending,
                               dispatch_type="EXECUTE" if writes else "EXECUTE_CONFIRM")

    async def _sync_plan(self, state, context):
        draft = await self.drafts.find_pending(state["user_id"], state["session_id"], state["pending"].draft_id)
        if draft is None:
            return AgentTurnResult(reply="当前没有可用的规划草稿，请先生成或重新制定规划。", completed=False,
                                   pending=state["pending"], dispatch_type="PENDING_UNKNOWN")
        plan = parse_plan(draft.plan_json)
        async with self.mcp_factory() as mcp:
            tools = {tool.name: tool for tool in await mcp.list_tools(context.token)}
            tool = tools.get("batchCreateTodos")
            if tool is None or tool.metadata.readOnly:
                raise BusinessException(ErrorCode.MCP_BATCH_UNAVAILABLE)
            call_id = f"plan-{draft.draft_id}"
            await context.send(ToolCallLifecycleEvent(event="tool_call_start",
                data=ToolCallData(round=1, call_id=call_id, tool=tool.name)))
            await context.send(ToolCallLifecycleEvent(event="tool_call_end",
                data=ToolCallData(round=1, call_id=call_id, tool=tool.name)))
            raw = await self._call(mcp, tool, {"todos": [todo.create_arguments() for todo in plan.todos]},
                                   context, call_id, 1)
        result = BatchCreateResult.model_validate(raw)
        if result.createdCount != len(plan.todos) or len(result.createdTodos) != len(plan.todos):
            raise BusinessException(ErrorCode.MCP_RESULT_INVALID)
        await self.drafts.mark_synced(state["user_id"], state["session_id"], draft.draft_id)
        return AgentTurnResult(reply=f"已同步到日历，共创建 {result.createdCount} 个待办：{plan.goal or '规划'}。",
                               completed=True, pending=PendingTask(), dispatch_type="EXECUTE")

    async def _execute(self, instruction, context):
        context.use_model("executor", self.model)
        messages = [{"role": "system", "content": EXECUTOR_PROMPT + f"\n当前日期：{today()}"},
                    {"role": "user", "content": instruction}]
        writes, answer, performed = 0, "", set()
        async with self.mcp_factory() as mcp:
            catalog = {t.name: t for t in await mcp.list_tools(context.token)
                       if t.name in ARGUMENTS and t.metadata.readOnly == (t.name in READ_TOOLS)}
            if not catalog:
                raise BusinessException(ErrorCode.MODEL_TOOL_UNAPPROVED)
            definitions = [{"type": "function", "function": {
                "name": t.name, "description": t.description, "parameters": t.inputSchema,
            }} for t in catalog.values()]
            for round_number in range(1, 4):
                tools = definitions if round_number < 3 else None
                await context.send(AgentStatusEvent(data=AgentStatusData(agent="executor", round=round_number, stage="model")))
                if context.emit is None:
                    response = AssistantMessage.model_validate(await self.model.chat(messages, tools=tools))
                else:
                    events = []
                    accumulator = StreamResponseAccumulator(round_number, events.append, allowed_tools=set(catalog))
                    async for delta in self.model.stream_chat(messages, tools=tools):
                        accumulator.add(ModelDelta.model_validate(delta))
                        for event in events:
                            await context.send(event)
                        events.clear()
                    response = accumulator.finish()
                    for event in events:
                        await context.send(event)
                context.rounds = max(context.rounds, round_number)
                answer += response.content or ""
                if not response.tool_calls:
                    if not response.content or not response.content.strip():
                        raise BusinessException(ErrorCode.MODEL_EMPTY_ANSWER)
                    return answer, writes
                if tools is None:
                    raise BusinessException(ErrorCode.MODEL_ROUND_LIMIT)
                # 先校验本轮全部调用，避免前一个已写入，后一个才发现参数格式非法。
                prepared, call_ids = [], set()
                for call in response.tool_calls:
                    name = call.function.name
                    if not call.id or call.id in call_ids or name not in catalog:
                        raise BusinessException(ErrorCode.MODEL_TOOL_UNAPPROVED)
                    call_ids.add(call.id)
                    try:
                        args = ARGUMENTS[name].model_validate_json(call.function.arguments, strict=True)
                    except ValidationError as exc:
                        raise BusinessException(ErrorCode.MODEL_TOOL_ARGUMENT_INVALID) from exc
                    arguments = args.model_dump(mode="json", exclude_none=True)
                    signature = name + json.dumps(arguments, sort_keys=True, ensure_ascii=False)
                    if name not in READ_TOOLS:
                        if signature in performed:
                            raise BusinessException(ErrorCode.MODEL_TOOL_DUPLICATE)
                        performed.add(signature)
                    prepared.append((call, arguments))
                messages.append(response.model_dump(mode="json", exclude_none=True))
                for call, arguments in prepared:
                    name = call.function.name
                    data = await self._call(mcp, catalog[name], arguments, context, call.id, round_number)
                    if name not in READ_TOOLS:
                        writes += 1
                    messages.append({"role": "tool", "tool_call_id": call.id,
                                     "content": json.dumps(data, ensure_ascii=False)})
        raise BusinessException(ErrorCode.MODEL_ROUND_LIMIT)

    async def _call(self, mcp, tool, arguments, context, call_id, round_number):
        await context.send(AgentStatusEvent(data=AgentStatusData(agent="executor", round=round_number, stage="tool")))
        # 位于所有参数检查之后、真实写调用之前。模型只追问/只读时不会设置标记。
        if not tool.metadata.readOnly:
            context.mark_write_started()
        try:
            timeout = tool.metadata.timeoutMs
            async with asyncio.timeout(timeout / 1000 if timeout and timeout > 0 else 30):
                data = await mcp.call_tool(tool.name, arguments, context.token)
            if not tool.metadata.readOnly:
                try:
                    validate_write_result(tool.name, arguments, data)
                except ValueError as exc:
                    await context.send(ToolResultEvent(data=ToolResultData(
                        call_id=call_id, status="error", code=ErrorCode.MCP_RESULT_INVALID.name)))
                    raise BusinessException(ErrorCode.MCP_RESULT_INVALID) from exc
        except (McpClientError, TimeoutError) as exc:
            await context.send(ToolResultEvent(data=ToolResultData(call_id=call_id, status="error",
                                                                  code=getattr(exc, "code", "TOOL_TIMEOUT"))))
            if isinstance(exc, McpClientError) and exc.code == "AUTH_FAILED":
                raise BusinessException(ErrorCode.MCP_AUTH_FAILED) from exc
            raise
        await context.send(ToolResultEvent(data=ToolResultData(call_id=call_id, status="success")))
        return data
