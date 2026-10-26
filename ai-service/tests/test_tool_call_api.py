import unittest
from unittest.mock import AsyncMock, patch

import httpx

from app.main import app


class ToolCallApiTests(unittest.IsolatedAsyncioTestCase):
    async def test_routes_require_login(self):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            self.assertEqual(401, (await client.get("/chat/tool-calls?sessionId=s")).status_code)
            self.assertEqual(401, (await client.get("/chat/tool-stats")).status_code)

    async def test_paging_uses_authenticated_identity_and_bounds(self):
        verifier = AsyncMock()
        verifier.verify.return_value = 23
        repository = AsyncMock()
        repository.list_calls.return_value = ([], False)
        with patch("app.core.middleware.auth.RedisTokenVerifier", return_value=verifier), \
             patch("app.api.routers.tool_call_router.ToolCallRepository", return_value=repository):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                for params in ({}, {"sessionId": " "}, {"sessionId": "s", "limit": 101},
                               {"sessionId": "s", "beforeId": 0}):
                    self.assertEqual(422, (await client.get("/chat/tool-calls", params=params)).status_code)
                response = await client.get("/chat/tool-calls?sessionId=s&userId=99&limit=2")
        self.assertEqual(200, response.status_code)
        repository.list_calls.assert_awaited_once_with(23, "s", None, 2)
        self.assertEqual([], response.json()["data"]["items"])

    async def test_stats_reject_invalid_windows_before_query(self):
        verifier = AsyncMock()
        verifier.verify.return_value = 23
        with patch("app.core.middleware.auth.RedisTokenVerifier", return_value=verifier), \
             patch("app.api.routers.tool_call_router.ToolCallRepository") as repository:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                for params in (
                    {"start": "2026-10-26T00:00:00", "end": "2026-10-27T00:00:00"},
                    {"start": "2026-10-26T00:00:00Z", "end": "2026-10-25T00:00:00Z"},
                    {"start": "2026-08-01T00:00:00Z", "end": "2026-10-26T00:00:00Z"},
                ):
                    self.assertEqual(422, (await client.get("/chat/tool-stats", params=params)).status_code)
        repository.assert_not_called()
