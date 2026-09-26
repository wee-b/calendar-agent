from fastapi import APIRouter, Header, HTTPException, status

from app.auth.java_auth_client import JavaAuthClient
from app.auth.schemas import JavaUserInfo

router = APIRouter(prefix="/auth", tags=["认证"])


@router.get("/me", response_model=JavaUserInfo)
async def current_user(
    token: str | None = Header(default=None, alias="yvli-token"),
):
    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="缺少 yvli-token",
        )

    return await JavaAuthClient().get_current_user(token)
