"""只读 MCP 调用的有限重试；写工具不进入这条路径。"""

import asyncio
from pydantic import ValidationError

from app.core.config.common.mcp import McpSettings, get_mcp_settings
from app.core.flow_logging import log_turn_step
from app.helper.mcp_client import JavaMcpClient, McpClientError
from app.schemas.chat.tools import DAY_DETAIL_TOOL_NAME, DayDetailArguments, DayDetailResult
from app.schemas.plan.planning_tools import (
    QUERY_TODO_LIST_TOOL_NAME, QueryTodoListArguments, TodoListResult,
    QUERY_SCHEDULE_RANGE_TOOL_NAME, ScheduleRangeArguments, ScheduleRangeResult,
)


READ_ONLY_TOOLS = frozenset({DAY_DETAIL_TOOL_NAME, QUERY_TODO_LIST_TOOL_NAME, QUERY_SCHEDULE_RANGE_TOOL_NAME})


async def call_read_only_tool(
    mcp: JavaMcpClient, name: str,
    arguments: DayDetailArguments | QueryTodoListArguments | ScheduleRangeArguments, token: str,
    settings: McpSettings | None = None,
) -> DayDetailResult | TodoListResult | ScheduleRangeResult:
    """只对 Java 标记为可重试的瞬态错误重试，并校验只读查询结果。"""

    if name not in READ_ONLY_TOOLS:
        raise ValueError(f"不是允许自动重试的只读工具: {name}")
    policy = settings or get_mcp_settings()
    for attempt in range(policy.read_retry_attempts):
        try:
            raw = await mcp.call_tool(name, arguments.model_dump(mode="json"), token)
            try:
                if name == QUERY_SCHEDULE_RANGE_TOOL_NAME:
                    result = ScheduleRangeResult.model_validate(raw)
                    if (str(result.startDate), str(result.endDate)) != (str(arguments.startDate), str(arguments.endDate)):
                        raise ValueError("返回的区间与查询参数不一致")
                    return result
                if name == QUERY_TODO_LIST_TOOL_NAME:
                    return TodoListResult.model_validate(raw)
                return DayDetailResult.model_validate(raw)
            except ValueError as exc:
                raise McpClientError("INVALID_RESPONSE", "Java 只读查询结果格式异常") from exc
        except McpClientError as exc:
            if not exc.retryable or attempt == policy.read_retry_attempts - 1:
                raise
            log_turn_step("READ_TOOL_RETRY", 工具=name, 下一次尝试=attempt + 2, 错误码=exc.code)
            await asyncio.sleep(policy.read_retry_delay_ms / 1000)
    raise AssertionError("重试循环应已返回或抛出异常")
