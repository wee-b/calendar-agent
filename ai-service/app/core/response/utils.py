from typing import Any

from pydantic import BaseModel

from app.core.response.models import FailResponse, SuccessResponse


def success(data: BaseModel | dict[str, Any]) -> dict[str, Any]:
    payload = data.model_dump() if isinstance(data, BaseModel) else data
    return SuccessResponse(data=payload).model_dump()


def fail(code: int, msg: str) -> dict[str, Any]:
    return FailResponse(code=code, msg=msg).model_dump()
