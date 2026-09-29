import unittest
from unittest.mock import patch

import httpx
from redis.exceptions import ConnectionError as RedisConnectionError

from app.cache.token_cache import RedisTokenVerifier
from app.core.exception.exceptions import BusinessException
from app.main import app


class FakeRedis:
    def __init__(self, values):
        self.values = values
        self.last_key = None

    async def get(self, key):
        self.last_key = key
        return self.values.get(key)


class RedisTokenTests(unittest.IsolatedAsyncioTestCase):
    async def test_valid_and_revoked_tokens(self):
        client = FakeRedis({"yvli-token:client:token:valid": "23"})
        verifier = RedisTokenVerifier(client)
        self.assertEqual(23, await verifier.verify("valid"))
        self.assertEqual("yvli-token:client:token:valid", client.last_key)
        with self.assertRaises(BusinessException) as caught:
            await verifier.verify("revoked")
        self.assertEqual(401, caught.exception.status_code)

    async def test_abnormal_sa_token_marker_is_rejected(self):
        verifier = RedisTokenVerifier(FakeRedis({
            "yvli-token:client:token:kicked": "-5",
        }))
        with self.assertRaises(BusinessException) as caught:
            await verifier.verify("kicked")
        self.assertEqual(401, caught.exception.status_code)

    async def test_redis_outage_fails_closed(self):
        class BrokenRedis:
            async def get(self, _):
                raise RedisConnectionError("offline")

        with self.assertRaises(BusinessException) as caught:
            await RedisTokenVerifier(BrokenRedis()).verify("valid")
        self.assertEqual(503, caught.exception.status_code)


class ChatEndpointTests(unittest.IsolatedAsyncioTestCase):
    async def test_chat_uses_authenticated_user_and_saves_one_round(self):
        class Verifier:
            async def verify(self, token):
                self_token = token
                assert self_token == "valid"
                return 23

        class Repository:
            saved = None

            async def recent_messages(self, user_id, session_id):
                assert (user_id, session_id) == (23, "session-1")
                return []

            async def save_round(self, user_id, session_id, message, answer, elapsed_ms):
                Repository.saved = (user_id, session_id, message, answer, elapsed_ms)

        class Model:
            async def chat(self, messages, tools=None):
                assert messages[-1] == {"role": "user", "content": "你好"}
                return {"role": "assistant", "content": "你好，我能帮你安排日程。"}

        with patch("app.core.middleware.auth.RedisTokenVerifier", return_value=Verifier()), \
             patch("app.service.chat.ChatRepository", return_value=Repository()), \
             patch("app.service.chat_graph.ModelClient", return_value=Model()):
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="http://test"
            ) as client:
                response = await client.post("/chat", headers={"yvli-token": "valid"},
                                             json={"sessionId": "session-1", "message": "你好"})
        self.assertEqual(200, response.status_code)
        self.assertEqual("你好，我能帮你安排日程。", response.json()["data"]["aiResult"])
        self.assertEqual((23, "session-1", "你好", "你好，我能帮你安排日程。"),
                         Repository.saved[:4])

    async def test_missing_token_is_blocked_before_chat(self):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test"
        ) as client:
            response = await client.post("/chat", json={"message": "你好"})
        self.assertEqual(401, response.status_code)
