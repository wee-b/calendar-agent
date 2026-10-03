"""用户文件解析、MinIO 归档、切片去重和独立向量索引。"""

import asyncio
from hashlib import sha256
from io import BytesIO
import logging
from pathlib import Path
import re
from time import perf_counter
from uuid import uuid4

from fastapi import HTTPException
from minio import Minio
from pypdf import PdfReader
from docx import Document

from app.cache.embedding_cache import CachedEmbedding
from app.core.config.common.documents import get_document_settings, get_minio_secrets
from app.core.config.common.rag import get_rag_settings
from app.core.flow_logging import text_preview
from app.db.redis_client import get_redis
from app.helper.embedding_client import EmbeddingClient
from app.models.document import DocumentChunk, UserFile
from app.repository.documents import DocumentRepository
from app.repository.qdrant import QdrantRepository
from app.schemas.rag import CorpusChunk, QdrantPayload, QdrantUpsertPoint
from app.service.rag_corpus import _split, deduplicate

ALLOWED_EXTENSIONS = {".txt", ".md", ".pdf", ".docx"}
logger = logging.getLogger(__name__)


def extract_chunks(name: str, data: bytes) -> list[CorpusChunk]:
    extension = Path(name).suffix.lower()
    if extension not in ALLOWED_EXTENSIONS:
        raise HTTPException(400, "仅支持 TXT、Markdown、PDF 和 DOCX 文件")
    try:
        if extension == ".pdf":
            reader = PdfReader(BytesIO(data))
            if len(reader.pages) > 100:
                raise HTTPException(400, "PDF 最多支持 100 页")
            sections = [(f"第 {index + 1} 页", page.extract_text() or "")
                        for index, page in enumerate(reader.pages)]
        elif extension == ".docx":
            document = Document(BytesIO(data))
            paragraphs = [p.text for p in document.paragraphs]
            table_lines = [" | ".join(cell.text for cell in row.cells)
                           for table in document.tables for row in table.rows]
            sections = [("正文", "\n".join(paragraphs + table_lines))]
        else:
            try:
                text = data.decode("utf-8-sig")
            except UnicodeDecodeError:
                text = data.decode("gb18030")
            sections = [("正文", text)]
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(400, "文档无法解析或文本编码不受支持") from exc
    chunks: list[CorpusChunk] = []
    for section, body in sections:
        if extension == ".md":
            body = re.sub(r"\A---\s*\n.*?\n---\s*\n", "", body, flags=re.S)
            current_section, lines = "正文", []
            markdown_sections = []
            for line in body.splitlines():
                heading = re.match(r"^#{1,6}\s+(.+)$", line)
                if heading:
                    if lines:
                        markdown_sections.append((current_section, "\n".join(lines)))
                    current_section, lines = heading.group(1).strip(), []
                else:
                    lines.append(line)
            if lines:
                markdown_sections.append((current_section, "\n".join(lines)))
        else:
            markdown_sections = [(section, body)]
        for title, content in markdown_sections:
            normalized = re.sub(r"[ \t]+", " ", content).strip()
            for paragraph in re.split(r"\n\s*\n", normalized):
                for piece in _split(paragraph.strip(), 500):
                    for start in range(0, len(piece), 500):
                        text = piece[start:start + 500].strip()
                        if len(text) >= 10:
                            chunks.append(CorpusChunk(name, title[:500], text))
    if not chunks:
        raise HTTPException(400, "文档没有可索引的文本内容")
    if len(chunks) > 500:
        raise HTTPException(400, "文档切片超过 500 个，请拆分文件后上传")
    return deduplicate(chunks)


def file_result(entry: UserFile) -> dict:
    return {"fileId": entry.file_id, "fileName": entry.file_name,
            "sizeBytes": entry.size_bytes, "status": entry.status,
            "errorMessage": entry.error_message,
            "createTime": entry.create_time.isoformat() if entry.create_time else None}


