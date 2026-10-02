"""流式聊天的总时限和保活间隔。"""

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.config.common.settings import PUBLIC_ENV_FILE


class ChatStreamSettings(BaseSettings):
    """请求超时保护整次对话；ping 间隔只控制无输出时的保活事件。"""

    request_timeout_seconds: float = Field(default=120, gt=0,
                                           validation_alias="CHAT_STREAM_TIMEOUT_SECONDS")
    ping_interval_seconds: float = Field(default=15, gt=0,
                                         validation_alias="CHAT_STREAM_PING_SECONDS")

    model_config = SettingsConfigDict(env_file=PUBLIC_ENV_FILE,
                                      env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_chat_stream_settings() -> ChatStreamSettings:
    return ChatStreamSettings()
