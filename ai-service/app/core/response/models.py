from typing import Generic, TypeVar

from pydantic import BaseModel


T = TypeVar("T")


class SuccessResponse(BaseModel, Generic[T]):
    code: int = 0
    ok: bool = True
    msg: str = "操作成功"
    data: T


class FailResponse(BaseModel):
    code: int
    ok: bool = False
    msg: str
    data: None = None
