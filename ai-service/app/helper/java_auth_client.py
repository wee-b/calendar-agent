import httpx
from app.schemas.auth import JavaUserInfo, JavaUserResponse
from app.core.config import get_settings
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException


class JavaAuthClient:
    """转发已校验的用户 token，向 Java 查询个人资料。"""

    def __init__(self) -> None:
        settings = get_settings()
        self.base_url = settings.java_base_url.rstrip("/")
        self.token_header_name = settings.token_header_name
        self.timeout = settings.java_request_timeout

    async def get_current_user(self, token: str) -> JavaUserInfo:
        """同时检查 HTTP 状态和 Java 响应中的 ok 标志。"""

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
            raise BusinessException(ErrorCode.JAVA_USER_TIMEOUT) from exc
        except httpx.RequestError as exc:
            raise BusinessException(ErrorCode.JAVA_USER_UNAVAILABLE) from exc

        try:
            body = JavaUserResponse.model_validate(response.json())
        except (ValueError, TypeError) as exc:
            raise BusinessException(ErrorCode.JAVA_USER_RESPONSE_INVALID) from exc

        # Java 拦截器可能返回 HTTP 200 + ok=false，不能只检查 HTTP 状态。
        if not response.is_success or not body.ok or body.data is None:
            raise BusinessException(ErrorCode.LOGIN_INVALID,
                                    message=body.msg or None)

        return body.data
