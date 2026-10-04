from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError

from app.db.session import get_session_factory
from app.models.document import DocumentChunk, UserFile


class DocumentRepository:
    def __init__(self, sessions=None):
        self.sessions = sessions or get_session_factory()

    async def list_files(self, user_id: int) -> list[UserFile]:
        async with self.sessions() as session:
            result = await session.scalars(select(UserFile).where(
                UserFile.user_id == user_id, UserFile.file_kind == "document"
            ).order_by(UserFile.file_id.desc()))
            return list(result)

    async def ready_files(self, user_id: int, ids: list[int]) -> list[UserFile]:
        if not ids:
            return []
        async with self.sessions() as session:
            result = await session.scalars(select(UserFile).where(
                UserFile.user_id == user_id, UserFile.file_id.in_(ids),
                UserFile.status == "ready", UserFile.file_kind == "document"
            ))
            return list(result)

    async def create_or_get(self, *, user_id: int, name: str, content_type: str,
                            size: int, digest: str, object_key: str,
                            object_bucket: str, file_kind: str = "document",
                            deduplicate: bool = True) -> tuple[UserFile, bool]:
        async with self.sessions() as session:
            if deduplicate:
                existing = await session.scalar(select(UserFile).where(
                    UserFile.user_id == user_id, UserFile.file_kind == file_kind,
                    UserFile.dedupe_key == digest))
                if existing:
                    return existing, False
            entry = UserFile(user_id=user_id, file_kind=file_kind, file_name=name, content_type=content_type,
                             size_bytes=size, sha256=digest,
                             dedupe_key=digest if deduplicate else None, object_key=object_key,
                             object_bucket=object_bucket, status="uploading")
            try:
                session.add(entry)
                await session.commit()
                await session.refresh(entry)
                return entry, True
            except IntegrityError:
                await session.rollback()
                if not deduplicate:
                    raise
                existing = await session.scalar(select(UserFile).where(
                    UserFile.user_id == user_id, UserFile.file_kind == file_kind,
                    UserFile.dedupe_key == digest))
                if existing is None:
                    raise
                return existing, False

    async def finish_upload(self, file_id: int) -> None:
        async with self.sessions() as session:
            async with session.begin():
                entry = await session.get(UserFile, file_id, with_for_update=True)
                entry.status = "uploaded"
                entry.error_message = None

    async def fail_upload(self, file_id: int, message: str) -> None:
        async with self.sessions() as session:
            async with session.begin():
                entry = await session.get(UserFile, file_id, with_for_update=True)
                entry.status = "upload_failed"
                entry.error_message = message[:500]

    async def retry_upload(self, file_id: int) -> bool:
        async with self.sessions() as session:
            async with session.begin():
                entry = await session.get(UserFile, file_id, with_for_update=True)
                if entry.status != "upload_failed":
                    return False
                entry.status = "uploading"
                entry.error_message = None
                return True

    async def claim_parse(self, user_id: int, file_id: int,
                          *, force: bool = False) -> tuple[UserFile | None, bool, str | None]:
        async with self.sessions() as session:
            async with session.begin():
                entry = await session.scalar(select(UserFile).where(
                    UserFile.user_id == user_id, UserFile.file_id == file_id,
                    UserFile.file_kind == "document").with_for_update())
                if entry is None:
                    return None, False, None
                previous_status = entry.status
                if entry.status in {"uploaded", "failed"} or (force and entry.status == "ready"):
                    entry.status = "processing"
                    entry.error_message = None
                    return entry, True, previous_status
                return entry, False, previous_status

    async def clear_chunks(self, file_id: int) -> None:
        async with self.sessions() as session:
            async with session.begin():
                await session.execute(delete(DocumentChunk).where(DocumentChunk.file_id == file_id))

    async def finish_parse(self, file_id: int, chunks: list[DocumentChunk]) -> None:
        async with self.sessions() as session:
            async with session.begin():
                entry = await session.get(UserFile, file_id, with_for_update=True)
                session.add_all(chunks)
                entry.status = "ready"
                entry.error_message = None

    async def fail(self, file_id: int, message: str, *, restore_ready: bool = False) -> None:
        async with self.sessions() as session:
            async with session.begin():
                entry = await session.get(UserFile, file_id, with_for_update=True)
                entry.status = "ready" if restore_ready else "failed"
                entry.error_message = message[:500]

    async def claim_delete_parse(self, user_id: int, file_id: int) -> tuple[UserFile | None, bool]:
        async with self.sessions() as session:
            async with session.begin():
                entry = await session.scalar(select(UserFile).where(
                    UserFile.user_id == user_id, UserFile.file_id == file_id,
                    UserFile.file_kind == "document").with_for_update())
                if entry is None:
                    return None, False
                if entry.status in {"ready", "failed"}:
                    entry.status = "deleting_parse"
                    return entry, True
                return entry, False

    async def complete_delete_parse(self, file_id: int) -> None:
        async with self.sessions() as session:
            async with session.begin():
                entry = await session.get(UserFile, file_id, with_for_update=True)
                await session.execute(delete(DocumentChunk).where(DocumentChunk.file_id == file_id))
                entry.status = "uploaded"
                entry.error_message = None

    async def claim_delete_source(self, user_id: int, file_id: int) -> tuple[UserFile | None, bool]:
        async with self.sessions() as session:
            async with session.begin():
                entry = await session.scalar(select(UserFile).where(
                    UserFile.user_id == user_id, UserFile.file_id == file_id,
                    UserFile.file_kind == "document").with_for_update())
                if entry is None:
                    return None, False
                if entry.status in {"uploaded", "ready", "failed", "upload_failed", "delete_failed"}:
                    entry.status = "deleting_source"
                    return entry, True
                return entry, False

    async def complete_delete_source(self, file_id: int) -> None:
        async with self.sessions() as session:
            async with session.begin():
                await session.execute(delete(DocumentChunk).where(DocumentChunk.file_id == file_id))
                await session.execute(delete(UserFile).where(UserFile.file_id == file_id))

    async def fail_delete(self, file_id: int, message: str, *, source: bool) -> None:
        async with self.sessions() as session:
            async with session.begin():
                entry = await session.get(UserFile, file_id, with_for_update=True)
                entry.status = "delete_failed" if source else "failed"
                entry.error_message = message[:500]

