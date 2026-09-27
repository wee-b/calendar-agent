"""Shared async Redis connection pool."""

from functools import lru_cache

import redis.asyncio as redis

from app.core.config import get_settings


@lru_cache
def get_redis() -> redis.Redis:
    url = get_settings().redis_url
    if not url:
        raise RuntimeError("REDIS_URL 未配置")
    return redis.from_url(url, decode_responses=True, socket_timeout=3)
