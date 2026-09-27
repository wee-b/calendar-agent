"""Async MySQL connection and session factories."""

from functools import lru_cache

from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import get_settings


@lru_cache
def get_engine() -> AsyncEngine:
    url = get_settings().database_url
    if not url:
        raise RuntimeError("DATABASE_URL 未配置")
    return create_async_engine(url, pool_pre_ping=True)


def session_factory_for_engine(engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(engine, expire_on_commit=False)


@lru_cache
def get_session_factory() -> async_sessionmaker[AsyncSession]:
    return session_factory_for_engine(get_engine())
