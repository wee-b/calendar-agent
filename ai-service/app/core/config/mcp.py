"""Java MCP 只读工具的重试策略。"""

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.config.settings import PUBLIC_ENV_FILE


class McpSettings(BaseSettings):
    """次数包含首次调用，间隔以毫秒计；写工具不使用此策略。"""

    read_retry_attempts: int = Field(default=2, ge=1, le=5,
                                     validation_alias="MCP_READ_RETRY_ATTEMPTS")
    read_retry_delay_ms: int = Field(default=250, ge=0, le=5000,
                                     validation_alias="MCP_READ_RETRY_DELAY_MS")

    model_config = SettingsConfigDict(env_file=PUBLIC_ENV_FILE,
                                      env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_mcp_settings() -> McpSettings:
    return McpSettings()
