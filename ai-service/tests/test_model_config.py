import json
import os
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import httpx

from app.helper.model_client import ModelClient
from app.core.config.embedding import EmbeddingSettings
from app.core.config.qdrant import QdrantSettings
from app.core.config.secret import SecretSettings
from app.core.config.settings import Settings


class ModelConfigTests(unittest.IsolatedAsyncioTestCase):
    async def test_public_and_secret_files_are_isolated(self):
        with tempfile.TemporaryDirectory() as directory:
            public = Path(directory, ".env")
            secret = Path(directory, ".env.prod")
            public.write_text("CHAT_PROVIDER=aliyun\nALIYUN_API_KEY=wrong\n"
                              "RAG_EMBEDDING_MODEL=remote-model\n"
                              "REDIS_URL=redis://localhost:6379/15\n"
                              "DATABASE_URL=mysql+aiomysql://localhost/test\n",
                              encoding="utf-8")
            secret.write_text("ALIYUN_API_KEY=correct\n",
                              encoding="utf-8")
            with patch.dict(os.environ, {}, clear=True):
                settings = Settings(_env_file=public)
                embedding = EmbeddingSettings(_env_file=public)
                qdrant = QdrantSettings(_env_file=public)
                secrets = SecretSettings(_env_file=secret)
            self.assertEqual("aliyun", settings.chat_provider)
            self.assertEqual("redis://localhost:6379/15", settings.redis_url)
            self.assertEqual("mysql+aiomysql://localhost/test", settings.database_url)
            self.assertFalse(hasattr(settings, "aliyun_api_key"))
            self.assertEqual("remote-model", embedding.rag_embedding_model)
            self.assertFalse(hasattr(embedding, "rag_embedding_api_key"))
            self.assertEqual("rag_corpus", qdrant.qdrant_collection)
            self.assertEqual("correct", secrets.aliyun_api_key)
            self.assertFalse(hasattr(secrets, "qdrant_api_key"))
            self.assertFalse(hasattr(secrets, "database_url"))

    async def test_provider_specific_credentials_and_model(self):
        real_client = httpx.AsyncClient
        for provider, base_url, api_key, model_name in (
            ("deepseek", "https://api.deepseek.com", "deepseek-secret", "deepseek-flash"),
            ("aliyun", "https://dashscope.aliyuncs.com/compatible-mode/v1",
             "aliyun-secret", "qwen3.7-plus"),
        ):
            with self.subTest(provider=provider):
                settings = SimpleNamespace(
                    chat_provider=provider,
                    deepseek_base_url="https://api.deepseek.com",
                    deepseek_model_name="deepseek-flash",
                    aliyun_base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
                    aliyun_model_name="qwen3.7-plus",
                    model_request_timeout=5,
                )

                def handle(request):
                    self.assertEqual(base_url + "/chat/completions", str(request.url))
                    self.assertEqual("Bearer " + api_key, request.headers["authorization"])
                    self.assertEqual(model_name, json.loads(request.content)["model"])
                    return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}]})

                transport = httpx.MockTransport(handle)
                secrets = SimpleNamespace(deepseek_api_key="deepseek-secret",
                                          aliyun_api_key="aliyun-secret")
                with patch("app.helper.model_client.get_settings", return_value=settings), \
                     patch("app.helper.model_client.get_secret_settings", return_value=secrets), \
                     patch("app.helper.model_client.httpx.AsyncClient",
                           side_effect=lambda **kwargs: real_client(transport=transport, **kwargs)):
                    answer = await ModelClient().complete([{"role": "user", "content": "hi"}])
                self.assertEqual("ok", answer)