class DocumentService:
    """共享用户文档仓储与 MinIO 连接，不执行上传或解析。"""
    def __init__(self, repository=None, qdrant=None, embedding=None, minio_client=None):
        self.settings = get_document_settings()
        self.repository = repository or DocumentRepository()
        self._qdrant = qdrant
        self._embedding = embedding
        self.minio_client = minio_client

    @property
    def qdrant(self):
        if self._qdrant is None:
            self._qdrant = QdrantRepository(collection=self.settings.qdrant_document_collection)
        return self._qdrant

    @property
    def embedding(self):
        if self._embedding is None:
            self._embedding = CachedEmbedding(EmbeddingClient(), get_redis())
        return self._embedding

    def _minio(self):
        if self.minio_client is not None:
            return self.minio_client
        secrets = get_minio_secrets()
        if not secrets.minio_access_key or not secrets.minio_secret_key:
            raise RuntimeError("MINIO_ACCESS_KEY 和 MINIO_SECRET_KEY 未配置")
        return Minio(self.settings.minio_endpoint, access_key=secrets.minio_access_key,
                     secret_key=secrets.minio_secret_key, secure=self.settings.minio_secure)

    def _put_object(self, key: str, data: bytes, content_type: str) -> None:
        client = self._minio()
        bucket = self.settings.minio_bucket
        if not client.bucket_exists(bucket):
            try:
                client.make_bucket(bucket)
            except Exception:
                if not client.bucket_exists(bucket):
                    raise
        client.put_object(bucket, key, BytesIO(data), len(data), content_type=content_type)

    def _get_object(self, key: str) -> bytes:
        response = self._minio().get_object(self.settings.minio_bucket, key)
        try:
            return response.read(self.settings.document_max_bytes + 1)
        finally:
            response.close()
            response.release_conn()


class DocumentUploadService(DocumentService):
    """只保存原文件和文件表记录。"""

    async def upload(self, user_id: int, name: str, data: bytes, content_type: str) -> dict:
        name = Path(name).name[:255]
        content_type = (content_type or "application/octet-stream")[:100]
        if Path(name).suffix.lower() not in ALLOWED_EXTENSIONS:
            raise HTTPException(400, "仅支持 TXT、Markdown、PDF 和 DOCX 文件")
        if not data or len(data) > self.settings.document_max_bytes:
            raise HTTPException(400, "文件大小必须在 1 字节到 10 MB 之间")
        try:
            self._minio()
        except RuntimeError as exc:
            raise HTTPException(503, str(exc)) from exc
        digest = sha256(data).hexdigest()
        key = f"users/{user_id}/{uuid4().hex}/{name}"
        entry, created = await self.repository.create_or_get(
            user_id=user_id, name=name, content_type=content_type,
            size=len(data), digest=digest, object_key=key)
        if not created:
            if entry.status in {"uploaded", "processing", "ready", "failed"}:
                return file_result(entry)
            if entry.status != "upload_failed" or not await self.repository.retry_upload(entry.file_id):
                raise HTTPException(409, "相同文件正在上传")
            key = entry.object_key
        try:
            await asyncio.to_thread(self._put_object, key, data, content_type)
            await self.repository.finish_upload(entry.file_id)
            entry.status = "uploaded"
            entry.error_message = None
            return file_result(entry)
        except Exception as exc:
            await self.repository.fail_upload(entry.file_id, str(exc))
            raise


class DocumentParseService(DocumentService):
    """从 MinIO 读取已上传文件，解析并建立文档索引。"""

    async def parse(self, user_id: int, file_id: int, *, force: bool = False) -> dict:
        entry, claimed, previous_status = await self.repository.claim_parse(
            user_id, file_id, force=force)
        if entry is None:
            raise HTTPException(404, "文档不存在")
        if not claimed:
            if entry.status == "ready" and not force:
                return file_result(entry)
            raise HTTPException(409, "文档尚未上传完成或正在解析")
        destructive_started = False
        try:
            data = await asyncio.to_thread(self._get_object, entry.object_key)
            if len(data) != entry.size_bytes or sha256(data).hexdigest() != entry.sha256:
                raise ValueError("MinIO 文件内容与登记记录不一致")
            chunks = await asyncio.to_thread(extract_chunks, entry.file_name, data)
            first = await self.embedding.embed(chunks[0].text)
            info = await self.qdrant.collection_info()
            if info is None:
                await self.qdrant.create_collection(len(first))
            elif info.config.params.vectors.size != len(first):
                raise ValueError("文档向量集合维度与当前 Embedding 模型不一致")
            # 全部片段向量化成功后才替换旧索引，降低重新解析失败时的数据损失。
            destructive_started = True
            await self.qdrant.delete_file(user_id, entry.file_id)
            await self.repository.clear_chunks(entry.file_id)
            points, records = [], []
            semaphore = asyncio.Semaphore(4)

            async def embed_chunk(text: str) -> list[float]:
                async with semaphore:
                    return await self.embedding.embed(text)

            vectors = [first]
            for start in range(1, len(chunks), 32):
                vectors.extend(await asyncio.gather(*(embed_chunk(chunk.text)
                    for chunk in chunks[start:start + 32])))
            for index, chunk in enumerate(chunks):
                vector = vectors[index]
                if len(vector) != len(first):
                    raise ValueError("Embedding 向量维度不一致")
                point_id = str(uuid4())
                points.append(QdrantUpsertPoint(id=point_id, vector=vector,
                    payload=QdrantPayload(source=entry.file_name, section=chunk.section, text=chunk.text,
                                          char_count=len(chunk.text), user_id=user_id, file_id=entry.file_id)))
                records.append(DocumentChunk(file_id=entry.file_id, user_id=user_id,
                    chunk_index=index, section=chunk.section, content=chunk.text,
                    content_hash=sha256(chunk.text.encode()).hexdigest(), vector_id=point_id))
            for start in range(0, len(points), 32):
                await self.qdrant.upsert(points[start:start + 32])
            await self.repository.finish_parse(entry.file_id, records)
            entry.status = "ready"
            entry.error_message = None
            return file_result(entry)
        except Exception as exc:
            if destructive_started or previous_status != "ready":
                try:
                    await self.qdrant.delete_file(user_id, entry.file_id)
                except Exception:
                    logger.warning("解析失败的文档向量清理失败: file_id=%s", entry.file_id, exc_info=True)
            await self.repository.fail(entry.file_id, str(exc),
                                       restore_ready=previous_status == "ready" and not destructive_started)
            raise


