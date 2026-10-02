"""RAG 检索和语料导入共用的 Redis Embedding 缓存。"""

from hashlib import md5
import json
import logging

from app.core.config.common.rag import get_rag_settings
from app.db.redis_client import get_redis
from app.helper.embedding_client import EmbeddingClient
from app.schemas.rag import EmbeddingItem

logger = logging.getLogger(__name__)


class CachedEmbedding:
    """按模型命名空间与文本生成缓存键，防止切换模型后误用旧向量。"""

    def __init__(self, client: EmbeddingClient | None = None, redis=None):
        self.client = client or EmbeddingClient()
        self.redis = redis if redis is not None else get_redis()

    @property
    def namespace(self) -> str:
        return self.client.namespace

    async def embed(self, text: str) -> list[float]:
        """缓存异常或缓存向量无效时回退到远程 Embedding。"""

        key = "rag:emb:" + md5(f"{self.namespace}:{text}".encode()).hexdigest()
        try:
            cached = await self.redis.get(key)
            if cached:
                return EmbeddingItem.model_validate({"embedding": json.loads(cached)}).embedding
        except Exception as exc:
            logger.warning("Embedding 缓存读取失败: %s", exc)
        vector = await self.client.embed(text)
        try:
            await self.redis.set(key, json.dumps(vector),
                                 ex=get_rag_settings().rag_embedding_cache_ttl)
        except Exception as exc:
            logger.warning("Embedding 缓存写入失败: %s", exc)
        return vector
