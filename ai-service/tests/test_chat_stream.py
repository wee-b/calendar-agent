import asyncio
import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import httpx
from fastapi import HTTPException

from app.helper.mcp_client import McpClientError
from app.helper.model_client import ModelClient
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException
from app.main import app
from app.schemas.chat import ChatRequest
from app.schemas.chat.model_stream import (
    AssistantDeltaData, AssistantDeltaEvent, FinalData, FinalEvent, ModelDelta,
)
from app.service.chat import ChatService, _events_with_ping
from app.service.model_stream import StreamResponseAccumulator


class ModelStreamTests(unittest.IsolatedAsyncioTestCase):
    async def test_idle_graph_emits_ping_without_canceling_graph(self):
        async def slow_events():
            await asyncio.sleep(0.025)
            yield AssistantDeltaEvent(data=AssistantDeltaData(round=1, delta="好"))

        events = [event async for event in _events_with_ping(slow_events(), 0.005)]
        self.assertIn("ping", [event.event for event in events])
        self.assertEqual("assistant_delta", events[-1].event)

    async def test_openai_sse_deltas_and_done(self):
        body = (
            'data: {"choices":[{"delta":{"content":"你"}}]}\n\n'
            'data: {"choices":[{"delta":{"content":"好"}}]}\n\n'
            'data: [DONE]\n\n'
        )
        real_client = httpx.AsyncClient

        def handle(request):
            payload = json.loads(request.content)
            self.assertTrue(payload["stream"])
            self.assertEqual("deepseek-flash", payload["model"])
            return httpx.Response(200, text=body,
                                  headers={"content-type": "text/event-stream"})

        settings = SimpleNamespace(
            chat_provider="deepseek", deepseek_base_url="https://api.deepseek.com",
            deepseek_model_name="deepseek-flash", model_request_timeout=5,
        )
        secrets = SimpleNamespace(deepseek_api_key="secret")
        with patch("app.helper.model_client.get_settings", return_value=settings), \
             patch("app.helper.model_client.get_secret_settings", return_value=secrets), \
             patch("app.helper.model_client.httpx.AsyncClient",
                   side_effect=lambda **kwargs: real_client(
                       transport=httpx.MockTransport(handle), **kwargs)):
            deltas = [delta async for delta in ModelClient().stream_chat(
                [{"role": "user", "content": "hi"}])]
        self.assertEqual(["你", "好"], [delta.content for delta in deltas])

    async def test_tool_fragments_are_assembled_by_index(self):
        events = []
        accumulator = StreamResponseAccumulator(1, events.append)
        accumulator.add(ModelDelta.model_validate({
            "tool_calls": [{"index": 0, "id": "call-1",
                            "function": {"name": "query", "arguments": ""}}],
        }))
        accumulator.add(ModelDelta.model_validate({
            "tool_calls": [{"index": 0,
                            "function": {"name": "DayDetail",
                                         "arguments": '{"date":"2026-'}}],
        }))
        accumulator.add(ModelDelta.model_validate({
            "tool_calls": [{"index": 0,
                            "function": {"arguments": '09-28"}'}}],
        }))
        message = accumulator.finish()
        self.assertEqual("queryDayDetail", message.tool_calls[0].function.name)
        self.assertEqual({"date": "2026-09-28"}, json.loads(
            message.tool_calls[0].function.arguments))
        self.assertEqual("tool_call_start", events[0].event)
        self.assertEqual("tool_call_end", events[-1].event)
        self.assertTrue(any(event.event == "tool_call_delta" for event in events))
        self.assertNotIn("arguments", json.dumps([
            event.model_dump(mode="json", by_alias=True) for event in events
        ]))

    async def test_invalid_model_delta_keeps_specific_error_code(self):
        for delta, expected in (
            ({"content": 123}, ErrorCode.MODEL_TEXT_DELTA_INVALID),
            ({"tool_calls": [{"index": "0"}]}, ErrorCode.MODEL_TOOL_DELTA_INVALID),
        ):
            with self.subTest(delta=delta), self.assertRaises(BusinessException) as caught:
                ModelClient._parse_delta(json.dumps({"choices": [{"delta": delta}]}))
            self.assertEqual(expected.code, caught.exception.code)


