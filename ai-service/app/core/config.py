from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


AI_SERVICE_DIR = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    java_base_url: str = "http://127.0.0.1:8080"
    token_header_name: str = "yvli-token"
    java_request_timeout: float = 5.0

    model_config = SettingsConfigDict(
        env_file=AI_SERVICE_DIR / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
