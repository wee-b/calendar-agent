"""历史接口与真实 MySQL 事务测试；MySQL 测试只操作自动创建的临时数据库。"""

import asyncio
import os
import re
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch
from uuid import uuid4

import httpx
from fastapi import HTTPException
from sqlalchemy import event, func, select, text, update
from sqlalchemy.engine import make_url
from sqlalchemy.exc import DataError, DBAPIError
from sqlalchemy.ext.asyncio import create_async_engine

from app.core.exception.exceptions import BusinessException
from app.main import app
from app.models.ai_dialogue import AiDialogue, Base
from app.models.ai_session import AiSession
from app.repository.chat import ChatRepository
from app.repository.chat_session import ChatSessionRepository
from app.schemas.chat.history import ChatMessageCreate
from app.schemas.chat.model_stream import AssistantMessage
from app.service.chat import ChatService


class HistoryValidationTests(unittest.IsolatedAsyncioTestCase):
    async def test_all_history_routes_require_login(self):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            for path in ("/chat/history?sessionId=s", "/chat/sessions", "/chat/latest"):
                self.assertEqual(401, (await client.get(path)).status_code)
            self.assertEqual(401, (await client.post("/chat/new-session")).status_code)
            for path in ("/chat/session?sessionId=s", "/chat/last-round?sessionId=s"):
                self.assertEqual(401, (await client.delete(path)).status_code)

    async def test_invalid_paging_is_rejected_before_repository(self):
        verifier = AsyncMock()
        verifier.verify.return_value = 23
        with patch("app.core.middleware.auth.RedisTokenVerifier", return_value=verifier), \
             patch("app.service.chat.ChatRepository") as repository:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                for params in ({}, {"sessionId": ""}, {"sessionId": " "},
                               {"sessionId": "s", "limit": 0}, {"sessionId": "s", "limit": 101},
                               {"sessionId": "s", "beforeId": 0}, {"sessionId": "s", "beforeId": -1},
                               {"sessionId": "s", "limit": "bad"}):
                    self.assertEqual(422, (await client.get("/chat/history", params=params)).status_code)
                self.assertEqual(422, (await client.get("/chat/latest")).status_code)
                self.assertEqual(422, (await client.get("/chat/latest?sessionId=s&throughId=0")).status_code)
            repository.assert_not_called()


    async def test_snapshot_preserves_selection_order_and_deduplicates_ids(self):
        files = [SimpleNamespace(file_id=2, file_name="第二份.pdf"),
                 SimpleNamespace(file_id=1, file_name="第一份.docx")]
        repository = SimpleNamespace(ready_files=AsyncMock(return_value=files))
        with patch("app.service.chat.DocumentRepository", return_value=repository):
            references = await ChatService().document_references(23, [1, 2, 1])
        repository.ready_files.assert_awaited_once_with(23, [1, 2])
        files[1].file_name = "之后改名.docx"
        self.assertEqual([{"fileId": 1, "fileName": "第一份.docx"},
                          {"fileId": 2, "fileName": "第二份.pdf"}],
                         [item.model_dump() for item in references])


    async def test_missing_inaccessible_or_unready_references_reject_the_whole_selection(self):
        repository = SimpleNamespace(ready_files=AsyncMock(return_value=[
            SimpleNamespace(file_id=1, file_name="可访问.pdf")]))
        with patch("app.service.chat.DocumentRepository", return_value=repository):
            with self.assertRaises(HTTPException) as caught:
                await ChatService().document_references(23, [1, 2])
        self.assertEqual(400, caught.exception.status_code)
        repository.ready_files.assert_awaited_once_with(23, [1, 2])


    async def test_history_returns_saved_names_without_reloading_deleted_files(self):
        snapshot = [{"fileId": 12, "fileName": "原文件名.pdf"}]
        rows = [SimpleNamespace(dialogue_id=index, role="user", content="参考资料规划",
                                create_time=datetime(2026, 10, 4), response_time_ms=None,
                                agent_steps=None, document_references=references)
                for index, references in [(2, snapshot), (1, None)]]
        repository = SimpleNamespace(list_history=AsyncMock(return_value=(rows, False)))
        with patch("app.service.chat.ChatRepository", return_value=repository), \
             patch("app.service.chat.DocumentRepository") as documents:
            history = await ChatService().get_history(23, "s")
            documents.assert_not_called()
        self.assertEqual([], history.items[0].documentReferences)
        self.assertEqual(snapshot, history.model_dump()["items"][1]["documentReferences"])


