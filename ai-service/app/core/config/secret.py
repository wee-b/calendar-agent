"""API Key 只从 .env.prod 或进程环境变量加载，不读取普通 .env。"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.config.settings import SECRET_ENV_FILE


class SecretSettings(BaseSettings):
    """聊天与 RAG Embedding 所需的密钥。"""

    aliyun_api_key: str | None = None
    deepseek_api_key: str | None = None
    rag_embedding_api_key: str | None = None

    model_config = SettingsConfigDict(env_file=SECRET_ENV_FILE,
                                      env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_secret_settings() -> SecretSettings:
    return SecretSettings()
