from collections.abc import Sequence

from pydantic import BaseModel

from app.core.response.models import FailResponse, SuccessResponse


def success(data: BaseModel | Sequence[BaseModel]) -> dict[str, object]:
    return SuccessResponse(data=data).model_dump()


def fail(code: int, msg: str) -> dict[str, object]:
    return FailResponse(code=code, msg=msg).model_dump()
