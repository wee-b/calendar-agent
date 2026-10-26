import asyncio
import unittest
from unittest.mock import patch

import httpx

from app.core.config.agent.agents import PlanAgentConfig, ImageAgentConfig, EmbeddingConfig
from app.core.config.agent.providers import ModelConfig
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException, EmbeddingError
from app.helper.embedding_client import EmbeddingClient
from app.helper.image_client import ImageClient
from app.helper.model_client import ModelClient


class ModelTimeoutLoggingTests(unittest.IsolatedAsyncioTestCase):
    async def invoke(self, operation, handler):
        # 实际 model.provider 与 AgentConfig 默认 provider 不同，日志应以实际模型为准。
        model = ModelConfig("test-provider", "test-model", "https://model.example/v1",
                            "hidden-api-key", "image-generation" if operation == "generate_image"
                            else "openai-compatible")
        real_client = httpx.AsyncClient
        transport = httpx.MockTransport(handler)
        if operation in {"chat", "stream_chat"}:
            config = PlanAgentConfig(_env_file=None, model=model, timeout_seconds=7)
            client = ModelClient(config)
            with patch("app.helper.model_client.httpx.AsyncClient",
                       side_effect=lambda **kwargs: real_client(transport=transport, **kwargs)):
                messages = [{"role": "user", "content": "hidden-user-prompt"}]
                if operation == "chat":
                    return await client.chat(messages)
                return [delta async for delta in client.stream_chat(messages)]
        async with real_client(transport=transport) as client:
            if operation == "generate_image":
                config = ImageAgentConfig(_env_file=None, model=model, timeout_seconds=7)
                return await ImageClient(config=config, client=client).generate("{}", "hidden-user-prompt")
            config = EmbeddingConfig(_env_file=None, model=model, timeout_seconds=7)
            return await EmbeddingClient(config=config, client=client).embed("hidden-user-prompt")

    def check_record(self, logs, operation, error_type, event="模型调用超时"):
        self.assertEqual(1, len(logs.records))
        record = logs.records[0]
        self.assertEqual("test-provider", record.model_provider)
        self.assertEqual("test-model", record.model_name)
        self.assertEqual(operation, record.model_operation)
        self.assertEqual(7, record.model_timeout_seconds)
        self.assertGreaterEqual(record.model_elapsed_seconds, 0)
        self.assertEqual(error_type, record.model_error_type)
        text = record.getMessage()
        for expected in (event, "厂商=test-provider", "模型=test-model", "超时配置=7.00秒", "耗时="):
            self.assertIn(expected, text)
        for secret in ("hidden-api-key", "hidden-user-prompt", "hidden-error-detail"):
            self.assertNotIn(secret, text)
        self.assertIsNone(record.exc_info)

    async def test_chat_and_stream_timeout_logs_actual_model_and_preserves_error_code(self):
        for operation in ("chat", "stream_chat"):
            for error_type in (httpx.ConnectTimeout, httpx.ReadTimeout, httpx.WriteTimeout, httpx.PoolTimeout):
                with self.subTest(operation=operation, error=error_type.__name__):
                    def fail(request):
                        raise error_type("hidden-error-detail", request=request)

                    with self.assertLogs("app.model", level="WARNING") as logs:
                        with self.assertRaises(BusinessException) as caught:
                            await self.invoke(operation, fail)
                    self.assertEqual(ErrorCode.MODEL_TIMEOUT.code, caught.exception.code)
                    self.check_record(logs, operation, error_type.__name__)
                    self.assertIn("配置=PlanAgentConfig", logs.records[0].getMessage())

    async def test_timeout_while_reading_stream_logs_model_after_partial_output(self):
        class InterruptedStream(httpx.AsyncByteStream):
            async def __aiter__(self):
                yield b'data: {"choices":[{"delta":{"content":"hello"}}]}\n\n'
                raise httpx.ReadTimeout("hidden-error-detail")

        with self.assertLogs("app.model", level="WARNING") as logs:
            with self.assertRaises(BusinessException) as caught:
                await self.invoke("stream_chat", lambda _: httpx.Response(200, stream=InterruptedStream()))
        self.assertEqual(ErrorCode.MODEL_TIMEOUT.code, caught.exception.code)
        self.check_record(logs, "stream_chat", "ReadTimeout")

    async def test_image_and_embedding_timeout_keep_existing_errors(self):
        def fail(request):
            raise httpx.ReadTimeout("hidden-error-detail", request=request)

        for operation, exception_type in (("generate_image", BusinessException), ("embed", EmbeddingError)):
            with self.subTest(operation=operation), self.assertLogs("app.model", level="WARNING") as logs:
                with self.assertRaises(exception_type) as caught:
                    await self.invoke(operation, fail)
            if operation == "generate_image":
                self.assertEqual(ErrorCode.MODEL_TIMEOUT.code, caught.exception.code)
            else:
                self.assertEqual("Embedding 请求或响应异常", str(caught.exception))
            self.check_record(logs, operation, "ReadTimeout")

    async def test_outer_request_timeout_records_active_model_and_preserves_cancellation(self):
        async def wait_forever(_request):
            await asyncio.Future()

        for operation in ("chat", "stream_chat", "generate_image", "embed"):
            with self.subTest(operation=operation), self.assertLogs("app.model", level="WARNING") as logs:
                with self.assertRaises(TimeoutError):
                    async with asyncio.timeout(0.01):
                        await self.invoke(operation, wait_forever)
            self.check_record(logs, operation, "CancelledError", "模型调用已取消")
            self.assertNotIn("模型调用超时", logs.records[0].getMessage())

    async def test_explicit_cancellation_is_not_reported_as_upstream_timeout(self):
        entered = asyncio.Event()

        async def wait_forever(_request):
            entered.set()
            await asyncio.Future()

        with self.assertLogs("app.model", level="WARNING") as logs:
            task = asyncio.create_task(self.invoke("chat", wait_forever))
            await entered.wait()
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
        self.check_record(logs, "chat", "CancelledError", "模型调用已取消")

    async def test_successful_calls_do_not_log_interruption(self):
        with self.assertNoLogs("app.model", level="WARNING"):
            response = await self.invoke("chat", lambda _: httpx.Response(
                200, json={"choices": [{"message": {"role": "assistant", "content": "ok"}}]}))
        self.assertEqual("ok", response.content)
