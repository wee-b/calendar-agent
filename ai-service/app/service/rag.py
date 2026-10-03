"""独立的 RAG 检索流程；当前聊天链路不调用该模块。"""

import asyncio
from collections import Counter
from hashlib import md5
import json
import logging
from math import log
import re
from time import monotonic

import jieba

from app.core.config.common.qdrant import get_qdrant_settings
from app.core.config.common.rag import get_rag_settings
from app.core.flow_logging import text_preview
from app.cache.embedding_cache import CachedEmbedding
from app.db.redis_client import get_redis
from app.helper.embedding_client import EmbeddingClient, EmbeddingError
from app.helper.model_client import ModelClient
from app.repository.qdrant import QdrantError, QdrantRepository
from app.schemas.rag import QdrantPoint, RagHit

logger = logging.getLogger(__name__)


def _tokens(text: str) -> list[str]:
    """将中文语料切成 BM25 使用的词项，并过滤纯标点。"""

    return [word.lower() for word in jieba.lcut(text) if word.strip()
            and re.search(r"[\w\u4e00-\u9fff]", word)]


def _point_hit(point: QdrantPoint, score: float = 0.0) -> RagHit:
    """把向量库内部字段转换为 /rag/search 的输出字段。"""

    return RagHit(id=str(point.id), source=point.payload.source,
                  section=point.payload.section, text=point.payload.text,
                  charCount=point.payload.char_count, score=score)


def _bm25(query: str, points: list[QdrantPoint], limit: int) -> list[RagHit]:
    """在已缓存的语料点上计算词项相关性，并归一化为候选分数。"""

    documents = [_tokens(point.payload.text) for point in points]
    if not documents:
        return []
    df = Counter(word for doc in documents for word in set(doc))
    average_length = sum(map(len, documents)) / len(documents) or 1
    scores = []
    for index, doc in enumerate(documents):
        counts = Counter(doc)
        score = 0.0
        for word in set(_tokens(query)):
            if word not in counts:
                continue
            frequency = counts[word]
            idf = log(1 + (len(documents) - df[word] + 0.5) / (df[word] + 0.5))
            score += idf * frequency * 2.2 / (frequency + 1.2 * (0.25 + 0.75 * len(doc) / average_length))
        if score > 0:
            scores.append((index, score))
    scores.sort(key=lambda item: item[1], reverse=True)
    maximum = scores[0][1] if scores else 1
    return [_point_hit(points[index], score / maximum) for index, score in scores[:limit]]


def _rrf(bm25_hits: list[RagHit], dense_hits: list[RagHit], limit: int,
         threshold: float) -> list[RagHit]:
    """按排名融合 BM25 和 Dense 候选，再按阈值与数量截断。"""

    scores: dict[str, float] = {}
    hits: dict[str, RagHit] = {}
    for source in (bm25_hits, dense_hits):
        for rank, hit in enumerate(source):
            scores[hit.id] = scores.get(hit.id, 0) + 1 / (60 + rank + 1)
            hits.setdefault(hit.id, hit)
    return [hits[id].model_copy(update={"score": score})
            for id, score in sorted(scores.items(), key=lambda pair: pair[1], reverse=True)
            if score >= threshold][:limit]