class ChatStreamEndpointTests(unittest.IsolatedAsyncioTestCase):
    async def test_streams_read_tool_and_saves_complete_round_once(self):
        class Verifier:
            async def verify(self, token):
                assert token == "valid"
                return 23

        class Repository:
            saved = []

            async def recent_messages(self, user_id, session_id):
                return []

            async def save_round(self, *args):
                Repository.saved.append(args)

        class Model:
            calls = 0

            async def stream_chat(self, messages, tools=None):
                self.calls += 1
                if self.calls == 1:
                    yield {"tool_calls": [{"index": 0, "id": "call-1",
                                           "function": {"name": "queryDayDetail",
                                                        "arguments": '{"date":"2026-'}}]}
                    yield {"tool_calls": [{"index": 0,
                                           "function": {"arguments": '09-28"}'}}]}
                else:
                    assert messages[-1]["role"] == "tool"
                    yield {"content": "明天"}
                    yield {"content": "休息。"}

        class Mcp:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *_):
                pass

            async def call_tool(self, name, args, token):
                assert (name, args, token) == (
                    "queryDayDetail", {"date": "2026-09-28"}, "valid")
                return {"date": "2026-09-28", "todos": [], "dailyNote": "休息"}

        Repository.saved = []
        with patch("app.core.middleware.auth.RedisTokenVerifier", return_value=Verifier()), \
             patch("app.service.chat.ChatRepository", return_value=Repository()), \
             patch("app.service.chat_graph.ModelClient", return_value=Model()), \
             patch("app.service.chat_graph.JavaMcpClient", Mcp):
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as client:
                response = await client.post(
                    "/chat/stream", headers={"yvli-token": "valid"},
                    json={"sessionId": "session-1", "message": "明天有什么安排"},
                )
        self.assertEqual(200, response.status_code)
        self.assertTrue(response.headers["content-type"].startswith("text/event-stream"))
        events = [part.split("\ndata: ")[0].removeprefix("event: ")
                  for part in response.text.strip().split("\n\n")]
        self.assertIn("tool_call_start", events)
        self.assertIn("tool_call_end", events)
        self.assertIn("tool_result", events)
        self.assertEqual(2, events.count("assistant_delta"))
        self.assertEqual(["result", "done"], events[-2:])
        self.assertEqual(1, len(Repository.saved))
        self.assertEqual("明天休息。", Repository.saved[0][3])

    async def test_missing_token_is_rejected(self):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.post("/chat/stream", json={"message": "你好"})
        self.assertEqual(401, response.status_code)

    async def test_disconnect_closes_graph_without_saving(self):
        class Repository:
            saved = 0

            async def recent_messages(self, *_):
                return []

            async def save_round(self, *_):
                self.saved += 1

        class Graph:
            closed = False

            async def stream(self, *_):
                try:
                    yield AssistantDeltaEvent(data=AssistantDeltaData(round=1, delta="半句"))
                    yield FinalEvent(data=FinalData(answer="完整", rounds=1))
                finally:
                    self.closed = True

        repository = Repository()
        graph = Graph()

        async def disconnected():
            return True

        with patch("app.service.chat.ChatRepository", return_value=repository), \
             patch("app.service.chat.ChatGraph", return_value=graph):
            events = [event async for event in ChatService().stream_reply(
                23, ChatRequest(sessionId="s1", message="你好"), "token", disconnected)]
        self.assertEqual([], events)
        self.assertTrue(graph.closed)
        self.assertEqual(0, repository.saved)

    async def test_tool_failure_is_reported_without_exposing_tool_payload(self):
        class Verifier:
            async def verify(self, _):
                return 23

        class Repository:
            saved = []

            async def recent_messages(self, *_):
                return []

            async def save_round(self, *args):
                Repository.saved.append(args)

        class Model:
            calls = 0

            async def stream_chat(self, messages, tools=None):
                self.calls += 1
                if self.calls == 1:
                    yield {"tool_calls": [{"index": 0, "id": "call-1",
                                           "function": {"name": "queryDayDetail",
                                                        "arguments": '{"date":"2026-09-28"}'}}]}
                else:
                    self_test.assertEqual("JAVA_TIMEOUT", json.loads(
                        messages[-1]["content"])["error"])
                    yield {"content": "暂时查不到明天的日程。"}

        class Mcp:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *_):
                pass

            async def call_tool(self, *_):
                raise McpClientError("JAVA_TIMEOUT", "private-detail", retryable=True)

        self_test = self
        Repository.saved = []
        with patch("app.core.middleware.auth.RedisTokenVerifier", return_value=Verifier()), \
             patch("app.service.chat.ChatRepository", return_value=Repository()), \
             patch("app.service.chat_graph.ModelClient", return_value=Model()), \
             patch("app.service.chat_graph.JavaMcpClient", Mcp), \
             patch("app.service.read_only_tool.asyncio.sleep", return_value=None):
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as client:
                response = await client.post(
                    "/chat/stream", headers={"yvli-token": "valid"},
                    json={"sessionId": "s1", "message": "明天有什么安排"},
                )
        self.assertIn('"status": "error"', response.text)
        self.assertIn('"code": "JAVA_TIMEOUT"', response.text)
        self.assertNotIn("private-detail", response.text)
        self.assertEqual(1, len(Repository.saved))

    async def test_model_stream_failure_emits_error_without_saving(self):
        class Verifier:
            async def verify(self, _):
                return 23

        class Repository:
            saved = 0

            async def recent_messages(self, *_):
                return []

            async def save_round(self, *_):
                Repository.saved += 1

        class Model:
            async def stream_chat(self, messages, tools=None):
                yield {"content": "半句"}
                raise HTTPException(status_code=502, detail="模型流提前结束")

        Repository.saved = 0
        with patch("app.core.middleware.auth.RedisTokenVerifier", return_value=Verifier()), \
             patch("app.service.chat.ChatRepository", return_value=Repository()), \
             patch("app.service.chat_graph.ModelClient", return_value=Model()):
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as client:
                response = await client.post(
                    "/chat/stream", headers={"yvli-token": "valid"},
                    json={"message": "你好"},
                )
        self.assertIn("event: assistant_delta", response.text)
        self.assertIn("event: error", response.text)
        self.assertNotIn("event: done", response.text)
        self.assertEqual(0, Repository.saved)
