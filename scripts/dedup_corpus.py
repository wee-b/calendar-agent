#!/usr/bin/env python3
"""
日程规划语料处理管线：去重 → BGE-M3 Embedding → Qdrant 入库

用法：
    # 全流程：去重 + embedding + Qdrant 入库
    python dedup_corpus.py -i ./rag_data --embedding-backend ollama --qdrant

    # 只去重，不 embedding
    python dedup_corpus.py -i ./rag_data --skip-embedding

    # 跳过 MinHash（小数据集加速）
    python dedup_corpus.py -i ./rag_data --no-minhash --qdrant

    # 带用户历史数据
    python dedup_corpus.py -i ./rag_data -H ./user_history.json --qdrant

Embedding 后端说明：
    ollama   → 本地 Ollama，默认 http://localhost:11434，模型 bge-m3
    openai   → 任何 OpenAI 兼容 API（硅基流动、阿里云等）
"""

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

import jieba
import numpy as np
from datasketch import MinHash, MinHashLSH
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity

# ---------------------------------------------------------------------------
# IDE 直接运行时的默认配置（PyCharm / VSCode 点 Run 即可）
# 从终端运行时请改用命令行参数，此处不影响
# ---------------------------------------------------------------------------
IDE_DEFAULTS = {
    "input":        "./rag_data",          # 语料目录（.md 文件）
    "history":      None,                  # 用户历史 JSON（可选）
    "output":       "./output",            # 输出目录
    "skip_dedup":   False,
    "no_minhash":   False,
    "skip_embedding": False,
    "embedding_backend": "ollama",
    "embedding_url":     "http://localhost:11434",
    "embedding_model":   "bge-m3",
    "embedding_api_key": None,
    "embedding_batch":   16,
    "qdrant":          True,              # IDE 运行默认启用 Qdrant 入库
    "qdrant_host":     "localhost",
    "qdrant_port":     6334,
    "qdrant_collection": "rag_corpus",
    "qdrant_drop":     False,             # True = 每次清空重建
    "tfidf_threshold":   0.92,
    "simhash_distance":  3,
    "minhash_threshold": 0.8,
    "minhash_perm":      128,
}

# ---------------------------------------------------------------------------
# 中文停用词
# ---------------------------------------------------------------------------
STOPWORDS: Set[str] = {
    "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
    "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
    "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那", "些",
    "所", "为", "所以", "因为", "但是", "然而", "而且", "虽然", "如果",
    "可以", "这个", "那个", "什么", "怎么", "哪", "为什么", "如何",
    "比如", "例如", "等等", "等", "各", "每", "被", "把", "从", "对",
    "与", "及", "或", "并", "但", "而", "且", "虽", "以", "之", "其",
    "中", "后", "前", "时", "内", "外", "来", "去", "做", "让", "给",
    "向", "至", "于", "则", "又", "能", "将", "已", "还", "只", "可",
    "便", "更", "最", "越", "再", "才", "已", "曾", "刚", "正", "正在",
    "过", "着", "了", "呢", "吗", "吧", "啊", "呀", "哦", "嗯", "嘛",
    "哈", "哇", "呵", "噢", "喔",
    " ", "\t", "\n", "\r", ".", "。", ",", "，", "、", "；", "：",
    "（", "）", "(", ")", "《", "》", "【", "】", "[", "]", "{", "}",
    "！", "？", "!", "?", "—", "-", "…", "“", "”", "‘", "’",
    "·", "／", "/", "＼", "｜", "＊", "％", "＃", "＠", "＆",
    "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
}

# BGE-M3 输出维度
BGE_M3_DIM = 1024


# ---------------------------------------------------------------------------
# SimHash（64-bit）
# ---------------------------------------------------------------------------
class SimHash:
    __slots__ = ("value", "hash_bits")

    def __init__(self, tokens: List[str], hash_bits: int = 64) -> None:
        self.hash_bits = hash_bits
        self.value = self._compute(tokens)

    def _compute(self, tokens: List[str]) -> int:
        v = [0] * self.hash_bits
        for token in tokens:
            h = int(hashlib.md5(token.encode("utf-8", errors="ignore")).hexdigest(), 16)
            for i in range(self.hash_bits):
                if h & (1 << i):
                    v[i] += 1
                else:
                    v[i] -= 1
        fingerprint = 0
        for i in range(self.hash_bits):
            if v[i] > 0:
                fingerprint |= 1 << i
        return fingerprint

    def hamming_distance(self, other: "SimHash") -> int:
        return (self.value ^ other.value).bit_count()


