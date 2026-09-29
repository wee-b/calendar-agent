"""只读 MCP 调用的有限重试；写工具不进入这条路径。"""

import asyncio
from pydantic import ValidationError

from app.core.config.mcp import McpSettings, get_mcp_settings
from app.helper.mcp_client import JavaMcpClient, McpClientError
from app.schemas.chat.tools import DAY_DETAIL_TOOL_NAME, DayDetailArguments, DayDetailResult


READ_ONLY_TOOLS = frozenset({DAY_DETAIL_TOOL_NAME})


async def call_read_only_tool(
    mcp: JavaMcpClient, name: str, arguments: DayDetailArguments, token: str,
    settings: McpSettings | None = None,
) -> DayDetailResult:
    """只对 Java 标记为可重试的瞬态错误重试，并校验日期详情结构。"""

    if name not in READ_ONLY_TOOLS:
        raise ValueError(f"不是允许自动重试的只读工具: {name}")
    policy = settings or get_mcp_settings()
    for attempt in range(policy.read_retry_attempts):
        try:
            raw = await mcp.call_tool(name, arguments.model_dump(), token)
            try:
                return DayDetailResult.model_validate(raw)
            except ValidationError as exc:
                raise McpClientError("INVALID_RESPONSE", "Java 日期详情格式异常") from exc
        except McpClientError as exc:
            if not exc.retryable or attempt == policy.read_retry_attempts - 1:
                raise
            await asyncio.sleep(policy.read_retry_delay_ms / 1000)
    raise AssertionError("重试循环应已返回或抛出异常")
