"""RAG 语料、检索结果以及 Qdrant/Embedding 边界结构。"""

from math import isfinite
from dataclasses import dataclass

from pydantic import BaseModel, Field, field_validator


class RagSearchRequest(BaseModel):
    """独立检索接口的用户输入。"""

    query: str = Field(min_length=1, max_length=10000)


@dataclass(frozen=True)
class CorpusChunk:
    """Markdown 按标题拆分后的片段，source 和 section 用于检索结果溯源。"""

    source: str  # 原始 Markdown 文件名。
    section: str  # 所属标题路径。
    text: str


class RagHit(BaseModel):
    """返回给 /rag/search 的命中项，score 是融合或重排后的分数。"""

    id: str
    source: str = ""
    section: str = ""
    text: str = ""
    charCount: int = 0
    score: float = 0.0


class QdrantPayload(BaseModel):
    """向量点携带的语料字段；保留 Qdrant 的 char_count 命名。"""

    source: str = ""
    section: str = ""
    text: str = ""
    char_count: int = 0  # 原片段字符数，沿用现有 Qdrant payload 键。
    user_id: int | None = None
    file_id: int | None = None


class QdrantPoint(BaseModel):
    """Qdrant 查询和滚动接口的点；滚动结果没有 score 时取 0。"""

    id: str | int
    payload: QdrantPayload = Field(default_factory=QdrantPayload)
    score: float = 0.0  # scroll 不带相似度，dense search 会提供该值。


class QdrantUpsertPoint(BaseModel):
    """导入语料时写入 Qdrant 的 ID、向量和 payload。"""

    id: str
    vector: list[float]
    payload: QdrantPayload


class QdrantVectorConfig(BaseModel):
    size: int


class QdrantCollectionParams(BaseModel):
    vectors: QdrantVectorConfig


class QdrantCollectionConfig(BaseModel):
    params: QdrantCollectionParams


class QdrantCollectionInfo(BaseModel):
    """现有集合的向量配置，用于导入前检查 Embedding 维度。"""

    config: QdrantCollectionConfig


class EmbeddingItem(BaseModel):
    """远程 Embedding 或缓存中的向量；拒绝空值、布尔值和非有限数。"""

    embedding: list[float] = Field(min_length=1)

    @field_validator("embedding", mode="before")
    @classmethod
    def validate_vector(cls, value: object) -> list[float]:
        """先检查原始 JSON 数值，再统一转换为浮点数供检索使用。"""

        if (not isinstance(value, list) or not value
                or any(isinstance(item, bool) or not isinstance(item, (int, float))
                       or not isfinite(item) for item in value)):
            raise ValueError("invalid embedding vector")
        return [float(item) for item in value]


class EmbeddingResponse(BaseModel):
    """OpenAI-compatible Embedding 响应中实际使用的 data 列表。"""

    data: list[EmbeddingItem] = Field(min_length=1)
