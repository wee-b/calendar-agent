"""调用 OpenAI-compatible 模型接口，并在返回边界校验消息与流片段。"""

from __future__ import annotations

import json
from typing import Any, AsyncIterator

import httpx
from pydantic import ValidationError

from app.core.config.agent.agents import AgentConfig, chat_config
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException
from app.schemas.chat.model_stream import AssistantMessage, ModelDelta


class ModelClient:
    """根据提供商配置选择模型；对话图只接触已校验的模型输出。"""

    def __init__(self, config: AgentConfig | None = None):
        self.config = config if config is not None else chat_config

    def _request_options(self) -> tuple[str, str, str, float]:
        """选择 URL、密钥、模型名和超时；密钥只从 SecretSettings 读取。"""

        model = self.config.model
        if model.api_type != "openai-compatible":
            raise BusinessException(ErrorCode.MODEL_PROVIDER_UNSUPPORTED)
        base_url, api_key, model_name = model.base_url, model.api_key, model.name
        if not base_url or not api_key or not model_name:
            raise BusinessException(ErrorCode.MODEL_CONFIG_INCOMPLETE)
        return f"{base_url.rstrip('/')}/chat/completions", api_key, model_name, self.config.timeout_seconds

    def _payload(self, model_name: str, messages: list[dict[str, Any]],
                 tools: list[dict[str, Any]] | None, streaming: bool) -> dict[str, Any]:
        """在 HTTP 边界把消息和工具声明组装成模型协议请求体。"""

        payload: dict[str, Any] = {"model": model_name, "messages": messages,
                                   "stream": streaming}
        if self.config.temperature is not None:
            payload["temperature"] = self.config.temperature
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"
        return payload

    async def complete(self, messages: list[dict[str, str]]) -> str:
        """供 RAG 重排使用的纯文本回答入口。"""

        answer = (await self.chat(messages)).content
        if not isinstance(answer, str) or not answer.strip():
            raise BusinessException(ErrorCode.MODEL_EMPTY_ANSWER)
        return answer

    async def chat(
        self, messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
    ) -> AssistantMessage:
        """非流式调用；返回前校验模型消息，拒绝空正文且无工具调用的响应。"""

        url, api_key, model_name, timeout = self._request_options()
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(
                    url,
                    headers={"Authorization": f"Bearer {api_key}"},
                    json=self._payload(model_name, messages, tools, False),
                )
        except httpx.TimeoutException as exc:
            raise BusinessException(ErrorCode.MODEL_TIMEOUT) from exc
        except httpx.RequestError as exc:
            raise BusinessException(ErrorCode.MODEL_UNAVAILABLE) from exc
        if not response.is_success:
            raise BusinessException(ErrorCode.MODEL_HTTP_ERROR,
                                    message=f"模型服务返回 HTTP {response.status_code}")
        try:
            message = AssistantMessage.model_validate(response.json()["choices"][0]["message"])
        except (ValueError, KeyError, IndexError, TypeError, ValidationError) as exc:
            raise BusinessException(ErrorCode.MODEL_RESPONSE_INVALID) from exc
        if not message.content and not message.tool_calls:
            raise BusinessException(ErrorCode.MODEL_EMPTY_ANSWER)
        return message

    async def stream_chat(
        self, messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
    ) -> AsyncIterator[ModelDelta]:
        """逐个解析 SSE delta；取消请求时同步关闭上游 HTTP 流。"""
        url, api_key, model_name, timeout = self._request_options()
        completed = False
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                async with client.stream(
                    "POST", url,
                    headers={"Authorization": f"Bearer {api_key}"},
                    json=self._payload(model_name, messages, tools, True),
                ) as response:
                    if not response.is_success:
                        raise BusinessException(ErrorCode.MODEL_HTTP_ERROR,
                                                message=f"模型服务返回 HTTP {response.status_code}")
                    data_lines: list[str] = []
                    # 一个 SSE 事件可能由多行 data 组成，空行才表示本事件结束。
                    async for line in response.aiter_lines():
                        if line.startswith("data:"):
                            data_lines.append(line[5:].lstrip())
                            continue
                        if line and not line.startswith(":"):
                            continue
                        if not data_lines:
                            continue
                        payload = "\n".join(data_lines)
                        data_lines.clear()
                        if payload == "[DONE]":
                            completed = True
                            break
                        delta = self._parse_delta(payload)
                        if delta is not None:
                            yield delta
                    if not completed and data_lines:
                        payload = "\n".join(data_lines)
                        if payload == "[DONE]":
                            completed = True
                        else:
                            delta = self._parse_delta(payload)
                            if delta is not None:
                                yield delta
        except httpx.TimeoutException as exc:
            raise BusinessException(ErrorCode.MODEL_TIMEOUT) from exc
        except httpx.RequestError as exc:
            raise BusinessException(ErrorCode.MODEL_UNAVAILABLE) from exc
        if not completed:
            raise BusinessException(ErrorCode.MODEL_STREAM_EARLY_END)

    @staticmethod
    def _parse_delta(payload: str) -> ModelDelta | None:
        """把单个 SSE 数据块转为类型对象，并区分文本与工具片段错误。"""

        try:
            body = json.loads(payload)
            if isinstance(body, dict) and body.get("error"):
                raise BusinessException(ErrorCode.MODEL_STREAM_ERROR)
            choices = body["choices"]
            if not choices:
                return None
            delta = choices[0]["delta"]
            if not isinstance(delta, dict):
                raise TypeError
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise BusinessException(ErrorCode.MODEL_STREAM_INVALID) from exc
        try:
            return ModelDelta.model_validate(delta)
        except ValidationError as exc:
            if any(error["loc"] and error["loc"][0] == "content"
                   for error in exc.errors()):
                raise BusinessException(ErrorCode.MODEL_TEXT_DELTA_INVALID) from exc
            raise BusinessException(ErrorCode.MODEL_TOOL_DELTA_INVALID) from exc
