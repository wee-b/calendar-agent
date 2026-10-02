import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import httpx

from app.core.config.agent import providers as vendor
from app.core.config.agent.agents import (
    AgentConfig, ChatAgentConfig, RouteAgentConfig, PlanAgentConfig,
    ExecutorAgentConfig, SummaryAgentConfig, ImageAgentConfig, EmbeddingConfig,
)
from app.core.config.agent.providers import SecretSettings, DeepseekConfig, AliyunConfig, ArkConfig
from app.core.config.common.settings import Settings, AI_SERVICE_DIR
from app.core.config.common.qdrant import QdrantSettings
from app.core.exception.exceptions import BusinessException
from app.helper.model_client import ModelClient


class ModelConfigTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        # 配置测试仅使用替身密钥和默认连接，不读取本机密钥。
        self.env = patch.dict(os.environ, {}, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        self.secret_patch = patch.object(vendor, "secrets", SecretSettings(
            _env_file=None, deepseek_api_key="ds-key", aliyun_api_key="ali-key", ark_api_key="ark-key"))
        self.secret_patch.start()
        self.addCleanup(self.secret_patch.stop)
        self.factories = {
            "deepseek": DeepseekConfig(_env_file=None),
            "aliyun": AliyunConfig(_env_file=None),
            "ark": ArkConfig(_env_file=None),
        }
        self.registry_patch = patch.dict(vendor.providers, self.factories)
        self.registry_patch.start()
        self.addCleanup(self.registry_patch.stop)

    def test_java_defaults_and_public_env_select_expected_models(self):
        for config_type, provider, name, temperature in (
            (ChatAgentConfig, "deepseek", "deepseek-flash", 0.3),
            (RouteAgentConfig, "aliyun", "qwen3.7-plus", 0.1),
            (PlanAgentConfig, "deepseek", "deepseek-flash", 0.1),
            (ExecutorAgentConfig, "aliyun", "qwen3.7-plus", 0.3),
            (SummaryAgentConfig, "aliyun", "qwen3.7-plus", 0.1),
        ):
            for config in (config_type(_env_file=None), config_type()):
                self.assertEqual((provider, name, temperature),
                                 (config.model.provider, config.model.name, config.temperature))
        image = ImageAgentConfig(_env_file=None)
        self.assertEqual("doubao-seedream-5-0-flash-260915", image.model.name)
        self.assertEqual("2K", image.size)
        self.assertTrue(image.watermark)
        with self.assertRaises(BusinessException):
            ModelClient(image)._request_options()

    def test_public_and_secret_files_are_isolated(self):
        self.assertEqual(Path(__file__).resolve().parents[1], AI_SERVICE_DIR)
        with tempfile.TemporaryDirectory() as directory:
            public = Path(directory, ".env")
            secret = Path(directory, ".env.prod")
            public.write_text("CHAT_PROVIDER=aliyun\nALIYUN_API_KEY=wrong\n"
                              "RAG_EMBEDDING_MODEL=remote-model\n"
                              "REDIS_URL=redis://localhost:6379/15\n"
                              "DATABASE_URL=mysql+aiomysql://localhost/test\n", encoding="utf-8")
            secret.write_text("ALIYUN_API_KEY=correct\n", encoding="utf-8")
            settings = Settings(_env_file=public)
            self.assertEqual("aliyun", ChatAgentConfig(_env_file=public).model.provider)
            self.assertEqual("remote-model", EmbeddingConfig(_env_file=public).model.name)
            self.assertEqual("redis://localhost:6379/15", settings.redis_url)
            self.assertFalse(hasattr(settings, "aliyun_api_key"))
            self.assertFalse(hasattr(AliyunConfig(_env_file=public), "api_key"))
            self.assertEqual("correct", SecretSettings(_env_file=secret).aliyun_api_key)
            self.assertEqual("rag_corpus", QdrantSettings(_env_file=public).qdrant_collection)

    def test_agent_overrides_are_independent_and_explicit_model_wins(self):
        with patch.dict(os.environ, {
            "ROUTE_PROVIDER": "deepseek", "ROUTE_MODEL_NAME": "route-special",
            "ROUTE_TEMPERATURE": "0.2", "ROUTE_REQUEST_TIMEOUT": "7",
        }):
            route = RouteAgentConfig(_env_file=None)
            plan = PlanAgentConfig(_env_file=None)
            explicit = RouteAgentConfig(_env_file=None,
                                       model=self.factories["aliyun"](name="explicit-model"))
        self.assertEqual(("deepseek", "route-special", 0.2, 7),
                         (route.model.provider, route.model.name, route.temperature, route.timeout_seconds))
        self.assertEqual("deepseek-flash", plan.model.name)
        self.assertEqual("explicit-model", explicit.model.name)
        self.assertEqual("aliyun", explicit.model.provider)
        self.assertNotIn("ds-key", repr(route))

    async def test_provider_credentials_and_temperature_reach_http_request(self):
        real_client = httpx.AsyncClient
        for provider, key in (("deepseek", "ds-key"), ("aliyun", "ali-key")):
            model = self.factories[provider](name="custom-model")
            config = PlanAgentConfig(_env_file=None, model=model, temperature=0.2)

            def handle(request):
                self.assertEqual(model.base_url + "/chat/completions", str(request.url))
                self.assertEqual("Bearer " + key, request.headers["authorization"])
                payload = json.loads(request.content)
                self.assertEqual("custom-model", payload["model"])
                self.assertEqual(0.2, payload["temperature"])
                return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}]})

            with patch("app.helper.model_client.httpx.AsyncClient",
                       side_effect=lambda **kwargs: real_client(transport=httpx.MockTransport(handle), **kwargs)):
                answer = await ModelClient(config).complete([{"role": "user", "content": "hi"}])
            self.assertEqual("ok", answer)

    def test_vendor_env_prefixes_keep_connections_separate(self):
        with patch.dict(os.environ, {"DEEPSEEK_BASE_URL": "https://custom.example/v1"}):
            deepseek = DeepseekConfig(_env_file=None)
            aliyun = AliyunConfig(_env_file=None)
        self.assertEqual("https://custom.example/v1", deepseek.base_url)
        self.assertEqual("https://dashscope.aliyuncs.com/compatible-mode/v1", aliyun.base_url)

    def test_embedding_connection_key_fallback_and_env_mapping(self):
        for override, expected in (("embedding-key", "embedding-key"), (None, "ali-key")):
            secrets = SecretSettings(_env_file=None, rag_embedding_api_key=override)
            with patch("app.core.config.agent.agents.secrets", secrets), \
                 patch("app.core.config.agent.agents.Aliyun", self.factories["aliyun"]), \
                 patch.dict(os.environ, {
                     "RAG_EMBEDDING_BASE_URL": "https://vectors.example/v1",
                     "RAG_EMBEDDING_MODEL": "vector-model", "RAG_EMBEDDING_TIMEOUT": "15",
                 }):
                config = EmbeddingConfig(_env_file=None)
            self.assertEqual("vector-model", config.model.name)
            self.assertEqual("https://vectors.example/v1", config.model.base_url)
            self.assertEqual(15, config.timeout_seconds)
            self.assertEqual(expected, config.model.api_key)
