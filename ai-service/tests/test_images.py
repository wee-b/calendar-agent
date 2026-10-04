"""图片生成结果归档与稳定访问地址。"""

from io import BytesIO
from hashlib import sha256
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, unquote, urlsplit

import httpx
from fastapi import HTTPException

from app.api.routers.image_router import get_image
from app.main import app
from app.core.exception.exceptions import BusinessException
from app.service.images import ImageStorageService


class FakeMinio:
    def __init__(self, events=None):
        self.buckets = set()
        self.objects = {}
        self.events = events if events is not None else []

    def bucket_exists(self, bucket):
        return bucket in self.buckets

    def make_bucket(self, bucket):
        self.buckets.add(bucket)

    def put_object(self, bucket, key, stream, size, content_type):
        self.events.append("minio_upload")
        self.objects[(bucket, key)] = (stream.read(), content_type)

    def get_object(self, bucket, key):
        data = self.objects[(bucket, key)][0]
        stream = BytesIO(data)
        stream.release_conn = lambda: None
        return stream


class FakeFileRepository:
    def __init__(self, events=None):
        self.events = events if events is not None else []
        self.created = None
        self.status = None

    async def create_or_get(self, **kwargs):
        self.events.append("file_insert")
        self.created = kwargs
        return SimpleNamespace(file_id=88, object_key=kwargs["object_key"]), True

    async def finish_upload(self, file_id):
        self.events.append("file_uploaded")
        self.status = "uploaded"

    async def fail_upload(self, file_id, message):
        self.events.append("file_failed")
        self.status = "upload_failed"


class ImageStorageTests(unittest.IsolatedAsyncioTestCase):
    async def test_downloads_original_bytes_and_serves_signed_minio_object(self):
        original = b"\xff\xd8\xff" + b"original-image" * 100
        events = []
        minio = FakeMinio(events)
        repository = FakeFileRepository(events)

        def handle(request):
            self.assertEqual("https://provider.example/original", str(request.url))
            return httpx.Response(200, content=original, headers={"Content-Type": "image/jpeg"})

        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            service = ImageStorageService(download_client=client, minio_client=minio,
                                          repository=repository)
            with patch("app.service.images.get_minio_secrets", return_value=SimpleNamespace(minio_secret_key="test-secret")):
                image = await service.archive("https://provider.example/original", 7, "session-1", 8)
                parts = urlsplit(image.view_url)
                key = unquote(parts.path.removeprefix("/images/"))
                sig = parse_qs(parts.query)["sig"][0]
                self.assertEqual(original, minio.objects[(service.settings.image_minio_bucket, key)][0])
                self.assertEqual("image/jpeg", minio.objects[(service.settings.image_minio_bucket, key)][1])
                self.assertEqual(["file_insert", "minio_upload", "file_uploaded"], events)
                self.assertEqual("image", repository.created["file_kind"])
                self.assertEqual(False, repository.created["deduplicate"])
                self.assertEqual(service.settings.image_minio_bucket, repository.created["object_bucket"])
                self.assertEqual(repository.created["digest"], sha256(original).hexdigest())
                self.assertEqual(f"minio://{service.settings.image_minio_bucket}/{key}", image.minio_uri)
                self.assertIn("download=true", image.download_url)
                with patch("app.api.routers.image_router.ImageStorageService", return_value=service):
                    response = await get_image(key, sig, True)
                    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                                 base_url="http://test") as browser:
                        image_response = await browser.get(parts.path, params={"sig": sig})
                self.assertEqual(original, response.body)
                self.assertIn("attachment", response.headers["content-disposition"])
                self.assertEqual(200, image_response.status_code)
                self.assertEqual(original, image_response.content)
                self.assertEqual("image/jpeg", image_response.headers["content-type"])
                with self.assertRaises(HTTPException) as invalid:
                    await service.read(key, "wrong-signature")
                self.assertEqual(403, invalid.exception.status_code)

    async def test_rejects_non_image_download_before_upload(self):
        minio = FakeMinio()
        repository = FakeFileRepository()
        async with httpx.AsyncClient(transport=httpx.MockTransport(
                lambda _: httpx.Response(200, content=b"not an image"))) as client:
            service = ImageStorageService(download_client=client, minio_client=minio,
                                          repository=repository)
            with self.assertRaises(BusinessException):
                await service.archive("https://provider.example/file", 7, "session-1", 8)
        self.assertFalse(minio.objects)
        self.assertIsNone(repository.created)

    async def test_upload_failure_marks_file_record_failed(self):
        class FailingMinio(FakeMinio):
            def put_object(self, *args, **kwargs):
                raise RuntimeError("storage unavailable")

        repository = FakeFileRepository()
        image = b"\xff\xd8\xffimage-data"
        async with httpx.AsyncClient(transport=httpx.MockTransport(
                lambda _: httpx.Response(200, content=image))) as client:
            service = ImageStorageService(download_client=client, minio_client=FailingMinio(),
                                          repository=repository)
            with patch("app.service.images.get_minio_secrets", return_value=SimpleNamespace(minio_secret_key="test-secret")):
                with self.assertRaises(BusinessException):
                    await service.archive("https://provider.example/file", 7, "session-1", 8)
        self.assertEqual("upload_failed", repository.status)

    async def test_file_insert_failure_prevents_minio_upload(self):
        class FailingRepository(FakeFileRepository):
            async def create_or_get(self, **kwargs):
                raise RuntimeError("database unavailable")

        minio = FakeMinio()
        image = b"\xff\xd8\xffimage-data"
        async with httpx.AsyncClient(transport=httpx.MockTransport(
                lambda _: httpx.Response(200, content=image))) as client:
            service = ImageStorageService(download_client=client, minio_client=minio,
                                          repository=FailingRepository())
            with self.assertRaises(BusinessException):
                await service.archive("https://provider.example/file", 7, "session-1", 8)
        self.assertFalse(minio.objects)
