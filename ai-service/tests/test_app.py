import unittest

import httpx
from fastapi import FastAPI

from app.core.exception.exceptions import BusinessException
from app.core.exception.error_code import ErrorCode
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
            raise BusinessException(ErrorCode.BUSINESS_ERROR)

        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=sample),
                                     base_url="http://test") as client:
            response = await client.get("/fail")
        self.assertEqual(400, response.status_code)
        self.assertEqual({"code": 4000, "ok": False, "msg": "业务错误", "data": None},
                         response.json())

    async def test_chat_error_keeps_existing_http_shape(self):
        sample = FastAPI()
        register_exception_handlers(sample)

        @sample.get("/fail")
        async def fail():
            raise BusinessException(ErrorCode.MODEL_EMPTY_ANSWER)

        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=sample),
                                     base_url="http://test") as client:
            response = await client.get("/fail")
        self.assertEqual(502, response.status_code)
        self.assertEqual({"detail": "模型未返回有效回答"}, response.json())
