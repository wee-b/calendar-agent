"""远程 Embedding 模型地址、名称与超时配置。"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.config.settings import PUBLIC_ENV_FILE


class EmbeddingSettings(BaseSettings):
    rag_embedding_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    rag_embedding_model: str = "text-embedding-v4"
    rag_embedding_timeout: float = 120.0

    model_config = SettingsConfigDict(env_file=PUBLIC_ENV_FILE,
                                      env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_embedding_settings() -> EmbeddingSettings:
    return EmbeddingSettings()
