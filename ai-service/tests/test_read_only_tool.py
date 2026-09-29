import unittest

from app.core.config.mcp import McpSettings
from app.helper.mcp_client import McpClientError
from app.service.read_only_tool import call_read_only_tool
from app.schemas.chat.tools import DayDetailArguments, DayDetailResult


class ReadOnlyToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_retries_retryable_failure_then_succeeds(self):
        class Mcp:
            calls = 0

            async def call_tool(self, name, arguments, token):
                self.calls += 1
                if self.calls == 1:
                    raise McpClientError("JAVA_TIMEOUT", "timeout", retryable=True)
                return {"date": arguments["date"], "todos": []}

        mcp = Mcp()
        result = await call_read_only_tool(
            mcp, "queryDayDetail", DayDetailArguments(date="2026-09-25"), "token",
            McpSettings(MCP_READ_RETRY_ATTEMPTS=2, MCP_READ_RETRY_DELAY_MS=0),
        )
        self.assertEqual(DayDetailResult(date="2026-09-25", todos=[]), result)
        self.assertEqual(2, mcp.calls)

    async def test_non_retryable_failure_is_called_once(self):
        class Mcp:
            calls = 0

            async def call_tool(self, *_):
                self.calls += 1
                raise McpClientError("BUSINESS_404", "missing")

        mcp = Mcp()
        with self.assertRaises(McpClientError):
            await call_read_only_tool(
                mcp, "queryDayDetail", DayDetailArguments(date="2026-09-25"), "token",
                McpSettings(MCP_READ_RETRY_ATTEMPTS=3, MCP_READ_RETRY_DELAY_MS=0),
            )
        self.assertEqual(1, mcp.calls)

    async def test_invalid_day_detail_result_is_rejected(self):
        class Mcp:
            calls = 0

            async def call_tool(self, *_):
                self.calls += 1
                return {"date": "2026-09-25", "todos": "invalid"}

        mcp = Mcp()
        with self.assertRaises(McpClientError) as caught:
            await call_read_only_tool(
                mcp, "queryDayDetail", DayDetailArguments(date="2026-09-25"),
                "token", McpSettings(MCP_READ_RETRY_ATTEMPTS=3,
                                     MCP_READ_RETRY_DELAY_MS=0),
            )
        self.assertEqual("INVALID_RESPONSE", caught.exception.code)
        self.assertEqual(1, mcp.calls)

    async def test_write_tool_cannot_use_retry_path(self):
        class Mcp:
            async def call_tool(self, *_):
                self.fail("write tool must not execute")

        with self.assertRaises(ValueError):
            await call_read_only_tool(Mcp(), "createTodo", {}, "token")
