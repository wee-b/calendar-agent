"""记忆与摘要的运行策略。"""
from functools import lru_cache
from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from app.core.config.common.settings import PUBLIC_ENV_FILE


class MemorySettings(BaseSettings):
    enabled: bool = True
    summary_trigger_messages: int = Field(default=50, ge=4)
    summary_retain_messages: int = Field(default=20, ge=2)
    summary_max_chars: int = Field(default=1200, ge=100)
    message_max_chars: int = Field(default=1000, ge=100)
    summary_timeout_seconds: float = Field(default=120, gt=0)
    model_config = SettingsConfigDict(env_file=PUBLIC_ENV_FILE, env_prefix="MEMORY_",
                                      env_file_encoding="utf-8", extra="ignore")

    @model_validator(mode="after")
    def check_batch(self):
        if self.summary_retain_messages >= self.summary_trigger_messages:
            raise ValueError("保留条数必须小于触发阈值，以保留近期原文")
        return self


@lru_cache
def get_memory_settings():
    return MemorySettings()
