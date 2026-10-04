"""保存图片模型返回的原图，并提供稳定的签名访问地址。"""

import asyncio
from dataclasses import dataclass
from hashlib import sha256
import hmac
from io import BytesIO
import logging
from pathlib import PurePosixPath
from urllib.parse import quote, urlsplit
from uuid import uuid4

import httpx
from fastapi import HTTPException
from minio import Minio

from app.core.config.common.documents import get_document_settings, get_minio_secrets
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException
from app.repository.documents import DocumentRepository


logger = logging.getLogger(__name__)
MAX_IMAGE_BYTES = 20 * 1024 * 1024
IMAGE_TYPES = {"jpg": "image/jpeg", "png": "image/png", "webp": "image/webp"}


def _image_extension(data: bytes) -> str | None:
    if data.startswith(b"\xff\xd8\xff"):
        return "jpg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "webp"
    return None


@dataclass(frozen=True)
class StoredImage:
    minio_uri: str
    view_url: str
    download_url: str


class ImageStorageService:
    def __init__(self, *, download_client: httpx.AsyncClient | None = None,
                 minio_client: Minio | None = None, repository=None):
        self.settings = get_document_settings()
        self.download_client = download_client
        self.minio_client = minio_client
        self._repository = repository

    @property
    def repository(self):
        if self._repository is None:
            self._repository = DocumentRepository()
        return self._repository

    def _minio(self) -> Minio:
        if self.minio_client is not None:
            return self.minio_client
        secrets = get_minio_secrets()
        if not secrets.minio_access_key or not secrets.minio_secret_key:
            raise RuntimeError("MinIO 连接密钥未配置")
        return Minio(self.settings.minio_endpoint, access_key=secrets.minio_access_key,
                     secret_key=secrets.minio_secret_key, secure=self.settings.minio_secure)

    @staticmethod
    def _signature(key: str) -> str:
        secret = get_minio_secrets().minio_secret_key
        if not secret:
            raise RuntimeError("MinIO 签名密钥未配置")
        return hmac.new(secret.encode(), key.encode(), sha256).hexdigest()

    def _image_urls(self, key: str) -> StoredImage:
        path = f"/images/{quote(key, safe='/')}?sig={self._signature(key)}"
        return StoredImage(minio_uri=f"minio://{self.settings.image_minio_bucket}/{key}",
                           view_url=path, download_url=path + "&download=true")

    @staticmethod
    async def _download(client: httpx.AsyncClient, url: str) -> tuple[bytes, str]:
        parts = urlsplit(url)
        if parts.scheme != "https" or not parts.netloc or parts.username or parts.password:
            raise ValueError("图片模型返回的下载地址无效")
        async with client.stream("GET", url) as response:
            response.raise_for_status()
            if int(response.headers.get("content-length", "0")) > MAX_IMAGE_BYTES:
                raise ValueError("图片超过 20 MB")
            chunks = bytearray()
            async for chunk in response.aiter_bytes():
                chunks.extend(chunk)
                if len(chunks) > MAX_IMAGE_BYTES:
                    raise ValueError("图片超过 20 MB")
        extension = _image_extension(chunks)
        if extension is None:
            raise ValueError("图片模型未返回有效的 JPEG、PNG 或 WebP 原图")
        return bytes(chunks), extension

    def _put_object(self, key: str, data: bytes, content_type: str) -> None:
        client = self._minio()
        bucket = self.settings.image_minio_bucket
        if not client.bucket_exists(bucket):
            try:
                client.make_bucket(bucket)
            except Exception:
                if not client.bucket_exists(bucket):
                    raise
        client.put_object(bucket, key, BytesIO(data), len(data), content_type=content_type)

    async def archive(self, url: str, user_id: int, session_id: str, draft_id: int) -> StoredImage:
        client = self.download_client or httpx.AsyncClient(timeout=45, follow_redirects=True)
        entry = None
        upload_claimed = False
        try:
            data, extension = await self._download(client, url)
            session_key = sha256(session_id.encode()).hexdigest()[:16]
            key = f"users/{user_id}/sessions/{session_key}/drafts/{draft_id}/{uuid4().hex}.{extension}"
            entry, created = await self.repository.create_or_get(
                user_id=user_id, name=f"plan-{draft_id}.{extension}",
                content_type=IMAGE_TYPES[extension], size=len(data), digest=sha256(data).hexdigest(),
                object_key=key, object_bucket=self.settings.image_minio_bucket,
                file_kind="image", deduplicate=False)
            upload_claimed = created
            if not created:
                raise RuntimeError("图片文件记录创建失败")
            image = self._image_urls(entry.object_key)
            await asyncio.to_thread(self._put_object, entry.object_key, data, IMAGE_TYPES[extension])
            await self.repository.finish_upload(entry.file_id)
            upload_claimed = False
            logger.info("图片原图已保存 | 用户=%s 草稿=%s 文件ID=%s MinIO=%s 大小=%s",
                        user_id, draft_id, entry.file_id, image.minio_uri, len(data))
            return image
        except Exception as exc:
            if upload_claimed and entry is not None:
                try:
                    await self.repository.fail_upload(entry.file_id, str(exc))
                except Exception:
                    logger.exception("图片文件状态更新失败 | 文件ID=%s", entry.file_id)
            logger.exception("图片原图下载或保存失败 | 用户=%s 草稿=%s", user_id, draft_id)
            raise BusinessException(ErrorCode.IMAGE_STORAGE_FAILED) from exc
        finally:
            if self.download_client is None:
                await client.aclose()

    async def read(self, key: str, signature: str) -> tuple[bytes, str]:
        if (not key.startswith("users/") or ".." in PurePosixPath(key).parts
                or not hmac.compare_digest(signature, self._signature(key))):
            raise HTTPException(403, "图片访问地址无效")

        def load() -> bytes:
            response = self._minio().get_object(self.settings.image_minio_bucket, key)
            try:
                data = response.read(MAX_IMAGE_BYTES + 1)
                if len(data) > MAX_IMAGE_BYTES:
                    raise ValueError("图片超过大小限制")
                return data
            finally:
                response.close()
                response.release_conn()

        try:
            data = await asyncio.to_thread(load)
        except Exception as exc:
            logger.warning("MinIO 图片读取失败 | key=%s", key, exc_info=True)
            raise HTTPException(404, "图片不存在") from exc
        extension = _image_extension(data)
        if extension is None:
            raise HTTPException(404, "图片内容无效")
        return data, IMAGE_TYPES[extension]
