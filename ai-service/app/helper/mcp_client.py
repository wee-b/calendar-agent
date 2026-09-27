"""Async client for the Java service's lightweight /mcp JSON-RPC endpoint."""

from __future__ import annotations

import json
from typing import Any
from uuid import uuid4

import httpx

from app.core.config import get_settings


class McpClientError(Exception):
    def __init__(self, code: str, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.retryable = retryable


class JavaMcpClient:
    def __init__(self, client: httpx.AsyncClient | None = None) -> None:
        settings = get_settings()
        self._url = f"{settings.java_base_url.rstrip('/')}/mcp"
        self._token_header_name = settings.token_header_name
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(timeout=settings.java_request_timeout)

    async def __aenter__(self) -> JavaMcpClient:
        return self

    async def __aexit__(self, *_: object) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def list_tools(self, token: str) -> list[dict[str, Any]]:
        result = await self._request("tools/list", {}, token)
        tools = result.get("tools") if isinstance(result, dict) else None
        if not isinstance(tools, list) or any(not isinstance(tool, dict) for tool in tools):
            raise McpClientError("INVALID_RESPONSE", "Java 工具列表格式异常")
        return tools

    async def call_tool(self, name: str, arguments: dict[str, Any], token: str) -> Any:
        result = await self._request(
            "tools/call", {"name": name, "arguments": arguments}, token
        )
        if not isinstance(result, dict) or not isinstance(result.get("isError"), bool):
            raise McpClientError("INVALID_RESPONSE", "Java 工具结果格式异常")
        content = result.get("content")
        if not isinstance(content, list) or not content or not isinstance(content[0], dict):
            raise McpClientError("INVALID_RESPONSE", "Java 工具结果缺少内容")
        text = content[0].get("text")
        if content[0].get("type") != "text" or not isinstance(text, str):
            raise McpClientError("INVALID_RESPONSE", "Java 工具结果内容格式异常")
        try:
            data = json.loads(text)
        except json.JSONDecodeError as exc:
            raise McpClientError("INVALID_RESPONSE", "Java 工具结果不是 JSON") from exc

        if result["isError"]:
            if not isinstance(data, dict):
                raise McpClientError("INVALID_RESPONSE", "Java 工具错误格式异常")
            raise McpClientError(
                str(data.get("code", "TOOL_ERROR")),
                str(data.get("message", "Java 工具调用失败")),
                retryable=data.get("retryable") is True,
            )
        return data

    async def _request(self, method: str, params: dict[str, Any], token: str) -> Any:
        if not token:
            raise McpClientError("MISSING_TOKEN", "缺少用户 token")
        request_id = uuid4().hex
        try:
            response = await self._client.post(
                self._url,
                headers={self._token_header_name: token},
                json={"jsonrpc": "2.0", "id": request_id, "method": method, "params": params},
            )
        except httpx.TimeoutException as exc:
            raise McpClientError("JAVA_TIMEOUT", "Java 工具服务响应超时", retryable=True) from exc
        except httpx.RequestError as exc:
            raise McpClientError("JAVA_UNAVAILABLE", "无法连接 Java 工具服务", retryable=True) from exc

        if response.status_code in (401, 403):
            raise McpClientError("AUTH_FAILED", "用户 token 无效或无权调用工具")
        if not response.is_success:
            raise McpClientError("JAVA_HTTP_ERROR", f"Java 工具服务返回 HTTP {response.status_code}")
        try:
            body = response.json()
        except ValueError as exc:
            raise McpClientError("INVALID_RESPONSE", "Java 工具服务返回的不是 JSON") from exc
        if not isinstance(body, dict):
            raise McpClientError("INVALID_RESPONSE", "Java 工具服务返回格式异常")
        # Java's login interceptor can return HTTP 200 with a ResponseDTO error.
        if body.get("ok") is False:
            code = "AUTH_FAILED" if body.get("code") in (401, 403) else "JAVA_ERROR"
            raise McpClientError(code, str(body.get("msg") or "Java 工具服务拒绝请求"))
        if body.get("jsonrpc") != "2.0" or body.get("id") != request_id:
            raise McpClientError("INVALID_RESPONSE", "Java JSON-RPC 响应标识不匹配")
        if "error" in body:
            error = body["error"]
            if not isinstance(error, dict):
                raise McpClientError("INVALID_RESPONSE", "Java JSON-RPC 错误格式异常")
            raise McpClientError(str(error.get("code", "RPC_ERROR")),
                                 str(error.get("message", "Java JSON-RPC 调用失败")))
        if "result" not in body:
            raise McpClientError("INVALID_RESPONSE", "Java JSON-RPC 响应缺少结果")
        return body["result"]
