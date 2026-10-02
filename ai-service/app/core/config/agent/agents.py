"""各 Agent 独立读取配置；直接导入 chat_config、route_config 等实例使用。"""

from dataclasses import replace

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.config.agent.providers import Aliyun, ModelConfig, providers, secrets
from app.core.config.common.settings import PUBLIC_ENV_FILE


class AgentConfig(BaseSettings):
    model: ModelConfig | None = None
    provider: str = "deepseek"
    model_name: str | None = None
    temperature: float | None = None
    timeout_seconds: float = Field(default=60, gt=0)

    model_config = SettingsConfigDict(env_file=PUBLIC_ENV_FILE,
                                      env_file_encoding="utf-8", extra="ignore",
                                      populate_by_name=True)

    @model_validator(mode="after")
    def resolve_model(self):
        """支持显式 model=Deepseek(name=...)，否则根据该 Agent 的环境配置选择。"""
        if self.model is None:
            if self.provider not in providers:
                raise ValueError(f"未知模型厂商: {self.provider}")
            self.model = providers[self.provider](name=self.model_name)
        return self


class ChatAgentConfig(AgentConfig):
    temperature: float | None = 0.3
    timeout_seconds: float = Field(default=60, gt=0, validation_alias="MODEL_REQUEST_TIMEOUT")
    model_config = SettingsConfigDict(env_prefix="CHAT_")


class RouteAgentConfig(AgentConfig):
    provider: str = "aliyun"
    temperature: float | None = 0.1
    timeout_seconds: float = Field(default=20, gt=0, validation_alias="ROUTE_REQUEST_TIMEOUT")
    model_config = SettingsConfigDict(env_prefix="ROUTE_")


class PlanAgentConfig(AgentConfig):
    temperature: float | None = 0.1
    timeout_seconds: float = Field(default=60, gt=0, validation_alias="PLAN_REQUEST_TIMEOUT")
    model_config = SettingsConfigDict(env_prefix="PLAN_")


class ExecutorAgentConfig(AgentConfig):
    provider: str = "aliyun"
    temperature: float | None = 0.3
    timeout_seconds: float = Field(default=60, gt=0, validation_alias="EXECUTOR_REQUEST_TIMEOUT")
    model_config = SettingsConfigDict(env_prefix="EXECUTOR_")


class SummaryAgentConfig(AgentConfig):
    provider: str = "aliyun"
    temperature: float | None = 0.1
    timeout_seconds: float = Field(default=60, gt=0, validation_alias="SUMMARY_REQUEST_TIMEOUT")
    model_config = SettingsConfigDict(env_prefix="SUMMARY_")


class ImageAgentConfig(AgentConfig):
    provider: str = "ark"
    size: str = "2K"
    watermark: bool = True
    timeout_seconds: float = Field(default=120, gt=0, validation_alias="IMAGE_REQUEST_TIMEOUT")
    model_config = SettingsConfigDict(env_prefix="IMAGE_")


class EmbeddingConfig(BaseSettings):
    model: ModelConfig | None = None
    base_url: str = Field(default="https://dashscope.aliyuncs.com/compatible-mode/v1",
                          validation_alias="RAG_EMBEDDING_BASE_URL")
    model_name: str = Field(default="text-embedding-v4", validation_alias="RAG_EMBEDDING_MODEL")
    timeout_seconds: float = Field(default=120, gt=0, validation_alias="RAG_EMBEDDING_TIMEOUT")

    model_config = SettingsConfigDict(env_file=PUBLIC_ENV_FILE, env_prefix="EMBEDDING_",
                                      env_file_encoding="utf-8", extra="ignore",
                                      populate_by_name=True)

    @model_validator(mode="after")
    def resolve_model(self):
        if self.model is None:
            model = Aliyun(name=self.model_name)
            self.model = replace(model, base_url=self.base_url,
                                 api_key=secrets.rag_embedding_api_key or model.api_key)
        return self


chat_config = ChatAgentConfig()
route_config = RouteAgentConfig()
plan_config = PlanAgentConfig()
executor_config = ExecutorAgentConfig()
summary_config = SummaryAgentConfig()
image_config = ImageAgentConfig()
embedding_config = EmbeddingConfig()
