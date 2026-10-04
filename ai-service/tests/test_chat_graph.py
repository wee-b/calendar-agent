import asyncio
import json
import unittest
from unittest.mock import patch

from app.core.config.common.mcp import McpSettings
from app.helper.mcp_client import McpClientError
from app.schemas.chat.messages import TextMessage
from app.all_graph.chat_graph import ChatGraph
from app.schemas.chat.model_stream import ToolResultEvent


class ChatGraphTests(unittest.IsolatedAsyncioTestCase):
    async def test_read_only_calls_overlap_retry_independently_and_keep_model_order(self):
        both_started = asyncio.Event()
        finished = []

        class Model:
            calls = 0

            async def chat(self, messages, tools=None):
                self.calls += 1
                if self.calls == 1:
                    return {"role": "assistant", "tool_calls": [
                        {"id": f"call-{i}", "function": {"name": "queryDayDetail",
                         "arguments": json.dumps({"date": f"2026-09-{i + 27}"})}}
                        for i in (1, 2)
                    ]}
                tool_messages = [item for item in messages if item["role"] == "tool"]
                self_test.assertEqual(["call-1", "call-2"],
                                      [item["tool_call_id"] for item in tool_messages])
                self_test.assertEqual(["2026-09-28", "2026-09-29"],
                                      [json.loads(item["content"])["date"] for item in tool_messages])
                return {"role": "assistant", "content": "两天的日程已查到。"}

        class Mcp:
            started = 0
            attempts = {}

            async def __aenter__(self):
                return self

            async def __aexit__(self, *_):
                pass

            async def call_tool(self, name, args, token):
                day = args["date"]
                self.attempts[day] = self.attempts.get(day, 0) + 1
                self.started += 1
                if self.started == 2:
                    both_started.set()
                await both_started.wait()
                if day == "2026-09-28" and self.attempts[day] == 1:
                    raise McpClientError("JAVA_TIMEOUT", "timeout", retryable=True)
                if day == "2026-09-28":
                    await asyncio.sleep(0.02)
                finished.append(day)
                return {"date": day, "todos": []}

        self_test = self
        mcp = Mcp()
        with patch("app.service.read_only_tool.get_mcp_settings", return_value=McpSettings(
            MCP_READ_RETRY_ATTEMPTS=2, MCP_READ_RETRY_DELAY_MS=0)):
            answer = await asyncio.wait_for(ChatGraph(Model(), lambda: mcp).run(
                [TextMessage(role="user", content="查两天的日程")], "token"), timeout=1)
        self.assertEqual("两天的日程已查到。", answer)
        self.assertEqual(["2026-09-29", "2026-09-28"], finished)
        self.assertEqual({"2026-09-28": 2, "2026-09-29": 1}, mcp.attempts)

    async def test_stream_exposes_short_tool_result_summary(self):
        class Model:
            def __init__(self):
                self.calls = 0

            async def stream_chat(self, messages, tools=None):
                self.calls += 1
                if self.calls == 1:
                    yield {"tool_calls": [{"index": 0, "id": "call-1", "function": {
                        "name": "queryDayDetail", "arguments": '{"date":"2026-09-28"}'}}]}
                else:
                    yield {"content": "当天有一项待办。"}

        class Mcp:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *_):
                pass

            async def call_tool(self, *_):
                return {"date": "2026-09-28", "todos": [{"todoId": 1, "title": "私密标题"}],
                        "dailyNote": "私密日记"}

        events = [event async for event in ChatGraph(Model(), Mcp).stream(
            [TextMessage(role="user", content="查询日程")], "token")]
        results = [event.data for event in events if isinstance(event, ToolResultEvent)]
        self.assertEqual("2026-09-28：1 项待办，有日记。", results[0].summary)
        self.assertNotIn("私密", str(results[0]))

    async def test_read_only_tool_result_is_returned_to_model(self):
        class Model:
            def __init__(self):
                self.calls = 0

            async def chat(self, messages, tools=None):
                self.calls += 1
                if self.calls == 1:
                    self_test.assertEqual("queryDayDetail", tools[0]["function"]["name"])
                    return {"role": "assistant", "content": None, "tool_calls": [{
                        "id": "call-1", "type": "function", "function": {
                            "name": "queryDayDetail",
                            "arguments": json.dumps({"date": "2026-09-28"}),
                        },
                    }]}
                self_test.assertEqual("tool", messages[-1]["role"])
                self_test.assertEqual("call-1", messages[-1]["tool_call_id"])
                self_test.assertEqual("休息", json.loads(messages[-1]["content"])["dailyNote"])
                return {"role": "assistant", "content": "明天没有待办，日记是休息。"}

        class Mcp:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *_):
                pass

            async def call_tool(self, name, args, token):
                self_test.assertEqual(("queryDayDetail", {"date": "2026-09-28"}, "token"),
                                      (name, args, token))
                return {"date": "2026-09-28", "dailyNote": "休息", "todos": []}

        self_test = self
        answer = await ChatGraph(Model(), Mcp).run(
            [TextMessage(role="user", content="明天有什么安排")], "token"
        )
        self.assertEqual("明天没有待办，日记是休息。", answer)

    async def test_unapproved_tool_is_rejected(self):
        class Model:
            async def chat(self, messages, tools=None):
                return {"role": "assistant", "content": None, "tool_calls": [{
                    "id": "call-1", "function": {"name": "createTodo", "arguments": "{}"}
                }]}

        with self.assertRaisesRegex(Exception, "未开放的工具"):
            await ChatGraph(Model()).run(
                [TextMessage(role="user", content="新增待办")], "token")

    async def test_tool_arguments_reject_unexpected_fields_before_mcp_call(self):
        class Model:
            async def chat(self, messages, tools=None):
                return {"role": "assistant", "tool_calls": [
                    {"id": "call-1", "function": {"name": "queryDayDetail",
                     "arguments": '{"date":"2026-09-28"}'}},
                    {"id": "call-2", "function": {"name": "queryDayDetail",
                     "arguments": '{"date":"2026-09-29","userId":999}'}},
                ]}

        class Mcp:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *_):
                pass

            async def call_tool(self, *_):
                raise AssertionError("invalid arguments must not reach Java")

        with self.assertRaisesRegex(Exception, "模型工具参数格式异常"):
            await ChatGraph(Model(), Mcp).run(
                [TextMessage(role="user", content="明天有什么安排")], "token")
