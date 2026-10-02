"""普通运行配置；API Key 由独立的 SecretSettings 加载。"""

from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


AI_SERVICE_DIR = Path(__file__).resolve().parents[4]
PUBLIC_ENV_FILE = AI_SERVICE_DIR / ".env"
SECRET_ENV_FILE = AI_SERVICE_DIR / ".env.prod"


class Settings(BaseSettings):
    """从公开配置文件和进程环境变量读取连接与模型选择参数。"""

    java_base_url: str = "http://127.0.0.1:8080"
    cors_origins: list[str] = Field(default_factory=list)
    token_header_name: str = "yvli-token"
    java_request_timeout: float = 5.0
    redis_url: str | None = None
    database_url: str | None = None

    model_config = SettingsConfigDict(
        env_file=PUBLIC_ENV_FILE,
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
