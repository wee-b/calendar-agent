"""厂商连接和密钥；通过 Deepseek(name=...)、Aliyun(name=...) 选择模型。"""

from dataclasses import dataclass, field

from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.config.common.settings import PUBLIC_ENV_FILE, SECRET_ENV_FILE


class SecretSettings(BaseSettings):
    aliyun_api_key: str | None = None
    deepseek_api_key: str | None = None
    rag_embedding_api_key: str | None = None
    ark_api_key: str | None = None

    model_config = SettingsConfigDict(env_file=SECRET_ENV_FILE,
                                      env_file_encoding="utf-8", extra="ignore")


secrets = SecretSettings()


@dataclass(frozen=True)
class ModelConfig:
    provider: str
    name: str
    base_url: str
    api_key: str | None = field(repr=False)
    api_type: str = "openai-compatible"


class ProviderConfig(BaseSettings):
    provider: str
    base_url: str
    model_name: str
    api_type: str = "openai-compatible"

    model_config = SettingsConfigDict(env_file=PUBLIC_ENV_FILE,
                                      env_file_encoding="utf-8", extra="ignore", frozen=True)

    def __call__(self, *, name: str | None = None) -> ModelConfig:
        return ModelConfig(
            provider=self.provider, name=name if name is not None else self.model_name,
            base_url=self.base_url, api_key=getattr(secrets, f"{self.provider}_api_key"),
            api_type=self.api_type,
        )


class DeepseekConfig(ProviderConfig):
    provider: str = "deepseek"
    base_url: str = "https://api.deepseek.com"
    model_name: str = "deepseek-flash"
    model_config = SettingsConfigDict(env_prefix="DEEPSEEK_")


class AliyunConfig(ProviderConfig):
    provider: str = "aliyun"
    base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    model_name: str = "qwen3.7-plus"
    model_config = SettingsConfigDict(env_prefix="ALIYUN_")


class ArkConfig(ProviderConfig):
    provider: str = "ark"
    base_url: str = "https://ark.cn-beijing.volces.com/api/v3"
    model_name: str = "doubao-seedream-5-0-flash-260915"
    api_type: str = "image-generation"
    model_config = SettingsConfigDict(env_prefix="ARK_")


Deepseek = DeepseekConfig()
Aliyun = AliyunConfig()
Ark = ArkConfig()
providers = {"deepseek": Deepseek, "aliyun": Aliyun, "ark": Ark}