def _simhash_cluster(chunks: List[Dict], max_distance: int = 3) -> List[Dict]:
    simhashes = []
    for c in chunks:
        tokens = list(jieba.cut(c["text"]))
        tokens = [t.strip() for t in tokens if t.strip() and t.strip() not in STOPWORDS]
        simhashes.append(SimHash(tokens))

    kept: List[Dict] = []
    removed: Set[int] = set()

    for i, sh_i in enumerate(simhashes):
        if i in removed:
            continue
        kept.append(chunks[i])
        for j in range(i + 1, len(simhashes)):
            if j in removed:
                continue
            if sh_i.hamming_distance(simhashes[j]) <= max_distance:
                removed.add(j)
    return kept


# ---------------------------------------------------------------------------
# Markdown 分块器
# ---------------------------------------------------------------------------
_HEADING_RE = re.compile(r"^(#{1,6})\s+(.+)$", re.MULTILINE)


def _clean_markdown(text: str) -> str:
    text = re.sub(r"`([^`]+)`", r"\1", text)
    text = re.sub(r"\*{1,3}([^*]+)\*{1,3}", r"\1", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    text = re.sub(r"!\[[^\]]*\]\([^)]+\)", "", text)
    text = re.sub(r"^>\s?", "", text, flags=re.MULTILINE)
    text = re.sub(r"^[-*_]{3,}\s*$", "", text, flags=re.MULTILINE)
    return text.strip()


def _split_long_paragraph(text: str, max_len: int = 500) -> List[str]:
    if len(text) <= max_len:
        return [text]
    sentences = re.split(r"(?<=[。；！？\n])", text)
    result: List[str] = []
    buf = ""
    for s in sentences:
        if len(buf) + len(s) <= max_len:
            buf += s
        else:
            if buf.strip():
                result.append(buf.strip())
            buf = s
    if buf.strip():
        result.append(buf.strip())
    return result if result else [text]


def chunk_markdown(filepath: str) -> List[Dict]:
    with open(filepath, "r", encoding="utf-8") as f:
        raw = f.read()

    source = os.path.basename(filepath)
    raw = re.sub(r"^---\n.*?\n---\n", "", raw, flags=re.DOTALL)

    lines = raw.split("\n")
    sections: List[Tuple[List[str], str]] = []
    heading_stack: List[str] = []

    for line in lines:
        m = _HEADING_RE.match(line)
        if m:
            level = len(m.group(1))
            title = m.group(2).strip()
            heading_stack = heading_stack[: level - 1]
            heading_stack.append(title)
            continue
        stripped = line.strip()
        if not stripped:
            continue
        if re.match(r"^[-*_]{3,}$", stripped):
            continue
        if stripped.startswith("> ") and "注：" in stripped:
            continue
        cleaned = _clean_markdown(stripped)
        if not cleaned:
            continue
        sections.append((list(heading_stack), cleaned))

    merged: List[Tuple[List[str], str]] = []
    for hs, para in sections:
        if merged and merged[-1][0] == hs:
            merged[-1] = (hs, merged[-1][1] + "\n" + para)
        else:
            merged.append((hs, para))

    chunks: List[Dict] = []
    for hs, para in merged:
        section_path = " > ".join(hs) if hs else "(无标题)"
        fragments = _split_long_paragraph(para)
        for frag in fragments:
            if len(frag) < 30:
                continue
            chunks.append({
                "source": source,
                "section": section_path,
                "text": frag,
                "char_count": len(frag),
            })
    return chunks


def _tokenize_chinese(text: str) -> str:
    tokens = jieba.cut(text)
    words = [t.strip() for t in tokens if t.strip() and t.strip() not in STOPWORDS]
    return " ".join(words)


# ---------------------------------------------------------------------------
# 三段式去重
# ---------------------------------------------------------------------------
def dedup_tfidf(chunks: List[Dict], threshold: float = 0.92) -> Tuple[List[Dict], int]:
    if len(chunks) <= 1:
        return chunks, 0

    corpus = [_tokenize_chinese(c["text"]) for c in chunks]
    vectorizer = TfidfVectorizer(analyzer="word", max_features=8000, ngram_range=(1, 2))
    try:
        tfidf_matrix = vectorizer.fit_transform(corpus)
    except ValueError:
        return chunks, 0

    sim = cosine_similarity(tfidf_matrix)
    n = len(chunks)
    removed: Set[int] = set()

    for i in range(n):
        if i in removed:
            continue
        for j in range(i + 1, n):
            if j in removed:
                continue
            if sim[i][j] >= threshold:
                removed.add(j)
    kept = [chunks[i] for i in range(n) if i not in removed]
    return kept, len(removed)


def dedup_simhash(chunks: List[Dict], max_distance: int = 3) -> Tuple[List[Dict], int]:
    before = len(chunks)
    kept = _simhash_cluster(chunks, max_distance)
    return kept, before - len(kept)


def dedup_minhash(
    chunks: List[Dict],
    num_perm: int = 128,
    jaccard_threshold: float = 0.8,
) -> Tuple[List[Dict], int]:
    if len(chunks) <= 1:
        return chunks, 0

    lsh = MinHashLSH(threshold=jaccard_threshold, num_perm=num_perm)
    minhashes: Dict[int, MinHash] = {}

    for i, c in enumerate(chunks):
        mh = MinHash(num_perm=num_perm)
        text = c["text"]
        for k in range(len(text) - 2):
            mh.update(text[k : k + 3].encode("utf-8"))
        minhashes[i] = mh
        lsh.insert(i, mh)

    removed: Set[int] = set()
    n = len(chunks)
    for i in range(n):
        if i in removed:
            continue
        for j in lsh.query(minhashes[i]):
            if j <= i or j in removed:
                continue
            if minhashes[i].jaccard(minhashes[j]) >= jaccard_threshold:
                removed.add(max(i, j))
    kept = [chunks[i] for i in range(n) if i not in removed]
    return kept, len(removed)


# ---------------------------------------------------------------------------
# 用户历史数据加载
# ---------------------------------------------------------------------------
def load_user_history(filepath: str) -> List[Dict]:
    with open(filepath, "r", encoding="utf-8") as f:
        raw = f.read().strip()

    if raw.startswith("["):
        records = json.loads(raw)
    else:
        records = [json.loads(line) for line in raw.split("\n") if line.strip()]

    chunks: List[Dict] = []
    for i, rec in enumerate(records):
        user_text = (rec.get("user_text") or rec.get("userText") or "").strip()
        ai_result = (rec.get("ai_result") or rec.get("aiResult") or "").strip()
        sid = rec.get("session_id", "unknown")
        if user_text:
            chunks.append({
                "source": f"user_history#{sid}",
                "section": f"用户输入 #{i + 1}",
                "text": user_text,
                "char_count": len(user_text),
            })
        if ai_result:
            chunks.append({
                "source": f"user_history#{sid}",
                "section": f"AI 回复 #{i + 1}",
                "text": ai_result,
                "char_count": len(ai_result),
            })
    return chunks


# ---------------------------------------------------------------------------
# BGE-M3 Embedding
# ---------------------------------------------------------------------------
class EmbeddingClient:
    """BGE-M3 embedding 客户端，支持 Ollama / OpenAI 兼容后端。"""

    def __init__(self, backend: str, base_url: str, model: str,
                 api_key: Optional[str] = None, timeout: int = 120) -> None:
        self.backend = backend
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.api_key = api_key
        self.timeout = timeout

    def embed(self, texts: List[str]) -> List[List[float]]:
        if self.backend == "ollama":
            return self._embed_ollama(texts)
        elif self.backend in ("openai", "openai-compatible"):
            return self._embed_openai(texts)
        else:
            raise ValueError(f"不支持的 embedding 后端: {self.backend}")

    def embed_single(self, text: str) -> List[float]:
        return self.embed([text])[0]

    def _embed_ollama(self, texts: List[str]) -> List[List[float]]:
        url = f"{self.base_url}/api/embeddings"
        result = []
        for text in texts:
            data = json.dumps({"model": self.model, "prompt": text}).encode("utf-8")
            req = urllib.request.Request(url, data=data, method="POST")
            req.add_header("Content-Type", "application/json")
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    body = json.loads(resp.read().decode("utf-8"))
            except urllib.error.URLError as e:
                raise RuntimeError(f"Ollama embedding 请求失败: {e}\n"
                                   f"请确认 Ollama 已启动且已拉取模型: ollama pull {self.model}")
            result.append(body["embedding"])
        return result

    def _embed_openai(self, texts: List[str]) -> List[List[float]]:
        url = f"{self.base_url}/v1/embeddings"
        data = json.dumps({"model": self.model, "input": texts}).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        if self.api_key:
            req.add_header("Authorization", f"Bearer {self.api_key}")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                body = json.loads(resp.read().decode("utf-8"))
        except urllib.error.URLError as e:
            raise RuntimeError(f"OpenAI embedding 请求失败: {e}\n"
                               f"请确认 embedding URL 和 API key 正确")
        data_list = body.get("data", [])
        data_list.sort(key=lambda x: x["index"])
        return [item["embedding"] for item in data_list]

    @staticmethod
    def detect_dim(backend: str, base_url: str, model: str,
                   api_key: Optional[str] = None) -> int:
        """发送一个短文本以检测 embedding 维度。"""
        client = EmbeddingClient(backend, base_url, model, api_key)
        vec = client.embed_single("测试维度")
        return len(vec)


# ---------------------------------------------------------------------------
# Qdrant
# ---------------------------------------------------------------------------
_EMBEDDING_MAX_LEN = 8192


def _truncate_text(text: str, max_len: int = _EMBEDDING_MAX_LEN) -> str:
    return text if len(text) <= max_len else text[:max_len]


def create_qdrant_client(host: str, port: int,
                         prefer_grpc: bool = True) -> "QdrantClient":
    from qdrant_client import QdrantClient
    client = QdrantClient(host=host, port=port, prefer_grpc=prefer_grpc)
    print(f"[QDRANT] 已连接 {host}:{port} (grpc={prefer_grpc})")
    return client


def create_or_get_collection_qdrant(client: "QdrantClient",
                                    collection_name: str,
                                    dim: int = BGE_M3_DIM,
                                    drop_if_exists: bool = False) -> None:
    from qdrant_client.models import Distance, VectorParams

    if drop_if_exists:
        try:
            client.delete_collection(collection_name)
            print(f"[QDRANT] 已删除旧 Collection: {collection_name}")
        except Exception:
            pass

    collections = [c.name for c in client.get_collections().collections]
    if collection_name in collections:
        print(f"[QDRANT] 使用已有 Collection: {collection_name}")
        return

    client.create_collection(
        collection_name=collection_name,
        vectors_config=VectorParams(
            size=dim,
            distance=Distance.COSINE,
        ),
        hnsw_config=None,  # 使用默认 HNSW 配置
    )
    print(f"[QDRANT] 新建 Collection: {collection_name} (dim={dim}, cosine)")


def insert_to_qdrant(client: "QdrantClient", chunks: List[Dict],
                     collection_name: str,
                     batch_size: int = 50) -> int:
    from qdrant_client.models import PointStruct

    total = 0
    for batch_start in range(0, len(chunks), batch_size):
        batch = chunks[batch_start: batch_start + batch_size]
        points = [
            PointStruct(
                id=c["id"],
                vector=c["embedding"],
                payload={
                    "source": c["source"],
                    "section": c["section"],
                    "text": _truncate_text(c["text"]),
                    "char_count": c["char_count"],
                },
            )
            for c in batch
        ]
        client.upsert(collection_name=collection_name, points=points)
        total += len(batch)

    print(f"[QDRANT] 写入 {total} 条向量")
    return total


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------
def main() -> None:
    parser = argparse.ArgumentParser(
        description="日程规划语料处理管线：去重 → BGE-M3 Embedding → Qdrant 入库",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例：
  # 全流程：去重 + ollama BGE-M3 + Qdrant
  python dedup_corpus.py -i ./rag_data --embedding-backend ollama --qdrant

  # 使用 OpenAI 兼容 API
  python dedup_corpus.py -i ./rag_data --embedding-backend openai \\
      --embedding-url https://api.siliconflow.cn \\
      --embedding-model BAAI/bge-m3 \\
      --embedding-api-key sk-xxx --qdrant

  # 只去重导出 JSON
  python dedup_corpus.py -i ./rag_data --skip-embedding
        """,
    )
    # -- 输入/输出 --
    parser.add_argument("-i", "--input", default=None, help="语料目录路径（.md 文件）")
    parser.add_argument("-H", "--history", default=None, help="用户历史 JSON 路径（可选）")
    parser.add_argument("-o", "--output", default=None, help="输出目录（默认 ./output）")

    # -- 去重参数 --
    parser.add_argument("--tfidf-threshold", type=float, default=None, help="TF-IDF 阈值（默认 0.92）")
    parser.add_argument("--simhash-distance", type=int, default=None, help="SimHash 汉明距离（默认 3）")
    parser.add_argument("--minhash-threshold", type=float, default=None, help="MinHash Jaccard 阈值（默认 0.8）")
    parser.add_argument("--minhash-perm", type=int, default=None, help="MinHash 排列数（默认 128）")
    parser.add_argument("--no-minhash", action="store_true", default=None, help="跳过 MinHash")
    parser.add_argument("--skip-dedup", action="store_true", default=None, help="跳过去重")

    # -- embedding --
    parser.add_argument("--skip-embedding", action="store_true", default=None, help="跳过 embedding + Qdrant")
    parser.add_argument("--embedding-backend", default=None,
                        choices=["ollama", "openai", "openai-compatible"],
                        help="embedding 后端（默认 ollama）")
    parser.add_argument("--embedding-url", default=None,
                        help="embedding API 地址（默认 http://localhost:11434）")
    parser.add_argument("--embedding-model", default=None,
                        help="embedding 模型名（默认 bge-m3）")
    parser.add_argument("--embedding-api-key", default=None,
                        help="API key（OpenAI 兼容后端需要）")
    parser.add_argument("--embedding-batch", type=int, default=None,
                        help="embedding 批大小（默认 16）")

    # -- Qdrant --
    parser.add_argument("--qdrant", action="store_true", default=None, help="启用 Qdrant 入库")
    parser.add_argument("--qdrant-host", default=None, help="Qdrant 地址（默认 localhost）")
    parser.add_argument("--qdrant-port", type=int, default=None, help="Qdrant gRPC 端口（默认 6334）")
    parser.add_argument("--qdrant-collection", default=None,
                        help="Qdrant Collection 名（默认 rag_corpus）")
    parser.add_argument("--qdrant-drop", action="store_true", default=None,
                        help="入库前删除已有 Collection 并重建")

    raw_args = parser.parse_args()

    # ---- IDE 模式：无命令行参数时自动使用 IDE_DEFAULTS ----
    ide_mode = len(sys.argv) <= 1
    if ide_mode:
        print("[IDE] 未检测到命令行参数，使用 IDE_DEFAULTS 配置\n")
        d = IDE_DEFAULTS
        # 构造一个等效的 args 对象
        class IdeArgs:
            pass
        args = IdeArgs()
        for key, val in d.items():
            setattr(args, key, val)
    else:
        args = raw_args
        # 对 None 的字段回填默认值
        _fallback = {
            "input": "./rag_data", "output": "./output", "history": None,
            "skip_dedup": False, "no_minhash": False, "skip_embedding": False,
            "embedding_backend": "ollama", "embedding_url": "http://localhost:11434",
            "embedding_model": "bge-m3", "embedding_api_key": None, "embedding_batch": 16,
            "qdrant": False, "qdrant_host": "localhost", "qdrant_port": 6334,
            "qdrant_collection": "rag_corpus", "qdrant_drop": False,
            "tfidf_threshold": 0.92, "simhash_distance": 3,
            "minhash_threshold": 0.8, "minhash_perm": 128,
        }
        for key, fallback_val in _fallback.items():
            if getattr(args, key, None) is None:
                setattr(args, key, fallback_val)

    if not args.input:
        print("[ERROR] 未指定输入目录（-i 或 IDE_DEFAULTS['input']）")
        sys.exit(1)

    input_dir = Path(args.input)
    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    if not input_dir.exists():
        print(f"[ERROR] 输入目录不存在: {input_dir}")
        sys.exit(1)

    # =====================================================================
    # 阶段 0: 加载
    # =====================================================================
    md_files = sorted(input_dir.glob("*.md"))
    if not md_files:
        print(f"[ERROR] 未找到 .md 文件: {input_dir}")
        sys.exit(1)

    all_chunks: List[Dict] = []
    print("[STAGE 0] 加载语料...")
    for fp in md_files:
        chunks = chunk_markdown(str(fp))
        print(f"  {fp.name}: {len(chunks)} chunks")
        all_chunks.extend(chunks)

    if args.history:
        hp = Path(args.history)
        if hp.exists():
            hist_chunks = load_user_history(str(hp))
            print(f"  {hp.name}: {len(hist_chunks)} chunks (用户历史)")
            all_chunks.extend(hist_chunks)
        else:
            print(f"[WARN] 用户历史文件不存在: {hp}")

    total_before = len(all_chunks)
    print(f"  共 {total_before} chunks\n")

    if total_before == 0:
        print("[WARN] 没有可处理的语料")
        return

    # =====================================================================
    # 阶段 1-3: 去重
    # =====================================================================
    tfidf_removed = simhash_removed = minhash_removed = 0

    if not args.skip_dedup:
        t0 = time.perf_counter()

        all_chunks, tfidf_removed = dedup_tfidf(all_chunks, args.tfidf_threshold)
        t1 = time.perf_counter()
        print(f"[STAGE 1] TF-IDF   (thresh={args.tfidf_threshold})  "
              f"移除 {tfidf_removed}, 剩余 {len(all_chunks)}, {t1 - t0:.2f}s")

        all_chunks, simhash_removed = dedup_simhash(all_chunks, args.simhash_distance)
        t2 = time.perf_counter()
        print(f"[STAGE 2] SimHash  (dist={args.simhash_distance})   "
              f"移除 {simhash_removed}, 剩余 {len(all_chunks)}, {t2 - t1:.2f}s")

        if not args.no_minhash:
            all_chunks, minhash_removed = dedup_minhash(
                all_chunks, num_perm=args.minhash_perm,
                jaccard_threshold=args.minhash_threshold,
            )
            t3 = time.perf_counter()
            print(f"[STAGE 3] MinHash  (jaccard>={args.minhash_threshold})  "
                      f"移除 {minhash_removed}, 剩余 {len(all_chunks)}, {t3 - t2:.2f}s")
        else:
            t3 = time.perf_counter()
            print("[STAGE 3] MinHash  — 已跳过")

        dedup_time = t3 - t0
    else:
        t3 = time.perf_counter()
        dedup_time = 0
        print("[STAGE 1-3] 去重 — 已跳过")

    total_removed = tfidf_removed + simhash_removed + minhash_removed
    print(f"  去重后: {len(all_chunks)} chunks\n")

    # ---- 分配 ID（Qdrant 要求 int 或 UUID）----
    for idx, c in enumerate(all_chunks):
        c["id"] = idx

    # =====================================================================
    # 阶段 4: BGE-M3 Embedding
    # =====================================================================
    if not args.skip_embedding:
        print(f"[STAGE 4] BGE-M3 Embedding ({args.embedding_backend})")
        print(f"  URL: {args.embedding_url}, model: {args.embedding_model}")

        emb_client = EmbeddingClient(
            backend=args.embedding_backend,
            base_url=args.embedding_url,
            model=args.embedding_model,
            api_key=args.embedding_api_key,
        )

        # 探测维度
        dim = EmbeddingClient.detect_dim(
            args.embedding_backend, args.embedding_url,
            args.embedding_model, args.embedding_api_key,
        )
        print(f"  检测到 embedding 维度: {dim}")

        t_emb = time.perf_counter()
        texts = [c["text"] for c in all_chunks]
        all_embeddings: List[List[float]] = []
        batch = args.embedding_batch
        for i in range(0, len(texts), batch):
            batch_texts = texts[i: i + batch]
            embs = emb_client.embed(batch_texts)
            all_embeddings.extend(embs)
            print(f"  embedding 进度: {min(i + batch, len(texts))}/{len(texts)}")

        for c, emb in zip(all_chunks, all_embeddings):
            c["embedding"] = emb

        emb_time = time.perf_counter() - t_emb
        print(f"  embedding 完成, 耗时: {emb_time:.2f}s\n")
    else:
        dim = BGE_M3_DIM
        emb_time = 0
        print("[STAGE 4] Embedding — 已跳过\n")

    # =====================================================================
    # 阶段 5: Qdrant 入库
    # =====================================================================
    if args.qdrant:
        if args.skip_embedding:
            print("[WARN] 跳过 embedding 时无法写入 Qdrant（缺少向量），"
                  "请移除 --skip-embedding")
        else:
            print(f"[STAGE 5] Qdrant 入库 → {args.qdrant_host}:{args.qdrant_port}"
                  f" / {args.qdrant_collection}")
            t_qd = time.perf_counter()
            try:
                qdrant_client = create_qdrant_client(
                    args.qdrant_host, args.qdrant_port)
                create_or_get_collection_qdrant(
                    qdrant_client, args.qdrant_collection, dim=dim,
                    drop_if_exists=args.qdrant_drop,
                )
                inserted = insert_to_qdrant(
                    qdrant_client, all_chunks, args.qdrant_collection)
                qd_time = time.perf_counter() - t_qd
                print(f"  Qdrant 入库完成, {inserted} 条, 耗时: {qd_time:.2f}s\n")
            except ImportError:
                print("[ERROR] 未安装 qdrant-client，请执行: pip install qdrant-client")
                sys.exit(1)
            except Exception as e:
                print(f"[ERROR] Qdrant 入库失败: {e}")
                sys.exit(1)
    else:
        print("[STAGE 5] Qdrant 入库 — 未启用\n")

    # =====================================================================
    # 输出 JSON（始终导出）
    # =====================================================================
    export_chunks = []
    for c in all_chunks:
        item = {
            "id": c["id"],
            "source": c["source"],
            "section": c["section"],
            "text": c["text"],
            "char_count": c["char_count"],
        }
        # embedding 数组不写 JSON（体积太大），仅写入维度标记
        if "embedding" in c:
            item["embedding_dim"] = len(c["embedding"])
        export_chunks.append(item)

    out_file = output_dir / "dedup_chunks.json"
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(export_chunks, f, ensure_ascii=False, indent=2)
    print(f"[OUTPUT] chunks → {out_file}")

    stats = {
        "total_before": total_before,
        "tfidf_removed": tfidf_removed,
        "simhash_removed": simhash_removed,
        "minhash_removed": minhash_removed,
        "total_removed": total_removed,
        "final_count": len(all_chunks),
        "dedup_ratio": f"{(total_removed / total_before * 100):.1f}%"
                       if total_before > 0 else "0%",
        "embedding": {
            "backend": args.embedding_backend,
            "model": args.embedding_model,
            "dim": dim,
            "skipped": args.skip_embedding,
        },
        "qdrant": {
            "enabled": args.qdrant,
            "collection": args.qdrant_collection,
        },
        "sources": {
            "md_files": [f.name for f in md_files],
            "history_file": os.path.basename(args.history) if args.history else None,
        },
    }
    stats_file = output_dir / "dedup_stats.json"
    with open(stats_file, "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2)
    print(f"[OUTPUT] 统计 → {stats_file}")

    print(f"""
{'=' * 50}
  处理完成
{'=' * 50}
  初始数量      : {total_before}
  TF-IDF 移除   : {tfidf_removed}
  SimHash 移除  : {simhash_removed}
  MinHash 移除  : {minhash_removed}
  最终数量      : {len(all_chunks)}
  去重率        : {stats['dedup_ratio']}
  Embedding     : {'跳过' if args.skip_embedding else f'{args.embedding_backend}/{args.embedding_model} ({dim}d)'}
  Qdrant        : {'跳过' if not args.qdrant else f'{args.qdrant_host}:{args.qdrant_port}/{args.qdrant_collection}'}
{'=' * 50}
""")


if __name__ == "__main__":
    main()
