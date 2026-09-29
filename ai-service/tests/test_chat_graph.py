import json
import unittest

from app.schemas.chat.messages import TextMessage
from app.service.chat_graph import ChatGraph


class ChatGraphTests(unittest.IsolatedAsyncioTestCase):
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
                return {"role": "assistant", "tool_calls": [{
                    "id": "call-1", "function": {"name": "queryDayDetail",
                    "arguments": '{"date":"2026-09-28","userId":999}'},
                }]}

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
