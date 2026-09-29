"""Qdrant REST 地址、集合名称和请求超时配置。"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.config.settings import PUBLIC_ENV_FILE


class QdrantSettings(BaseSettings):
    qdrant_url: str = "http://localhost:6333"
    qdrant_collection: str = "rag_corpus"
    qdrant_timeout: float = 10.0

    model_config = SettingsConfigDict(env_file=PUBLIC_ENV_FILE,
                                      env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_qdrant_settings() -> QdrantSettings:
    return QdrantSettings()
