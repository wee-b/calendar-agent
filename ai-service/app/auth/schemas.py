from pydantic import BaseModel, Field


class JavaUserInfo(BaseModel):
    user_id: int = Field(alias="userId")
    user_code: str | None = Field(default=None, alias="userCode")
    user_name: str | None = Field(default=None, alias="userName")
    phone: str | None = None
    avatar: str | None = None
    gender: int | None = None


class JavaUserResponse(BaseModel):
    code: int
    ok: bool
    msg: str | None = None
    data: JavaUserInfo | None = None