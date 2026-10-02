import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from app.core.config.agent.agents import AgentConfig
from app.core.config.agent.providers import ModelConfig
from app.helper.model_client import ModelClient
from app.main import app
from app.schemas.chat import ChatRequest
from app.schemas.chat.model_stream import AssistantMessage, ModelDelta, ResultEvent
from app.service.chat import ChatService


class ChatLoggingTests(unittest.IsolatedAsyncioTestCase):
    async def test_startup_prints_documentation_url(self):
        for base_url in ("http://127.0.0.1:8001", "https://example.com/ai/"):
            with self.subTest(base_url=base_url), \
                 patch("app.main.get_settings", return_value=SimpleNamespace(
                     public_base_url=base_url)), \
                 self.assertLogs("app.main", level="INFO") as logs:
                async with app.router.lifespan_context(app):
                    pass
            self.assertEqual(
                [f"接口文档地址：{base_url.rstrip('/')}/docs"],
                [record.getMessage() for record in logs.records],
            )

    async def test_chat_logs_previews_and_actual_provider_without_changing_content(self):
        for streaming in (False, True):
            for truncated in (False, True):
                with self.subTest(streaming=streaming, truncated=truncated):
                    user_text = "你\n" + "好" * 18 + ("隐藏输入" if truncated else "")
                    answer = "答\n" + "复" * 48 + ("隐藏回复" if truncated else "")
                    repository = SimpleNamespace(
                        recent_messages=AsyncMock(return_value=[]),
                        save_round=AsyncMock(),
                    )
                    # 显式 model 的厂商优先于 AgentConfig.provider 默认值 deepseek。
                    model = ModelClient(AgentConfig(model=ModelConfig(
                        "aliyun", "test-model", "https://example.com", "secret-key",
                    )))
                    model.chat = AsyncMock(return_value=AssistantMessage(content=answer))

                    async def stream_chat(*args, **kwargs):
                        yield ModelDelta(content=answer[:10])
                        yield ModelDelta(content=answer[10:])

                    model.stream_chat = stream_chat
                    request = ChatRequest(sessionId="logging-test", message=user_text)
                    with patch("app.service.chat.ChatRepository", return_value=repository), \
                         patch("app.service.chat_graph.ModelClient", return_value=model), \
                         self.assertLogs("app.service", level="INFO") as logs:
                        if streaming:
                            events = [event async for event in ChatService().stream_reply(
                                23, request, "secret-token", AsyncMock(return_value=False),
                            )]
                            result = next(event.data for event in events
                                          if isinstance(event, ResultEvent))
                        else:
                            result = await ChatService().reply(23, request, "secret-token")

                    suffix = "…" if truncated else ""
                    self.assertEqual([
                        "对话完成 | 用户输入：你 " + "好" * 18 + suffix
                        + " | 命中agent：chatAgent(aliyun)"
                        + " | 模型回复：答 " + "复" * 48 + suffix,
                    ], [record.getMessage() for record in logs.records])
                    self.assertEqual(answer, result.aiResult)
                    repository.save_round.assert_awaited_once()
                    self.assertEqual((user_text, answer),
                                     repository.save_round.call_args.args[2:4])

    async def test_failed_chat_does_not_log_a_completed_reply(self):
        for streaming in (False, True):
            with self.subTest(streaming=streaming):
                repository = SimpleNamespace(
                    recent_messages=AsyncMock(return_value=[]), save_round=AsyncMock(),
                )
                model = ModelClient()
                model.chat = AsyncMock(side_effect=RuntimeError("model unavailable"))

                async def broken_stream(*args, **kwargs):
                    yield ModelDelta(content="不完整回复")
                    raise RuntimeError("model unavailable")

                model.stream_chat = broken_stream
                with patch("app.service.chat.ChatRepository", return_value=repository), \
                     patch("app.service.chat_graph.ModelClient", return_value=model), \
                     patch("app.service.chat.logger.info") as log_info, \
                     self.assertRaisesRegex(RuntimeError, "model unavailable"):
                    request = ChatRequest(message="你好")
                    if streaming:
                        async for _ in ChatService().stream_reply(
                            23, request, "", AsyncMock(return_value=False),
                        ):
                            pass
                    else:
                        await ChatService().reply(23, request)
                log_info.assert_not_called()
                repository.save_round.assert_not_awaited()
