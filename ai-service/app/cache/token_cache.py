"""Read-only validation of the current Sa-Token client token mapping.

This key format is specific to Sa-Token 1.44.0 and the Java ClientTokenConfig.
Keep a cross-service logout/expiry test when upgrading either side.
"""

from __future__ import annotations

import redis.asyncio as redis
from fastapi import HTTPException, status
from redis.exceptions import RedisError

from app.db.redis_client import get_redis
from app.utils.security import parse_login_id, token_key


class RedisTokenVerifier:
    def __init__(self, client: redis.Redis | None = None) -> None:
        self.client = client

    async def verify(self, token: str) -> int:
        if not token or len(token) > 512 or token != token.strip():
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="无效的用户 token")
        key = token_key(token)
        try:
            login_id = await (self.client or get_redis()).get(key)
        except (RedisError, RuntimeError) as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="鉴权服务暂不可用",
            ) from exc
        # Sa-Token's exceptional markers are non-numeric; user IDs are positive integers.
        user_id = parse_login_id(login_id)
        if user_id is None:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="登录状态无效")
        return user_id
