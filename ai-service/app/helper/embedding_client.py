"""远程 Embedding 客户端，导入语料和在线检索共用。"""

import httpx
from pydantic import ValidationError

from app.core.config.agent.agents import EmbeddingConfig, embedding_config
from app.core.exception.exceptions import EmbeddingError
from app.schemas.rag import EmbeddingResponse


class EmbeddingClient:
    """请求百炼兼容接口，并拒绝缺失、非数值或非有限的向量。"""

    def __init__(self, client: httpx.AsyncClient | None = None,
                 config: EmbeddingConfig | None = None):
        self.config = config if config is not None else embedding_config
        self.client = client

    @property
    def namespace(self) -> str:
        return f"{self.config.model.base_url.rstrip('/')}:{self.config.model.name}"

    async def embed(self, text: str) -> list[float]:
        key = self.config.model.api_key
        if not key:
            raise EmbeddingError("RAG_EMBEDDING_API_KEY 未配置")
        url = self.config.model.base_url.rstrip("/") + "/embeddings"
        payload = {"model": self.config.model.name, "input": text}
        headers = {"Authorization": f"Bearer {key}"}

        try:
            if self.client is None:
                async with httpx.AsyncClient(timeout=self.config.timeout_seconds) as client:
                    response = await client.post(url, json=payload, headers=headers)
            else:
                response = await self.client.post(url, json=payload, headers=headers)
            response.raise_for_status()
            body = EmbeddingResponse.model_validate(response.json())
            return body.data[0].embedding
        except (httpx.HTTPError, ValueError, ValidationError) as exc:
            raise EmbeddingError("Embedding 请求或响应异常") from exc
