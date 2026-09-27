import json
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import httpx

from app.helper.model_client import ModelClient


class ModelConfigTests(unittest.IsolatedAsyncioTestCase):
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
                    deepseek_api_key="deepseek-secret",
                    deepseek_model_name="deepseek-flash",
                    aliyun_base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
                    aliyun_api_key="aliyun-secret",
                    aliyun_model_name="qwen3.7-plus",
                    model_request_timeout=5,
                )

                def handle(request):
                    self.assertEqual(base_url + "/chat/completions", str(request.url))
                    self.assertEqual("Bearer " + api_key, request.headers["authorization"])
                    self.assertEqual(model_name, json.loads(request.content)["model"])
                    return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}]})

                transport = httpx.MockTransport(handle)
                with patch("app.helper.model_client.get_settings", return_value=settings), \
                     patch("app.helper.model_client.httpx.AsyncClient",
                           side_effect=lambda **kwargs: real_client(transport=transport, **kwargs)):
                    answer = await ModelClient().complete([{"role": "user", "content": "hi"}])
                self.assertEqual("ok", answer)
