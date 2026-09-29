"""Read-only validation of the current Sa-Token client token mapping.

This key format is specific to Sa-Token 1.44.0 and the Java ClientTokenConfig.
Keep a cross-service logout/expiry test when upgrading either side.
"""

from __future__ import annotations

import redis.asyncio as redis
from redis.exceptions import RedisError

from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException
from app.db.redis_client import get_redis
from app.utils.security import parse_login_id, token_key


class RedisTokenVerifier:
    """按 Java Sa-Token 的 client token 键格式验证登录态。"""

    def __init__(self, client: redis.Redis | None = None) -> None:
        self.client = client

    async def verify(self, token: str) -> int:
        """只从 Redis 映射解析用户 ID，不信任请求体提供的身份信息。"""

        if not token or len(token) > 512 or token != token.strip():
            raise BusinessException(ErrorCode.TOKEN_INVALID)
        key = token_key(token)
        try:
            login_id = await (self.client or get_redis()).get(key)
        except (RedisError, RuntimeError) as exc:
            raise BusinessException(ErrorCode.AUTH_UNAVAILABLE) from exc
        # Sa-Token's exceptional markers are non-numeric; user IDs are positive integers.
        user_id = parse_login_id(login_id)
        if user_id is None:
            raise BusinessException(ErrorCode.LOGIN_INVALID)
        return user_id
