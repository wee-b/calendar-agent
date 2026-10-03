from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.config.common.settings import PUBLIC_ENV_FILE, SECRET_ENV_FILE


class DocumentSettings(BaseSettings):
    minio_endpoint: str = "localhost:9000"
    minio_secure: bool = False
    minio_bucket: str = "calendar-documents"
    qdrant_document_collection: str = "user_documents"
    document_max_bytes: int = 10 * 1024 * 1024

    model_config = SettingsConfigDict(env_file=PUBLIC_ENV_FILE, extra="ignore")


class MinioSecrets(BaseSettings):
    minio_access_key: str | None = None
    minio_secret_key: str | None = None

    model_config = SettingsConfigDict(env_file=SECRET_ENV_FILE, extra="ignore", repr=False)


@lru_cache
def get_document_settings() -> DocumentSettings:
    return DocumentSettings()


@lru_cache
def get_minio_secrets() -> MinioSecrets:
    return MinioSecrets()
