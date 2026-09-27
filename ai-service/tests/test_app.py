import unittest

import httpx
from fastapi import FastAPI

from app.core.exception.exceptions import BusinessException
from app.core.exception.handlers import register_exception_handlers
from app.main import app


class AppStructureTests(unittest.IsolatedAsyncioTestCase):
    async def test_routes_are_registered(self):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                     base_url="http://test") as client:
            health = await client.get("/health")
            spec = await client.get("/openapi.json")
        self.assertEqual({"status": "ok"}, health.json())
        self.assertTrue({"/health", "/auth/me", "/chat"}.issubset(spec.json()["paths"]))

    async def test_business_error_has_standard_response(self):
        sample = FastAPI()
        register_exception_handlers(sample)

        @sample.get("/fail")
        async def fail():
            raise BusinessException("示例错误")

        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=sample),
                                     base_url="http://test") as client:
            response = await client.get("/fail")
        self.assertEqual(400, response.status_code)
        self.assertEqual({"code": 4000, "ok": False, "msg": "示例错误", "data": None},
                         response.json())
