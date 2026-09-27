"""Minimal OpenAI-compatible, non-streaming chat client."""

from __future__ import annotations

import httpx
from fastapi import HTTPException, status

from app.core.config import get_settings


class ModelClient:
    async def complete(self, messages: list[dict[str, str]]) -> str:
        settings = get_settings()
        if settings.chat_provider == "aliyun":
            base_url = settings.aliyun_base_url
            api_key = settings.aliyun_api_key
            model_name = settings.aliyun_model_name
        elif settings.chat_provider == "deepseek":
            base_url = settings.deepseek_base_url
            api_key = settings.deepseek_api_key
            model_name = settings.deepseek_model_name
        else:
            raise HTTPException(status_code=503, detail="不支持的聊天模型提供商")
        if not base_url or not api_key or not model_name:
            raise HTTPException(status_code=503, detail="模型配置未完成")
        url = f"{base_url.rstrip('/')}/chat/completions"
        try:
            async with httpx.AsyncClient(timeout=settings.model_request_timeout) as client:
                response = await client.post(
                    url,
                    headers={"Authorization": f"Bearer {api_key}"},
                    json={"model": model_name, "messages": messages, "stream": False},
                )
        except httpx.TimeoutException as exc:
            raise HTTPException(status_code=status.HTTP_504_GATEWAY_TIMEOUT,
                                detail="模型响应超时") from exc
        except httpx.RequestError as exc:
            raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY,
                                detail="无法连接模型服务") from exc
        if not response.is_success:
            raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY,
                                detail=f"模型服务返回 HTTP {response.status_code}")
        try:
            answer = response.json()["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY,
                                detail="模型响应格式异常") from exc
        if not isinstance(answer, str) or not answer.strip():
            raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY,
                                detail="模型未返回有效回答")
        return answer
