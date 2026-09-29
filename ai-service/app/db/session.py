"""异步 MySQL 引擎及会话工厂；聊天仓库通过它读取和保存对话。"""

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
    """允许仓库测试注入引擎；提交后对象属性继续可读。"""

    return async_sessionmaker(engine, expire_on_commit=False)


@lru_cache
def get_session_factory() -> async_sessionmaker[AsyncSession]:
    return session_factory_for_engine(get_engine())
