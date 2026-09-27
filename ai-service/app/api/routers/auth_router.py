from fastapi import APIRouter, Request

from app.helper.java_auth_client import JavaAuthClient
from app.schemas.auth import JavaUserInfo

router = APIRouter(prefix="/auth", tags=["认证"])


@router.get("/me", response_model=JavaUserInfo)
async def current_user(request: Request):
    # Middleware has already verified the token in Redis. Java supplies profile data.
    return await JavaAuthClient().get_current_user(request.state.user_token)
