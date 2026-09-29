"""远程 Embedding 客户端，导入语料和在线检索共用。"""

import httpx
from pydantic import ValidationError

from app.core.config.embedding import get_embedding_settings
from app.core.config.secret import get_secret_settings
from app.core.exception.exceptions import EmbeddingError
from app.schemas.rag import EmbeddingResponse


class EmbeddingClient:
    """请求百炼兼容接口，并拒绝缺失、非数值或非有限的向量。"""

    def __init__(self, client: httpx.AsyncClient | None = None):
        self.settings = get_embedding_settings()
        self.client = client

    @property
    def namespace(self) -> str:
        return f"{self.settings.rag_embedding_base_url.rstrip('/')}:{self.settings.rag_embedding_model}"

    async def embed(self, text: str) -> list[float]:
        secrets = get_secret_settings()
        key = secrets.rag_embedding_api_key or secrets.aliyun_api_key
        if not key:
            raise EmbeddingError("RAG_EMBEDDING_API_KEY 未配置")
        url = self.settings.rag_embedding_base_url.rstrip("/") + "/embeddings"
        payload = {"model": self.settings.rag_embedding_model, "input": text}
        headers = {"Authorization": f"Bearer {key}"}

        try:
            if self.client is None:
                async with httpx.AsyncClient(timeout=self.settings.rag_embedding_timeout) as client:
                    response = await client.post(url, json=payload, headers=headers)
            else:
                response = await self.client.post(url, json=payload, headers=headers)
            response.raise_for_status()
            body = EmbeddingResponse.model_validate(response.json())
            return body.data[0].embedding
        except (httpx.HTTPError, ValueError, ValidationError) as exc:
            raise EmbeddingError("Embedding 请求或响应异常") from exc
