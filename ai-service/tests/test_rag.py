import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import httpx
from fastapi import HTTPException

from app.api.routers.rag_router import get_rag_service
from app.main import app
from app.schemas.rag import QdrantPayload, QdrantPoint, RagHit
from app.helper.embedding_client import EmbeddingClient
from app.core.config.agent.agents import EmbeddingConfig
from app.core.config.agent.providers import ModelConfig
from app.repository.qdrant import QdrantError, QdrantRepository
from app.service.rag import RagService
from app.service.rag_corpus import (
    DEFAULT_CORPUS_DIR, CorpusChunk, _java_name_uuid, deduplicate, import_corpus,
    load_corpus,
)


class FakeRedis:
    def __init__(self):
        self.values = {}

    async def get(self, key):
        return self.values.get(key)

    async def set(self, key, value, ex):
        assert ex > 0
        self.values[key] = value


class RagTests(unittest.IsolatedAsyncioTestCase):
    async def test_default_corpus_is_in_ai_service(self):
        self.assertEqual(Path(__file__).resolve().parents[1] / "rag_data", DEFAULT_CORPUS_DIR)
        self.assertEqual(2, len(list(DEFAULT_CORPUS_DIR.glob("*.md"))))
        self.assertTrue(load_corpus())

    async def test_rag_endpoint_requires_token_and_is_independent_of_chat(self):
        class Service:
            async def search(self, query):
                self_query = query
                assert self_query == "学习计划"
                return [RagHit(id="a", text="规划参考", score=0.02)]

        class Verifier:
            async def verify(self, token):
                if token != "valid":
                    raise HTTPException(status_code=401, detail="未登录")
                return 23

        app.dependency_overrides[get_rag_service] = lambda: Service()
        try:
            with patch("app.core.middleware.auth.RedisTokenVerifier", return_value=Verifier()):
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),
                                             base_url="http://test") as client:
                    unauthorized = await client.post("/rag/search", json={"query": "学习计划"})
                    response = await client.post("/rag/search", headers={"yvli-token": "valid"},
                                                 json={"query": "学习计划"})
            self.assertEqual(401, unauthorized.status_code)
            self.assertEqual("规划参考", response.json()["data"][0]["text"])
        finally:
            app.dependency_overrides.clear()

    async def test_embedding_provider_and_namespace(self):
        requests = []

        def respond(request):
            requests.append(request)
            return httpx.Response(200, json={"data": [{"embedding": [0.1, 0.2]}]})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            config = EmbeddingConfig(model=ModelConfig(
                "aliyun", "text-embedding-v4",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "test-key"))
            embedding = EmbeddingClient(client, config=config)
            self.assertEqual([0.1, 0.2], await embedding.embed("备考"))
        self.assertEqual("https://dashscope.aliyuncs.com/compatible-mode/v1:text-embedding-v4", embedding.namespace)
        self.assertEqual("Bearer test-key", requests[0].headers["authorization"])
        self.assertEqual("备考", json.loads(requests[0].content)["input"])

    async def test_qdrant_scroll_and_dense_query(self):
        requests = []

        def respond(request):
            requests.append(request)
            if request.url.path.endswith("/scroll"):
                offset = json.loads(request.content).get("offset")
                return httpx.Response(200, json={"result": {
                    "points": [{"id": "a", "payload": {"text": "规划"}}] if offset is None else [],
                    "next_page_offset": 12 if offset is None else None}})
            return httpx.Response(200, json={"result": {"points": [{"id": "a", "score": 0.9,
                                                                     "payload": {"text": "规划"}}]}})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            repo = QdrantRepository(client)
            self.assertEqual(1, len(await repo.scroll_all()))
            self.assertEqual(0.9, (await repo.search([0.1, 0.2], 3))[0].score)
        self.assertEqual(12, json.loads(requests[1].content)["offset"])
        self.assertEqual([0.1, 0.2], json.loads(requests[2].content)["query"])
        self.assertNotIn("api-key", requests[2].headers)

    async def test_qdrant_collection_reset_request(self):
        requests = []

        def respond(request):
            requests.append(request)
            return httpx.Response(200, json={"result": True})

        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            await QdrantRepository(client).delete_collection()
        self.assertEqual("DELETE", requests[0].method)
        self.assertEqual("/collections/rag_corpus", requests[0].url.path)

    async def test_qdrant_rejects_malformed_point(self):
        transport = httpx.MockTransport(lambda _: httpx.Response(
            200, json={"result": {"points": [{"payload": {"text": "缺少 id"}}]}}
        ))
        async with httpx.AsyncClient(transport=transport) as client:
            with self.assertRaises(QdrantError):
                await QdrantRepository(client).search([0.1], 1)

    async def test_search_fuses_results_and_caches_embedding_and_result(self):
        class Repo:
            scrolls = 0
            searches = 0

            async def scroll_all(self):
                self.scrolls += 1
                return [QdrantPoint(id="a", payload=QdrantPayload(
                            text="制定学习计划", char_count=6)),
                        QdrantPoint(id="b", payload=QdrantPayload(text="厨房采购清单"))]

            async def search(self, vector, limit):
                self.searches += 1
                return [QdrantPoint(id="a", score=0.9, payload=QdrantPayload(
                    text="制定学习计划", char_count=6))]

        class Embedding:
            namespace = "test:model"
            calls = 0

            async def embed(self, text):
                self.calls += 1
                return [0.1, 0.2]

        repo, embedding, redis = Repo(), Embedding(), FakeRedis()
        service = RagService(repo, embedding, redis)
        with self.assertLogs("app.service.rag", level="INFO") as captured:
            first = await service.search("学习计划")
            second = await service.search("学习计划")
        self.assertEqual("a", first[0].id)
        self.assertEqual(first, second)
        self.assertEqual((1, 1, 1), (repo.scrolls, repo.searches, embedding.calls))
        self.assertTrue(any(key.startswith("rag:emb:") for key in redis.values))
        self.assertTrue(any(key.startswith("rag:result:") for key in redis.values))
        self.assertIn("查询='学习计划'", captured.output[0])
        self.assertTrue(any("缓存命中=False" in line and "返回数=1" in line
                            for line in captured.output))
        self.assertTrue(any("缓存命中=True" in line and "返回数=1" in line
                            for line in captured.output))
        self.assertTrue(any("点ID=a" in line and "内容='制定学习计划'" in line
                            for line in captured.output))

    async def test_import_deduplicates_and_reuses_java_uuid(self):
        class Repo:
            points = None

            async def collection_info(self):
                return {"config": {"params": {"vectors": {"size": 2}}}}

            async def upsert(self, points):
                self.points = points

        class Embedding:
            async def embed(self, text):
                return [0.1, 0.2]

        text = "学习规划需要分阶段执行。先确定目标，再逐步安排每天的任务。"
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "corpus.md").write_text("# 学习\n" + text + "\n" + text, encoding="utf-8")
            repo = Repo()
            self.assertEqual(1, await import_corpus(Path(directory), repository=repo,
                                                    embedding=Embedding()))
        self.assertEqual(1, len(repo.points))
        self.assertEqual(4, repo.points[0].id.count("-"))
        self.assertEqual(1, len(deduplicate([CorpusChunk("x", "y", text)] * 2)))
        self.assertEqual(_java_name_uuid("abc"), "90015098-3cd2-3fb0-9696-3f7d28e17f72")