class RagService:
    """管理语料快照、Embedding/结果缓存以及可选的模型重排。"""

    def __init__(self, repository: QdrantRepository | None = None,
                 embedding: EmbeddingClient | None = None, redis=None,
                 model: ModelClient | None = None):
        self.settings = get_rag_settings()
        self.qdrant_settings = get_qdrant_settings()
        self.repository = repository or QdrantRepository()
        self.redis = redis if redis is not None else get_redis()
        self.embedding = CachedEmbedding(embedding or EmbeddingClient(), self.redis)
        self.model = model or ModelClient()
        self._points: list[QdrantPoint] | None = None
        self._points_loaded_at = 0.0
        self._index_lock = asyncio.Lock()

    async def _cached_vector(self, query: str) -> list[float]:
        return await self.embedding.embed(query)

    async def _corpus(self) -> list[QdrantPoint]:
        """按 TTL 缓存完整语料，锁住并发重载以免重复滚动查询。"""

        if self._points is None or monotonic() - self._points_loaded_at >= self.settings.rag_result_cache_ttl:
            async with self._index_lock:
                if self._points is None or monotonic() - self._points_loaded_at >= self.settings.rag_result_cache_ttl:
                    self._points = await self.repository.scroll_all()
                    self._points_loaded_at = monotonic()
        return self._points

    def invalidate_corpus(self) -> None:
        """导入或更新语料后使本地 BM25 快照失效。"""

        self._points = None

    async def _rerank(self, query: str, hits: list[RagHit]) -> list[RagHit]:
        """模型重排不可用时保留 RRF 顺序，不影响基础检索。"""

        if not self.settings.rag_rerank_enabled or len(hits) <= self.settings.rag_top_k:
            return hits
        prompt = ("你是日程规划相关性评估助手。只输出候选片段索引的 JSON 数组，最相关的排前面。\n"
                  f"用户查询: {query}\n候选语料:\n" +
                  "\n".join(f"[{i}] {hit.text}" for i, hit in enumerate(hits)))
        try:
            response = await self.model.complete([{"role": "user", "content": prompt}])
            start, end = response.find("["), response.rfind("]")
            order = json.loads(response[start:end + 1])
            if not isinstance(order, list):
                return hits
            indices = []
            for value in order:
                if type(value) is int and 0 <= value < len(hits) and value not in indices:
                    indices.append(value)
            indices.extend(i for i in range(len(hits)) if i not in indices)
            return [hits[i].model_copy(update={"score": 1 - rank / len(hits)})
                    for rank, i in enumerate(indices)]
        except Exception as exc:
            logger.warning("RAG 重排失败，使用 RRF 顺序: %s", exc)
            return hits

    async def search(self, query: str) -> list[RagHit]:
        """先读结果缓存，再并行准备语料和向量并融合两路召回。"""

        query = query.strip()
        if not query:
            return []
        started = monotonic()
        logger.info("RAG 查询开始 | 集合=%s top_k=%s BM25候选=%s 向量候选=%s 阈值=%s 重排=%s 查询长度=%s 查询=%r",
                    self.qdrant_settings.qdrant_collection, self.settings.rag_top_k,
                    self.settings.rag_bm25_top_k, self.settings.rag_dense_top_k,
                    self.settings.rag_recall_threshold, self.settings.rag_rerank_enabled,
                    len(query), text_preview(query, 160))
        cache_key = "rag:result:" + md5((self.qdrant_settings.qdrant_collection + ":" +
            self.embedding.namespace + ":" + query).encode()).hexdigest()
        try:
            cached = await self.redis.get(cache_key)
            if cached is not None:
                result = [RagHit.model_validate(hit) for hit in json.loads(cached)]
                self._log_search_result(result, started, cache_hit=True)
                return result
        except Exception as exc:
            logger.warning("RAG 结果缓存读取失败: %s", exc)

        try:
            points, vector = await asyncio.gather(self._corpus(), self._cached_vector(query))
            dense_points = await self.repository.search(vector, self.settings.rag_dense_top_k)
        except (QdrantError, EmbeddingError) as exc:
            logger.warning("RAG 检索降级: %s", exc)
            points = self._points or []
            dense_points = []

        bm25_hits = _bm25(query, points, self.settings.rag_bm25_top_k)
        dense_hits = [_point_hit(point, point.score)
                      for point in dense_points]
        fused = _rrf(bm25_hits, dense_hits, self.settings.rag_top_k * 3,
                     self.settings.rag_recall_threshold)
        ranked = await self._rerank(query, fused)
        result = ranked[:self.settings.rag_top_k]
        self._log_search_result(result, started, cache_hit=False,
                                corpus_count=len(points), bm25_count=len(bm25_hits),
                                dense_count=len(dense_hits), fused_count=len(fused))
        if result:
            try:
                await self.redis.set(cache_key,
                                     json.dumps([hit.model_dump() for hit in result], ensure_ascii=False),
                                     ex=self.settings.rag_result_cache_ttl)
            except Exception as exc:
                logger.warning("RAG 结果缓存写入失败: %s", exc)
        return result

    @staticmethod
    def _log_search_result(hits: list[RagHit], started: float, *, cache_hit: bool,
                           corpus_count: int | None = None, bm25_count: int | None = None,
                           dense_count: int | None = None, fused_count: int | None = None) -> None:
        if cache_hit:
            logger.info("RAG 查询完成 | 缓存命中=True 返回数=%s 耗时=%.3f秒",
                        len(hits), monotonic() - started)
        else:
            logger.info("RAG 查询完成 | 缓存命中=False 语料数=%s BM25命中=%s 向量命中=%s 融合命中=%s 返回数=%s 耗时=%.3f秒",
                        corpus_count, bm25_count, dense_count, fused_count,
                        len(hits), monotonic() - started)
        for rank, hit in enumerate(hits, 1):
            logger.info("RAG 命中 | 排名=%s 点ID=%s 分数=%.4f 来源=%r 章节=%r 内容长度=%s 内容=%r",
                        rank, hit.id, hit.score, hit.source, hit.section,
                        len(hit.text), text_preview(hit.text, 160))
