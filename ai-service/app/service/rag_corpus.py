"""从 Markdown 生成去重语料，并导入现有 Qdrant 集合。"""

from collections import Counter
from hashlib import md5
from math import log, sqrt
from pathlib import Path
import re
from uuid import UUID

from app.cache.embedding_cache import CachedEmbedding
from app.service.rag import _tokens

from app.helper.embedding_client import EmbeddingClient
from app.repository.qdrant import QdrantRepository
from app.schemas.rag import (
    CorpusChunk, QdrantCollectionInfo, QdrantPayload, QdrantUpsertPoint,
)

DEFAULT_CORPUS_DIR = Path(__file__).resolve().parents[2] / "rag_data"


def _split(text: str, maximum: int = 500) -> list[str]:
    if len(text) <= maximum:
        return [text]
    parts, current = [], ""
    for sentence in re.split(r"(?<=[。；！？\n])", text):
        if current and len(current) + len(sentence) > maximum:
            parts.append(current.strip())
            current = ""
        current += sentence
    if current.strip():
        parts.append(current.strip())
    return parts


def load_corpus(directory: Path = DEFAULT_CORPUS_DIR) -> list[CorpusChunk]:
    """读取语料目录，按标题保留来源和章节，供检索结果溯源。"""

    if not directory.is_dir():
        raise FileNotFoundError(directory)
    chunks = []
    for path in sorted(directory.glob("*.md")):
        raw = path.read_text(encoding="utf-8")
        raw = re.sub(r"\A---\s*\n.*?\n---\s*\n", "", raw, flags=re.S)
        headings: list[str] = []
        sections: list[tuple[str, str]] = []
        for line in raw.splitlines():
            match = re.match(r"^(#{1,6})\s+(.+)$", line)
            if match:
                headings = headings[:len(match[1]) - 1] + [match[2].strip()]
                continue
            line = line.strip()
            if not line or re.fullmatch(r"[-*_]{3,}", line):
                continue
            if line.startswith("> ") and "注：" in line:
                continue
            line = re.sub(r"!\[[^]]*]\([^)]+\)", "", line)
            line = re.sub(r"\[([^]]+)]\([^)]+\)", r"\1", line)
            line = re.sub(r"`([^`]+)`", r"\1", line)
            line = re.sub(r"\*{1,3}([^*]+)\*{1,3}", r"\1", line)
            line = re.sub(r"^>\s?", "", line).strip()
            if not line:
                continue
            section = " > ".join(headings) or "(无标题)"
            if sections and sections[-1][0] == section:
                sections[-1] = (section, sections[-1][1] + "\n" + line)
            else:
                sections.append((section, line))
        for section, text in sections:
            for fragment in _split(text):
                if len(fragment) >= 30:
                    chunks.append(CorpusChunk(path.name, section, fragment))
    return chunks


def _trigrams(text: str) -> set[str]:
    normalized = re.sub(r"\s+", "", text)
    return {normalized[i:i + 3] for i in range(max(1, len(normalized) - 2))}


def _features(text: str) -> list[str]:
    words = _tokens(text)
    return words + [words[i] + "\0" + words[i + 1] for i in range(len(words) - 1)]


def _simhash(words: list[str]) -> int:
    weights = [0] * 128
    for word in words:
        digest = int.from_bytes(md5(word.encode("utf-8")).digest(), "big")
        for bit in range(128):
            weights[bit] += 1 if digest & (1 << bit) else -1
    return sum(1 << bit for bit, weight in enumerate(weights) if weight > 0)


def _cosine(left: dict[str, float], right: dict[str, float]) -> float:
    dot = sum(value * right.get(term, 0) for term, value in left.items())
    left_norm = sqrt(sum(value * value for value in left.values()))
    right_norm = sqrt(sum(value * value for value in right.values()))
    return dot / (left_norm * right_norm) if left_norm and right_norm else 0


def deduplicate(chunks: list[CorpusChunk]) -> list[CorpusChunk]:
    """沿用 Java 版的 TF-IDF、SimHash 和 3-gram Jaccard 去重规则。"""
    documents = [_features(chunk.text) for chunk in chunks]
    frequency = Counter(term for document in documents for term in set(document))
    vectors = [{term: count * (log((len(chunks) + 1) / (frequency[term] + 1)) + 1)
                for term, count in Counter(document).items()} for document in documents]
    hashes = [_simhash(document) for document in documents]
    trigrams = [_trigrams(chunk.text) for chunk in chunks]
    removed = set()
    for i in range(len(chunks)):
        if i in removed:
            continue
        for j in range(i + 1, len(chunks)):
            if j in removed:
                continue
            union = trigrams[i] | trigrams[j]
            jaccard = len(trigrams[i] & trigrams[j]) / len(union) if union else 1
            if (_cosine(vectors[i], vectors[j]) >= 0.92
                    or (hashes[i] ^ hashes[j]).bit_count() <= 3
                    or jaccard >= 0.80):
                removed.add(j)
    return [chunk for i, chunk in enumerate(chunks) if i not in removed]


def _java_name_uuid(value: str) -> str:
    # Java UUID.nameUUIDFromBytes() 对原始 UTF-8 字节做 MD5，不能直接用 Python uuid3()。
    raw = bytearray(md5(value.encode("utf-8")).digest())
    raw[6] = (raw[6] & 0x0f) | 0x30
    raw[8] = (raw[8] & 0x3f) | 0x80
    return str(UUID(bytes=bytes(raw)))


async def import_corpus(directory: Path = DEFAULT_CORPUS_DIR, *, dry_run: bool = False,
                        repository: QdrantRepository | None = None,
                        embedding: EmbeddingClient | None = None) -> int:
    """先检查维度，再按稳定 UUID 分批 upsert；dry_run 不访问模型或 Qdrant。"""

    chunks = deduplicate(load_corpus(directory))
    if not chunks:
        raise ValueError(f"没有可导入的语料: {directory}")
    if dry_run:
        return len(chunks)
    repository = repository or QdrantRepository()
    embedding = embedding or CachedEmbedding()
    first = await embedding.embed(chunks[0].text)
    info = await repository.collection_info()
    if info is None:
        await repository.create_collection(len(first))
    else:
        collection = QdrantCollectionInfo.model_validate(info)
        if collection.config.params.vectors.size != len(first):
            raise ValueError("现有 Qdrant 集合的向量维度与 embedding 模型不匹配")
    batch: list[QdrantUpsertPoint] = []
    for index, chunk in enumerate(chunks):
        vector = first if index == 0 else await embedding.embed(chunk.text)
        if len(vector) != len(first):
            raise ValueError("Embedding 向量维度不一致")
        identity = "\0".join((chunk.source, chunk.section, chunk.text))
        batch.append(QdrantUpsertPoint(
            id=_java_name_uuid(identity), vector=vector,
            payload=QdrantPayload(source=chunk.source, section=chunk.section,
                                  text=chunk.text, char_count=len(chunk.text)),
        ))
        if len(batch) == 32 or index == len(chunks) - 1:
            await repository.upsert(batch)
            batch = []
    return len(chunks)