class DocumentManagementService(DocumentService):
    """分别清除解析结果或原文件及其全部索引。"""

    async def delete_parse(self, user_id: int, file_id: int) -> dict:
        entry, claimed = await self.repository.claim_delete_parse(user_id, file_id)
        if entry is None:
            raise HTTPException(404, "文档不存在")
        if not claimed:
            if entry.status == "uploaded":
                return file_result(entry)
            raise HTTPException(409, "文档正在处理，暂不能删除解析结果")
        try:
            if await self.qdrant.collection_info() is not None:
                await self.qdrant.delete_file(user_id, file_id)
            await self.repository.complete_delete_parse(file_id)
            entry.status = "uploaded"
            entry.error_message = None
            return file_result(entry)
        except Exception as exc:
            await self.repository.fail_delete(file_id, str(exc), source=False)
            raise

    async def delete_source(self, user_id: int, file_id: int) -> None:
        entry, claimed = await self.repository.claim_delete_source(user_id, file_id)
        if entry is None:
            raise HTTPException(404, "文档不存在")
        if not claimed:
            raise HTTPException(409, "文档正在处理，暂不能删除源文件")
        try:
            if await self.qdrant.collection_info() is not None:
                await self.qdrant.delete_file(user_id, file_id)
            await asyncio.to_thread(self._minio().remove_object,
                                    self.settings.minio_bucket, entry.object_key)
            await self.repository.complete_delete_source(file_id)
        except Exception as exc:
            await self.repository.fail_delete(file_id, str(exc), source=True)
            raise


class DocumentSearchService(DocumentService):
    """按用户和已就绪文件限制规划节点的检索范围。"""

    async def search(self, user_id: int, ids: list[int], query: str):
        started = perf_counter()
        unique_ids = list(dict.fromkeys(ids))
        top_k = get_rag_settings().rag_top_k
        logger.info("文档 RAG 查询开始 | 用户=%s 集合=%s 文件ID=%s top_k=%s 查询长度=%s 查询=%r",
                    user_id, get_document_settings().qdrant_document_collection,
                    unique_ids, top_k, len(query), text_preview(query, 160))
        files = await self.repository.ready_files(user_id, unique_ids)
        if len(files) != len(unique_ids):
            logger.warning("文档 RAG 查询拒绝 | 用户=%s 文件ID=%s 可用文件数=%s",
                           user_id, unique_ids, len(files))
            raise HTTPException(400, "引用的文档不存在、未解析完成或无权访问")
        try:
            vector = await self.embedding.embed(query)
            hits = await self.qdrant.search_documents(vector, user_id, unique_ids, top_k)
        except Exception:
            logger.exception("文档 RAG 查询失败 | 用户=%s 文件ID=%s 耗时=%.3f秒",
                             user_id, unique_ids, perf_counter() - started)
            raise
        results = [hit for hit in hits if hit.payload.user_id == user_id
                   and hit.payload.file_id in unique_ids]
        logger.info("文档 RAG 查询完成 | 用户=%s 文件ID=%s 向量维度=%s 候选数=%s 命中数=%s 耗时=%.3f秒",
                    user_id, unique_ids, len(vector), len(hits), len(results), perf_counter() - started)
        for rank, hit in enumerate(results, 1):
            logger.info("文档 RAG 命中 | 排名=%s 点ID=%s 文件ID=%s 相似度=%.4f 来源=%r 章节=%r 内容长度=%s 内容=%r",
                        rank, hit.id, hit.payload.file_id, hit.score, hit.payload.source,
                        hit.payload.section, len(hit.payload.text), text_preview(hit.payload.text, 160))
        return results
