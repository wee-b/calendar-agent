"""工具单次超时、只读重试及单轮共享预算。"""

from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.config.common.settings import PUBLIC_ENV_FILE


class McpSettings(BaseSettings):
    """重试尝试次数包含首次调用；耗时以毫秒计，写工具只尝试一次。"""

    read_retry_attempts: int = Field(default=2, ge=1, le=5,
                                     validation_alias="MCP_READ_RETRY_ATTEMPTS")
    read_retry_delay_ms: int = Field(default=250, ge=0, le=5000,
                                     validation_alias="MCP_READ_RETRY_DELAY_MS")
    call_timeout_ms: int = Field(default=5000, ge=1, le=300000,
                                validation_alias="MCP_CALL_TIMEOUT_MS")
    turn_max_calls: int = Field(default=12, ge=1, le=100,
                               validation_alias="MCP_TURN_MAX_CALLS")
    turn_budget_ms: int = Field(default=60000, ge=1, le=300000,
                               validation_alias="MCP_TURN_BUDGET_MS")

    model_config = SettingsConfigDict(env_file=PUBLIC_ENV_FILE,
                                      env_file_encoding="utf-8", extra="ignore")


@lru_cache
def get_mcp_settings() -> McpSettings:
    return McpSettings()
