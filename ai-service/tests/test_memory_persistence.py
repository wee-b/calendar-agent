"""只使用 HistoryMysqlCase 自动创建的随机测试库。"""
from unittest.mock import patch
from sqlalchemy import select
from app.core.config.common.memory import MemorySettings
from app.models.ai_session import AiSession
from app.repository.memory import MemoryRepository
from app.repository.context_summary import ContextSummaryRepository
from app.schemas.memory import MemoryChange
from app.schemas.chat.history import ChatMessageCreate
from tests.test_chat_history import HistoryMysqlCase


class MemoryPersistenceTests(HistoryMysqlCase):
    async def seed(self, count=50):
        return await self.repository.append_messages(23, 's', [
            ChatMessageCreate(role='user' if i % 2 == 0 else 'assistant', content=f'消息{i}')
            for i in range(count)])

    async def count(self):
        async with self.repository.sessions() as session:
            return await session.scalar(select(AiSession.message_count).where(AiSession.user_id == 23, AiSession.session_id == 's'))

    async def test_49_skip_50_compress_keep_20_and_append_to_22(self):
        repo = ContextSummaryRepository(self.engine)
        settings = MemorySettings(_env_file=None)
        await self.seed(49)
        self.assertIsNone(await repo.snapshot(23, 's', settings))
        await self.repository.append_messages(23, 's', [ChatMessageCreate(role='assistant', content='第50条')])
        snapshot = await repo.snapshot(23, 's', settings)
        self.assertEqual((30, 20), (len(snapshot.rows), len(snapshot.retained_ids)))
        preference = (snapshot.rows[0].dialogue_id, MemoryChange('study_time', '偏好晚间', 'PREFERENCE_TIME'))
        self.assertTrue(await repo.save(23, 's', snapshot, '旧消息摘要', [preference]))
        self.assertEqual(20, await self.count())
        self.assertEqual(1, len(await MemoryRepository(self.engine).list_active(23)))
        self.assertFalse(await repo.save(23, 's', snapshot, '过时摘要'))
        self.assertEqual(50, len((await self.repository.list_history(23, 's', limit=100))[0]))
        self.assertEqual(21, len(await self.repository.recent_messages(23, 's')))
        await self.repository.save_round(23, 's', '新输入', '新回复', 1)
        self.assertEqual(22, await self.count())
        self.assertIsNone(await repo.snapshot(23, 's', settings))
        self.assertIsNone(await repo.snapshot(99, 's', settings))

    async def test_append_does_not_extract_preferences(self):
        await self.repository.save_round(23, 's', '我习惯晚上学习', '了解', 1)
        self.assertEqual([], await MemoryRepository(self.engine).list_active(23))

    async def test_failed_memory_write_rolls_back_summary_and_count(self):
        await self.seed()
        repo = ContextSummaryRepository(self.engine)
        snapshot = await repo.snapshot(23, 's', MemorySettings())
        with patch.object(MemoryRepository, 'apply', side_effect=RuntimeError('rollback')):
            with self.assertRaises(RuntimeError):
                await repo.save(23, 's', snapshot, '摘要', [(1, MemoryChange('key', '内容', 'PREFERENCE_STYLE'))])
        self.assertEqual(50, await self.count())
        async with repo.sessions() as session:
            self.assertIsNone(await repo.latest(session, 23, 's'))

    async def test_deleted_or_changed_session_rejects_snapshot(self):
        await self.seed()
        repo = ContextSummaryRepository(self.engine)
        snapshot = await repo.snapshot(23, 's', MemorySettings())
        await self.repository.save_round(23, 's', '新输入', '新回复', 1)
        self.assertFalse(await repo.save(23, 's', snapshot, '旧摘要'))
        snapshot = await repo.snapshot(23, 's', MemorySettings())
        await self.repository.delete_session(23, 's')
        self.assertFalse(await repo.save(23, 's', snapshot, '晚到摘要'))
