from fastapi import HTTPException, Request
from fastapi.responses import JSONResponse

from app.cache.token_cache import RedisTokenVerifier
from app.core.config import get_settings
from app.core.exception.exceptions import BusinessException


async def authenticate_request(request: Request, call_next):
    """从请求头校验 Redis token，并把可信用户 ID 留给业务路由。"""

    if request.url.path == "/auth/me" or request.url.path == "/chat" \
            or request.url.path.startswith("/chat/") or request.url.path == "/rag/search":
        token = request.headers.get(get_settings().token_header_name)
        try:
            user_id = await RedisTokenVerifier().verify(token or "")
        except BusinessException as exc:
            return JSONResponse(status_code=exc.status_code, content={"detail": str(exc)})
        except HTTPException as exc:
            return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})
        request.state.user_id = user_id
        request.state.user_token = token
    return await call_next(request)