MYSQL_URL = os.getenv("CHAT_TEST_MYSQL_URL")


@unittest.skipUnless(MYSQL_URL, "设置 CHAT_TEST_MYSQL_URL 后运行隔离 MySQL 集成测试")
class HistoryMysqlCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        # 不读取业务 DATABASE_URL。每个测试使用随机新库，不清空用户指定的库。
        self.database = "chat_history_test_" + uuid4().hex
        self.admin = create_async_engine(make_url(MYSQL_URL).set(database=None))
        async with self.admin.begin() as connection:
            await connection.execute(text(
                f"CREATE DATABASE `{self.database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            ))
        self.engine = create_async_engine(make_url(MYSQL_URL).set(database=self.database))
        self.addAsyncCleanup(self.cleanup_database)
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)
            schema = (Path(__file__).resolve().parents[2] / "sql/tables.sql").read_text(encoding="utf-8")
            for name in ("yl_agent_flow_state", "yl_chat_context_summary"):
                ddl = re.search(rf"CREATE TABLE `{name}`[\s\S]*?;", schema).group(0)
                await connection.execute(text(ddl))
        self.repository = ChatRepository(self.engine)
        self.session_repository = ChatSessionRepository(self.engine)
        self.enterContext(patch("app.service.chat.ChatRepository", return_value=self.repository))
        self.enterContext(patch("app.service.chat.ChatSessionRepository", return_value=self.session_repository))
        verifier = AsyncMock()
        verifier.verify.return_value = 23
        self.enterContext(patch("app.core.middleware.auth.RedisTokenVerifier", return_value=verifier))
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")
        self.addAsyncCleanup(self.client.aclose)

    async def cleanup_database(self):
        await self.engine.dispose()
        if not self.database.startswith("chat_history_test_") or len(self.database) != 50:
            raise AssertionError("不是本测试创建的临时数据库")
        async with self.admin.begin() as connection:
            await connection.execute(text(f"DROP DATABASE `{self.database}`"))
        await self.admin.dispose()


class HistoryMysqlTests(HistoryMysqlCase):
    async def append(self, count, session_id="s", user_id=23):
        return await self.repository.append_messages(user_id, session_id, [
            ChatMessageCreate(role="assistant" if i % 3 == 0 else "user", content=f"消息{i}")
            for i in range(count)
        ])

    async def test_default_twenty_messages_and_cursor_during_new_insert(self):
        ids = await self.append(45)
        first = (await self.client.get("/chat/history?sessionId=s")).json()["data"]
        self.assertEqual(ids[-20:], [item["dialogueId"] for item in first["items"]])
        self.assertEqual(ids[25], first["nextBeforeId"])
        self.assertTrue(first["hasMore"])
        self.assertEqual({"user", "assistant"}, {item["role"] for item in first["items"]})
        await self.append(1)
        second = (await self.client.get("/chat/history", params={
            "sessionId": "s", "beforeId": first["nextBeforeId"],
        })).json()["data"]
        self.assertEqual(ids[5:25], [item["dialogueId"] for item in second["items"]])
        third = (await self.client.get("/chat/history", params={
            "sessionId": "s", "beforeId": second["nextBeforeId"],
        })).json()["data"]
        self.assertEqual(ids[:5], [item["dialogueId"] for item in third["items"]])
        self.assertFalse(third["hasMore"])
        self.assertIsNone(third["nextBeforeId"])

    async def test_exact_page_custom_limit_and_empty_history(self):
        await self.append(20)
        page = (await self.client.get("/chat/history?sessionId=s")).json()["data"]
        self.assertEqual(20, len(page["items"]))
        self.assertFalse(page["hasMore"])
        self.assertIsNone(page["nextBeforeId"])
        page = (await self.client.get("/chat/history?sessionId=s&limit=1")).json()["data"]
        self.assertEqual(1, len(page["items"]))
        self.assertTrue(page["hasMore"])
        for path in ("/chat/history?sessionId=missing", "/chat/history?sessionId=s&beforeId=1"):
            self.assertEqual({"items": [], "hasMore": False, "nextBeforeId": None},
                             (await self.client.get(path)).json()["data"])

    async def test_user_and_session_isolation_and_deleted_messages(self):
        await self.append(3, user_id=99)
        await self.append(1, session_id="other")
        ids = await self.append(3)
        async with self.engine.begin() as connection:
            await connection.execute(update(AiDialogue).where(
                AiDialogue.dialogue_id == ids[1]).values(deleted_flag=1))
        rows, _ = await self.repository.list_history(23, "s")
        self.assertEqual([ids[2], ids[0]], [row.dialogue_id for row in rows])
        await self.append(1, session_id="private", user_id=99)
        self.assertEqual([], (await self.repository.list_history(23, "private"))[0])
        async with self.engine.begin() as connection:
            await connection.execute(update(AiSession).where(
                AiSession.user_id == 23, AiSession.session_id == "s").values(deleted_flag=1))
        self.assertEqual([], (await self.repository.list_history(23, "s"))[0])
        self.assertEqual([], await self.repository.recent_messages(23, "s"))
        self.assertEqual(["other"], [s.session_id for s in await self.session_repository.list_sessions(23)])
        with self.assertRaises(BusinessException):
            await self.append(1)

    async def test_unpaired_messages_title_count_and_model_context(self):
        await self.repository.append_messages(23, "s", [
            ChatMessageCreate(role="assistant", content="你好"),
            ChatMessageCreate(role="assistant", content="可以连续回复"),
        ])
        session = (await self.session_repository.list_sessions(23))[0]
        self.assertIsNone(session.title)
        self.assertEqual(2, session.message_count)
        title = "长标题" * 20
        await self.repository.append_messages(23, "s", [
            ChatMessageCreate(role="user", content=title),
            ChatMessageCreate(role="user", content="补充说明"),
        ])
        session = (await self.session_repository.list_sessions(23))[0]
        self.assertEqual(title[:30] + "...", session.title)
        self.assertEqual(4, session.message_count)
        context = await self.repository.recent_messages(23, "s")
        self.assertIsInstance(context[0], AssistantMessage)
        self.assertEqual(["assistant", "assistant", "user", "user"], [m.role for m in context])
        self.assertEqual("补充说明", context[-1].content)

    async def test_concurrent_first_writes_keep_session_and_counts_consistent(self):
        batches = await asyncio.gather(*(self.append(3) for _ in range(8)))
        sessions = await self.session_repository.list_sessions(23)
        self.assertEqual(1, len(sessions))
        self.assertEqual(24, sessions[0].message_count)
        self.assertEqual(max(i for batch in batches for i in batch), sessions[0].last_message_id)
        rows, more = await self.repository.list_history(23, "s", limit=100)
        self.assertEqual(24, len(rows))
        self.assertFalse(more)
        self.assertEqual(rows[0].create_time, sessions[0].last_message_time)

    async def test_failed_insert_rolls_back_new_session_and_existing_stats(self):
        # TEXT 最大 65535 字节，制造真实的 MySQL 写入错误。
        bad = ChatMessageCreate(role="user", content="x" * 70000)
        with self.assertRaises(DataError):
            await self.repository.append_messages(23, "new", [bad])
        self.assertEqual([], await self.session_repository.list_sessions(23))
        async with self.engine.connect() as connection:
            self.assertEqual(0, await connection.scalar(select(func.count()).select_from(AiSession)))
        await self.append(1)
        with self.assertRaises(DataError):
            await self.repository.append_messages(23, "s", [bad])
        session = (await self.session_repository.list_sessions(23))[0]
        self.assertEqual(1, session.message_count)
        rows, _ = await self.repository.list_history(23, "s")
        self.assertEqual(1, len(rows))

    async def test_sessions_read_only_metadata(self):
        await self.append(1, session_id="older")
        await self.append(25, session_id="newer")
        async with self.engine.begin() as connection:
            await connection.execute(update(AiSession).where(AiSession.session_id == "older").values(
                last_message_time=datetime(2020, 1, 1)))
        statements = []
        def record(_conn, _cursor, statement, _parameters, _context, _many):
            statements.append(statement)
        event.listen(self.engine.sync_engine, "before_cursor_execute", record)
        try:
            sessions = (await self.client.get("/chat/sessions")).json()["data"]
        finally:
            event.remove(self.engine.sync_engine, "before_cursor_execute", record)
        self.assertEqual(["newer", "older"], [s["sessionId"] for s in sessions])
        self.assertEqual(25, sessions[0]["messageCount"])
        self.assertIn("lastMessageTime", sessions[0])
        self.assertFalse(any("yl_ai_dialogue" in s for s in statements))

    async def test_route_context_contains_previous_reply_and_all_following_inputs(self):
        await self.repository.save_round(23, "s", "更早的问题", "更早的回复", 10)
        ids = await self.repository.append_messages(23, "s", [
            ChatMessageCreate(role="user", content="下一件事"),
            ChatMessageCreate(role="assistant", content="请说明时间和要求"),
            *[ChatMessageCreate(role="user", content=f"补充{i}") for i in range(30)],
        ])
        context = (await self.client.get("/chat/latest?sessionId=s")).json()["data"]
        self.assertEqual("s", context["sessionId"])
        self.assertEqual(ids[1], context["previousReply"]["dialogueId"])
        self.assertEqual("请说明时间和要求", context["previousReply"]["content"])
        self.assertEqual(ids[2:], [m["dialogueId"] for m in context["userMessages"]])
        self.assertEqual(30, len(context["userMessages"]))
        # 本次尚未入库的输入单独追加，相同文本也不能被去重成一条。
        current = await ChatService().get_route_context(23, "s", current_message="补充29")
        self.assertEqual(31, len(current.userMessages))
        self.assertIsNone(current.userMessages[-1].dialogueId)
        await self.repository.save_round(23, "s", "后来的输入", "后来的回复", 10)
        bounded = await ChatService().get_route_context(23, "s", through_id=ids[4])
        self.assertEqual(ids[1], bounded.previousReply.dialogueId)
        self.assertEqual(ids[2:5], [m.dialogueId for m in bounded.userMessages])

    async def test_route_context_no_reply_deleted_messages_and_user_isolation(self):
        own = await self.repository.append_messages(23, "s", [
            ChatMessageCreate(role="user", content="第一句"), ChatMessageCreate(role="user", content="第二句"),
        ])
        await self.repository.save_round(99, "s", "其他用户", "私密回复", 10)
        context = await ChatService().get_route_context(23, "s")
        self.assertIsNone(context.previousReply)
        self.assertEqual(own, [m.dialogueId for m in context.userMessages])
        async with self.engine.begin() as connection:
            await connection.execute(update(AiDialogue).where(AiDialogue.dialogue_id == own[0]).values(deleted_flag=1))
        context = await ChatService().get_route_context(23, "s")
        self.assertEqual([own[1]], [m.dialogueId for m in context.userMessages])
        await self.repository.delete_session(23, "s")
        self.assertEqual([], (await ChatService().get_route_context(23, "s")).userMessages)
        self.assertIsNone((await ChatService().get_route_context(23, "missing")).previousReply)

    async def test_new_session_is_unique_and_does_not_create_empty_sidebar_entry(self):
        first = (await self.client.post("/chat/new-session")).json()["data"]["sessionId"]
        second = (await self.client.post("/chat/new-session")).json()["data"]["sessionId"]
        self.assertNotEqual(first, second)
        self.assertEqual(36, len(first))
        self.assertEqual([], await self.session_repository.list_sessions(23))

    async def test_delete_session_cleans_context_and_blocks_late_reply(self):
        await self.repository.save_round(23, "s", "问题", "回答", 10)
        await self.repository.save_round(99, "s", "私密", "保留", 10)
        async with self.engine.begin() as connection:
            await connection.execute(text("INSERT INTO yl_agent_flow_state (user_id, session_id) VALUES (23, 's')"))
            await connection.execute(text("INSERT INTO yl_chat_context_summary (user_id, session_id, summary_text) VALUES (23, 's', '旧摘要')"))
        self.assertEqual(200, (await self.client.delete("/chat/session?sessionId=s")).status_code)
        self.assertEqual([], (await self.repository.list_history(23, "s"))[0])
        self.assertEqual(2, len((await self.repository.list_history(99, "s"))[0]))
        async with self.engine.connect() as connection:
            for name in ("yl_agent_flow_state", "yl_chat_context_summary"):
                self.assertEqual(0, await connection.scalar(text(f"SELECT COUNT(*) FROM {name}")))
        self.assertEqual([], await self.session_repository.list_sessions(23))
        with self.assertRaises(BusinessException):
            await self.repository.save_round(23, "s", "迟到问题", "迟到回答", 10)
        # 首轮还没有写入时删除，也要保留墓碑。
        await self.repository.delete_session(23, "in-flight")
        with self.assertRaises(BusinessException):
            await self.repository.save_round(23, "in-flight", "问题", "回答", 10)

    async def test_undo_uses_reply_boundaries_and_updates_statistics(self):
        await self.repository.save_round(23, "s", "保留问题", "保留回复", 10)
        await self.repository.append_messages(23, "s", [
            ChatMessageCreate(role="user", content="补充1"),
            ChatMessageCreate(role="user", content="补充2"),
            ChatMessageCreate(role="assistant", content="完整回复"),
        ])
        await self.repository.append_messages(23, "s", [
            ChatMessageCreate(role="user", content="尚未回复1"),
            ChatMessageCreate(role="user", content="尚未回复2"),
        ])
        self.assertEqual(200, (await self.client.delete("/chat/last-round?sessionId=s")).status_code)
        rows, _ = await self.repository.list_history(23, "s")
        self.assertEqual(5, len(rows))
        self.assertEqual("完整回复", rows[0].content)
        await self.repository.delete_last_round(23, "s")
        rows, _ = await self.repository.list_history(23, "s")
        self.assertEqual(["保留回复", "保留问题"], [r.content for r in rows])
        chat_session = (await self.session_repository.list_sessions(23))[0]
        self.assertEqual((2, rows[0].dialogue_id), (chat_session.message_count, chat_session.last_message_id))
        await self.repository.delete_last_round(23, "s")
        await self.repository.delete_last_round(23, "s")
        self.assertEqual([], await self.session_repository.list_sessions(23))

    async def test_delete_and_undo_refuse_processing_flow_without_partial_changes(self):
        await self.append(3)
        async with self.engine.begin() as connection:
            await connection.execute(text("INSERT INTO yl_agent_flow_state (user_id, session_id, processing) VALUES (23, 's', 1)"))
        for path in ("/chat/session", "/chat/last-round"):
            response = await self.client.delete(path, params={"sessionId": "s"})
            self.assertEqual(409, response.status_code)
        self.assertEqual(3, len((await self.repository.list_history(23, "s"))[0]))
        self.assertEqual(3, (await self.session_repository.list_sessions(23))[0].message_count)

    async def test_save_round_uses_same_atomic_message_storage(self):
        await self.repository.save_round(23, "s", "问题", "回答", 123)
        rows, _ = await self.repository.list_history(23, "s")
        self.assertEqual(["回答", "问题"], [row.content for row in rows])
        self.assertEqual(123, rows[0].response_time_ms)
        self.assertIsNone(rows[1].response_time_ms)
        self.assertEqual(2, (await self.session_repository.list_sessions(23))[0].message_count)


async def execute_mysql_script(connection, source):
    """执行本仓库迁移文件的分隔符，保留存储过程内的分号。"""
    delimiter = ";"
    pending = []
    for line in source.splitlines():
        if line.startswith("DELIMITER "):
            delimiter = line.split()[1]
            continue
        pending.append(line)
        if line.rstrip().endswith(delimiter):
            statement = "\n".join(pending).rstrip()[:-len(delimiter)]
            await connection.execute(text(statement))
            pending = []


@unittest.skipUnless(MYSQL_URL, "设置 CHAT_TEST_MYSQL_URL 后运行隔离 MySQL 集成测试")
class HistoryMigrationTests(HistoryMysqlCase):
    async def test_invalid_legacy_rows_stop_before_changing_schema(self):
        root = Path(__file__).resolve().parents[2]
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.drop_all)
            await execute_mysql_script(connection, (Path(__file__).parent / "fixtures/legacy_ai_dialogue.sql").read_text(encoding="utf-8"))
            await connection.execute(text("""
                INSERT INTO yl_ai_dialogue (user_id, session_id, role, user_text, ai_result)
                VALUES (23, 's', 'user', '用户正文', '错误角色列中的正文')
            """))
            with self.assertRaises(DBAPIError) as caught:
                await execute_mysql_script(connection, (root / "sql/migrations/20261002_chat_session_content.sql").read_text(encoding="utf-8"))
            self.assertIn("Unexpected role", str(caught.exception))
            columns = (await connection.exec_driver_sql("SHOW COLUMNS FROM yl_ai_dialogue")).all()
            self.assertNotIn("content", {row[0] for row in columns})
            self.assertEqual("错误角色列中的正文", await connection.scalar(text("SELECT ai_result FROM yl_ai_dialogue")))

    async def test_fresh_sql_schema_and_sample_messages(self):
        root = Path(__file__).resolve().parents[2]
        schema = (root / "sql/tables.sql").read_text(encoding="utf-8")
        samples = (root / "sql/insert_data.sql").read_text(encoding="utf-8")
        async with self.engine.begin() as connection:
            # 只执行本次变更涉及的两张表及对应样例，不加载无关业务数据。
            await execute_mysql_script(connection, schema[schema.index("-- yl_ai_session"):])
            await execute_mysql_script(connection, samples[samples.index("-- AI 对话记录（yl_ai_dialogue）"):])
        sessions = await self.session_repository.list_sessions(36)
        self.assertEqual(5, len(sessions))
        self.assertEqual(10, sum(s.message_count for s in sessions))
        await self.repository.save_round(36, sessions[0].session_id, "新消息", "新回答", 20)
        updated = await self.session_repository.list_sessions(36)
        self.assertEqual(12, sum(s.message_count for s in updated))

    async def test_legacy_data_migration(self):
        root = Path(__file__).resolve().parents[2]
        async with self.engine.begin() as connection:
            await connection.run_sync(Base.metadata.drop_all)
            await execute_mysql_script(connection, (Path(__file__).parent / "fixtures/legacy_ai_dialogue.sql").read_text(encoding="utf-8"))
            await connection.execute(text("""
                INSERT INTO yl_ai_dialogue
                    (user_id, session_id, role, user_text, ai_result, deleted_flag, create_time, update_time)
                VALUES
                    (23, 's', 'user', '旧问题', NULL, 0, '2026-01-01', '2026-01-02'),
                    (23, 's', 'assistant', NULL, '旧回复', 0, '2026-01-03', '2026-01-04'),
                    (23, 's', 'assistant', NULL, '已删除', 1, '2026-01-05', '2026-01-06'),
                    (99, 's', 'user', '其他用户', NULL, 0, '2026-01-01', '2026-01-02'),
                    (23, 'deleted', 'user', '删除会话', NULL, 1, '2026-01-01', '2026-01-02'),
                    (23, 'empty', 'assistant', NULL, NULL, 0, '2026-01-01', '2026-01-02')
            """))
            await execute_mysql_script(connection, (root / "sql/migrations/20261002_chat_session_content.sql").read_text(encoding="utf-8"))
            await execute_mysql_script(connection, (root / "sql/migrations/20261004_agent_timeline.sql").read_text(encoding="utf-8"))
            columns = (await connection.exec_driver_sql("SHOW COLUMNS FROM yl_ai_dialogue")).all()
            self.assertFalse({"user_text", "ai_result", "intent", "execute_result"} & {row[0] for row in columns})
            self.assertEqual(6, await connection.scalar(text("SELECT COUNT(*) FROM yl_ai_dialogue_backup_20261002")))
        rows, _ = await self.repository.list_history(23, "s")
        self.assertEqual(["旧回复", "旧问题"], [row.content for row in rows])
        self.assertEqual(datetime(2026, 1, 4), rows[0].update_time)
        sessions = await self.session_repository.list_sessions(23)
        session = next(s for s in sessions if s.session_id == "s")
        self.assertEqual(("旧问题", 2, 2, datetime(2026, 1, 3)),
                         (session.title, session.message_count, session.last_message_id, session.last_message_time))
        self.assertNotIn("deleted", [s.session_id for s in sessions])
        self.assertEqual("", (await self.repository.list_history(23, "empty"))[0][0].content)
        await self.repository.save_round(23, "s", "新问题", "新回复", 50)
        self.assertEqual(4, (await self.session_repository.list_sessions(23))[0].message_count)
