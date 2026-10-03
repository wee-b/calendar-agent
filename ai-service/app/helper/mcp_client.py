"""Java /mcp 的异步 JSON-RPC 客户端；只在边界处理协议字典。"""

from __future__ import annotations

import json
from uuid import uuid4

import httpx
from pydantic import ValidationError

from app.core.config import get_settings
from app.core.exception.exceptions import McpClientError
from app.schemas.mcp import (
    JavaErrorResponse, JsonRpcResponse, McpCallResult, McpToolDefinition,
    McpToolFailure, McpToolList,
)


class JavaMcpClient:
    """负责 token 转发、JSON-RPC 校验和 Java 工具错误解码。"""

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

    async def list_tools(self, token: str) -> list[McpToolDefinition]:
        """解析工具目录及只读元数据。"""

        result = await self._request("tools/list", {}, token)
        try:
            return McpToolList.model_validate(result).tools
        except ValidationError as exc:
            raise McpClientError("INVALID_RESPONSE", "Java 工具列表格式异常") from exc

    async def call_tool(self, name: str, arguments: dict[str, object], token: str) -> object:
        """解析 MCP 外壳和文本内容；具体工具结果由调用服务继续校验。"""

        result = await self._request(
            "tools/call", {"name": name, "arguments": arguments}, token
        )
        try:
            call = McpCallResult.model_validate(result)
        except ValidationError as exc:
            raise McpClientError("INVALID_RESPONSE", "Java 工具结果格式异常") from exc
        try:
            data = json.loads(call.content[0].text)
        except json.JSONDecodeError as exc:
            raise McpClientError("INVALID_RESPONSE", "Java 工具结果不是 JSON") from exc

        if call.isError:
            try:
                failure = McpToolFailure.model_validate(data)
            except ValidationError as exc:
                raise McpClientError("INVALID_RESPONSE", "Java 工具错误格式异常") from exc
            raise McpClientError(
                str(failure.code), failure.message, retryable=failure.retryable,
            )
        return data

    async def _request(self, method: str, params: dict[str, object], token: str) -> object:
        """执行一次 JSON-RPC 请求，核对请求 ID 并区分拦截器与 RPC 错误。"""

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
        # Java's login interceptor can return HTTP 200 with a ResponseDTO error.
        if isinstance(body, dict) and body.get("ok") is False:
            try:
                java_error = JavaErrorResponse.model_validate(body)
            except ValidationError as exc:
                raise McpClientError("INVALID_RESPONSE", "Java 工具服务返回格式异常") from exc
            code = "AUTH_FAILED" if java_error.code in (401, 403) else "JAVA_ERROR"
            raise McpClientError(code, java_error.msg or "Java 工具服务拒绝请求")
        try:
            rpc = JsonRpcResponse.model_validate(body)
        except ValidationError as exc:
            raise McpClientError("INVALID_RESPONSE", "Java 工具服务返回格式异常") from exc
        if rpc.id != request_id:
            raise McpClientError("INVALID_RESPONSE", "Java JSON-RPC 响应标识不匹配")
        if rpc.error is not None:
            raise McpClientError(str(rpc.error.code), rpc.error.message)
        if "result" not in rpc.model_fields_set:
            raise McpClientError("INVALID_RESPONSE", "Java JSON-RPC 响应缺少结果")
        return rpc.result
