"""封装 Qdrant REST 访问；返回前把点和集合信息转换为 schema 对象。"""

import httpx
from pydantic import ValidationError

from app.core.config.qdrant import get_qdrant_settings
from app.core.exception.exceptions import QdrantError
from app.schemas.rag import QdrantCollectionInfo, QdrantPoint, QdrantUpsertPoint


class QdrantRepository:
    """检索、滚动读取和导入语料共用的集合访问层。"""

    def __init__(self, client: httpx.AsyncClient | None = None):
        self.settings = get_qdrant_settings()
        self.client = client
        self.base = (self.settings.qdrant_url.rstrip("/") + "/collections/"
                     + self.settings.qdrant_collection)

    async def _request(self, method: str, path: str, payload: dict | None = None) -> dict:
        """检查 HTTP 与 Qdrant 通用 result 外壳，具体结果由各方法再校验。"""

        try:
            if self.client is None:
                async with httpx.AsyncClient(timeout=self.settings.qdrant_timeout) as client:
                    response = await client.request(method, self.base + path, json=payload)
            else:
                response = await self.client.request(method, self.base + path, json=payload)
            response.raise_for_status()
            body = response.json()
            if not isinstance(body, dict) or "result" not in body:
                raise ValueError("invalid Qdrant response")
            return body
        except (httpx.HTTPError, ValueError) as exc:
            raise QdrantError("Qdrant 请求或响应异常") from exc

    async def search(self, vector: list[float], limit: int) -> list[QdrantPoint]:
        result = (await self._request("POST", "/points/query", {
            "query": vector, "limit": limit, "with_payload": True,
            "params": {"hnsw_ef": 128},
        }))["result"]
        if not isinstance(result, dict) or not isinstance(result.get("points"), list):
            raise QdrantError("Qdrant 搜索结果格式异常")
        try:
            return [QdrantPoint.model_validate(point) for point in result["points"]]
        except ValidationError as exc:
            raise QdrantError("Qdrant 搜索结果格式异常") from exc

    async def scroll_all(self) -> list[QdrantPoint]:
        """分页读完整个集合，供本地中文 BM25 计算。"""

        points: list[QdrantPoint] = []
        offset = None
        while True:
            payload = {"limit": 1000, "with_payload": True, "with_vector": False}
            if offset is not None:
                payload["offset"] = offset
            result = (await self._request("POST", "/points/scroll", payload))["result"]
            if not isinstance(result, dict) or not isinstance(result.get("points"), list):
                raise QdrantError("Qdrant 滚动结果格式异常")
            try:
                points.extend(QdrantPoint.model_validate(point) for point in result["points"])
            except ValidationError as exc:
                raise QdrantError("Qdrant 滚动结果格式异常") from exc
            next_offset = result.get("next_page_offset")
            if next_offset is None:
                return points
            if next_offset == offset:
                raise QdrantError("Qdrant 滚动分页未前进")
            offset = next_offset

    async def collection_info(self) -> QdrantCollectionInfo | None:
        """集合不存在时返回 None；其他请求或格式错误继续上抛。"""

        try:
            return QdrantCollectionInfo.model_validate((await self._request("GET", ""))["result"])
        except QdrantError as exc:
            if isinstance(exc.__cause__, httpx.HTTPStatusError) and exc.__cause__.response.status_code == 404:
                return None
            raise
        except ValidationError as exc:
            raise QdrantError("Qdrant 集合信息格式异常") from exc

    async def create_collection(self, dimension: int) -> None:
        await self._request("PUT", "", {"vectors": {"size": dimension, "distance": "Cosine"}})

    async def delete_collection(self) -> None:
        if (await self._request("DELETE", ""))["result"] is not True:
            raise QdrantError("Qdrant 删除集合失败")

    async def upsert(self, points: list[QdrantUpsertPoint]) -> None:
        await self._request("PUT", "/points?wait=true", {
            "points": [point.model_dump() for point in points],
        })
