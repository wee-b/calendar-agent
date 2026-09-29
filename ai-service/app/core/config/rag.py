"""RAG 召回数量、阈值、重排开关和缓存时长配置。"""

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.config.settings import PUBLIC_ENV_FILE


class RagSettings(BaseSettings):
    rag_top_k: int = Field(default=3, ge=1)
    rag_bm25_top_k: int = Field(default=10, ge=1)
    rag_dense_top_k: int = Field(default=10, ge=1)
    rag_recall_threshold: float = Field(default=0.015, ge=0)
    rag_rerank_enabled: bool = False
    rag_embedding_cache_ttl: int = Field(default=86400, ge=1)
    rag_result_cache_ttl: int = Field(default=1800, ge=1)

    model_config = SettingsConfigDict(env_file=PUBLIC_ENV_FILE,
                                      env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_rag_settings() -> RagSettings:
    return RagSettings()
