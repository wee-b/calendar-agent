import asyncio
import json
import unittest
from contextlib import contextmanager
from unittest.mock import patch

import httpx

from app.core.config.agent.agents import PlanAgentConfig, ImageAgentConfig, EmbeddingConfig
from app.core.config.agent.providers import ModelConfig
from app.core.exception.exceptions import BusinessException
from app.core.flow_logging import bind_flow_trace, log_turn_step
from app.helper.embedding_client import EmbeddingClient
from app.helper.image_client import ImageClient
from app.helper.model_client import ModelClient


@contextmanager
def mock_http(handler):
    real_client = httpx.AsyncClient
    with patch("app.helper.model_client.httpx.AsyncClient",
               side_effect=lambda **kwargs: real_client(transport=httpx.MockTransport(handler), **kwargs)):
        yield real_client


def config(config_type=PlanAgentConfig):
    return config_type(_env_file=None, timeout_seconds=7,
        model=ModelConfig("actual-provider", "actual-model", "https://model.example/v1", "hidden-api-key",
                          "image-generation" if config_type is ImageAgentConfig else "openai-compatible"))


class ModelTelemetryTests(unittest.IsolatedAsyncioTestCase):
    async def call(self, streaming=False):
        client = ModelClient(config())
        messages = [{"role": "user", "content": "hidden-user-prompt"}]
        if streaming:
            return [delta async for delta in client.stream_chat(messages)]
        return await client.complete(messages)

    def events(self, logs, terminal="success"):
        self.assertEqual(["start", terminal], [r.model_event for r in logs.records])
        start, finish = logs.records
        self.assertEqual(start.model_call_id, finish.model_call_id)
        self.assertEqual("actual-provider", finish.model_provider)
        self.assertEqual("actual-model", finish.model_name)
        self.assertGreaterEqual(finish.model_elapsed_ms, 0)
        self.assertIsNone(finish.exc_info)
        for private in ("hidden-api-key", "hidden-user-prompt", "hidden-output", "hidden-error-detail"):
            self.assertNotIn(private, "\n".join(logs.output))
        return finish

    async def test_complete_logs_one_call_with_latency_http_status_and_usage(self):
        requests = []

        def respond(request):
            requests.append(request)
            return httpx.Response(200, json={"choices": [{"message": {"content": "hidden-output"}}],
                "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20,
                          "untrusted_extra": "hidden-user-prompt"}})

        with mock_http(respond), self.assertLogs("app.model", "INFO") as logs:
            self.assertEqual("hidden-output", await self.call())
        result = self.events(logs)
        self.assertEqual(1, len(requests))  # complete() 包装 chat()，只计一次 API。
        self.assertEqual(200, result.model_http_status)
        self.assertLessEqual(result.model_response_ms, result.model_elapsed_ms)
        self.assertIsNone(result.model_first_output_ms)
        self.assertEqual({"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}, result.model_usage)

    async def test_stream_records_first_meaningful_delta_and_only_succeeds_after_done(self):
        body = ('data: {"choices":[{"delta":{"role":"assistant","content":""}}]}\n\n'
                'data: {"choices":[{"delta":{"content":"hello"}}]}\n\n'
                'data: {"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}\n\n'
                'data: [DONE]\n\n')
        with mock_http(lambda _: httpx.Response(200, text=body)), \
             patch("app.core.model_logging.perf_counter", side_effect=[10, 10.1, 10.4, 11]), \
             self.assertLogs("app.model", "INFO") as logs:
            generator = ModelClient(config()).stream_chat([{"role": "user", "content": "hidden-user-prompt"}])
            self.assertEqual("", (await anext(generator)).content)
            self.assertEqual("hello", (await anext(generator)).content)
            self.assertEqual(["start"], [r.model_event for r in logs.records])
            self.assertEqual([], [delta async for delta in generator])
        result = self.events(logs)
        self.assertAlmostEqual(100, result.model_response_ms)
        self.assertAlmostEqual(400, result.model_first_output_ms)
        self.assertAlmostEqual(1000, result.model_elapsed_ms)
        self.assertEqual(11, result.model_usage["total_tokens"])

    async def test_http_network_protocol_and_truncated_streams_have_one_failure_event(self):
        def offline(_request):
            raise httpx.ConnectError("hidden-error-detail")

        cases = [
            (False, lambda _: httpx.Response(503, text="hidden-error-detail"), 503),
            (False, offline, None),
            (False, lambda _: httpx.Response(200, text="invalid json"), 200),
            (False, lambda _: httpx.Response(200, json={"choices": [{"message": {"content": ""}}]}), 200),
            (True, lambda _: httpx.Response(200, text='data: {"choices":[{"delta":{"content":"hello"}}]}\n\n'), 200),
            (True, lambda _: httpx.Response(200, text="data: invalid json\n\n"), 200),
        ]
        for streaming, handler, status in cases:
            with self.subTest(streaming=streaming, handler=handler), mock_http(handler), \
                 self.assertLogs("app.model", "INFO") as logs:
                with self.assertRaises(BusinessException):
                    await self.call(streaming)
            result = self.events(logs, "error")
            self.assertEqual(status, result.model_http_status)
            self.assertIsNotNone(result.model_error_type)
            self.assertIsNotNone(result.model_error_code)

    async def test_timeout_and_cancellation_are_counted_separately(self):
        for error, terminal, expected in ((httpx.ReadTimeout("hidden-error-detail"), "timeout", BusinessException),
                                          (asyncio.CancelledError(), "cancelled", asyncio.CancelledError)):
            def interrupt(_request):
                raise error

            with self.subTest(terminal=terminal), mock_http(interrupt), self.assertLogs("app.model", "INFO") as logs:
                with self.assertRaises(expected):
                    await self.call()
            self.events(logs, terminal)

    async def test_early_stream_close_is_cancelled_and_never_success(self):
        body = 'data: {"choices":[{"delta":{"content":"hello"}}]}\n\ndata: [DONE]\n\n'
        with mock_http(lambda _: httpx.Response(200, text=body)), self.assertLogs("app.model", "INFO") as logs:
            generator = ModelClient(config()).stream_chat([{"role": "user", "content": "hidden-user-prompt"}])
            await anext(generator)
            await generator.aclose()
        result = self.events(logs, "cancelled")
        self.assertIsNotNone(result.model_first_output_ms)

    async def test_image_and_embedding_success_use_the_same_events(self):
        for operation in ("image", "embedding"):
            data = ({"data": [{"url": "https://image.example/hidden-output"}], "usage": {"output_tokens": 4}}
                    if operation == "image" else {"data": [{"embedding": [0.1, 0.2]}],
                                                  "usage": {"input_tokens": 6, "total_tokens": 6}})
            async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, json=data))) as client:
                with self.subTest(operation=operation), self.assertLogs("app.model", "INFO") as logs:
                    if operation == "image":
                        await ImageClient(config(ImageAgentConfig), client).generate("{}", "hidden-user-prompt")
                    else:
                        await EmbeddingClient(client, config(EmbeddingConfig)).embed("hidden-user-prompt")
            result = self.events(logs)
            self.assertEqual(200, result.model_http_status)
            self.assertTrue(result.model_usage)

    async def test_invalid_usage_is_ignored_and_cannot_break_a_successful_response(self):
        body = {"choices": [{"message": {"content": "ok"}}],
                "usage": {"prompt_tokens": True, "completion_tokens": -1, "total_tokens": "20"}}
        with mock_http(lambda _: httpx.Response(200, json=body)), self.assertLogs("app.model", "INFO") as logs:
            self.assertEqual("ok", await self.call())
        self.assertEqual({}, self.events(logs).model_usage)

    async def test_concurrent_turns_keep_their_own_call_ids_and_aggregate_stats(self):
        async def respond(_request):
            await asyncio.sleep(0)
            return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}]})

        async def run(session):
            with bind_flow_trace(7, session) as trace:
                log_turn_step("ROUTE")
                await self.call()
                return trace

        with mock_http(respond), self.assertLogs("app", "INFO") as logs:
            traces = await asyncio.gather(run("session-a"), run("session-b"))
        self.assertNotEqual(traces[0].turn_id, traces[1].turn_id)
        for trace in traces:
            calls = [r for r in logs.records if getattr(r, "model_event", None)
                     and r.session_id == trace.session_id]
            self.assertEqual(["start", "success"], [r.model_event for r in calls])
            self.assertEqual({trace.turn_id}, {r.turn_id for r in calls})
            self.assertEqual("ROUTE", calls[1].flow_step)
            self.assertEqual([calls[1].model_call_id], [call["call_id"] for call in trace.model_calls])
            turn = next(r for r in logs.records if getattr(r, "flow_scope", None) == "单轮流转"
                        and r.session_id == trace.session_id)
            stats = turn.flow_details["模型API统计"]
            self.assertEqual((1, 1, 0, 0, 0), tuple(stats[key] for key in ("count", "success", "timeout", "error", "cancelled")))
            self.assertIn("模型API=1次", turn.getMessage())

    async def test_turn_totals_include_success_timeout_error_and_cancelled_once_each(self):
        outcomes = [httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}]}),
                    httpx.ReadTimeout("hidden-error-detail"), httpx.Response(503), asyncio.CancelledError()]

        def respond(_request):
            outcome = outcomes.pop(0)
            if isinstance(outcome, BaseException):
                raise outcome
            return outcome

        with mock_http(respond), self.assertLogs("app", "INFO") as logs:
            with bind_flow_trace(7, "s"):
                for _ in range(4):
                    try:
                        await self.call()
                    except (BusinessException, asyncio.CancelledError):
                        pass
        turn = next(r for r in logs.records if getattr(r, "flow_scope", None) == "单轮流转")
        stats = turn.flow_details["模型API统计"]
        self.assertEqual((4, 1, 1, 1, 1), tuple(stats[key] for key in ("count", "success", "timeout", "error", "cancelled")))
        self.assertEqual(stats["elapsed_ms"], round(sum(call["elapsed_ms"] for call in turn.flow_details["模型API调用"]), 2))
