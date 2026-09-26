import httpx
from fastapi import HTTPException, status

from app.auth.schemas import JavaUserInfo, JavaUserResponse
from app.core.config import get_settings


class JavaAuthClient:
    def __init__(self) -> None:
        settings = get_settings()
        self.base_url = settings.java_base_url.rstrip("/")
        self.token_header_name = settings.token_header_name
        self.timeout = settings.java_request_timeout

    async def get_current_user(self, token: str) -> JavaUserInfo:
        try:
            async with httpx.AsyncClient(
                    base_url=self.base_url,
                    timeout=self.timeout,
            ) as client:
                response = await client.get(
                    "/user/info",
                    headers={self.token_header_name: token},
                )
        except httpx.TimeoutException as exc:
            raise HTTPException(
                status_code=status.HTTP_504_GATEWAY_TIMEOUT,
                detail="Java 用户服务响应超时",
            ) from exc
        except httpx.RequestError as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="无法连接 Java 用户服务",
            ) from exc

        try:
            body = JavaUserResponse.model_validate(response.json())
        except (ValueError, TypeError) as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="Java 用户服务返回格式异常",
            ) from exc

        # Java 拦截器可能返回 HTTP 200 + ok=false，不能只检查 HTTP 状态。
        if not response.is_success or not body.ok or body.data is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail=body.msg or "登录状态无效",
            )

        return body.data