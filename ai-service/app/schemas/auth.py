"""Java 用户信息接口的响应结构和字段别名。"""

from pydantic import BaseModel, Field


class JavaUserInfo(BaseModel):
    """保留 Java 的 camelCase JSON 字段，同时让 Python 使用 snake_case 属性。"""

    user_id: int = Field(alias="userId")
    user_code: str | None = Field(default=None, alias="userCode")
    user_name: str | None = Field(default=None, alias="userName")
    phone: str | None = None
    avatar: str | None = None
    gender: int | None = None


class JavaUserResponse(BaseModel):
    """Java 通用响应体；HTTP 200 仍可能携带 ok=false。"""

    code: int
    ok: bool
    msg: str | None = None
    data: JavaUserInfo | None = None
