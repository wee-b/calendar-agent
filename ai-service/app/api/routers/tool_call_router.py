"""登录用户自己的工具统计及明细，不提供任意用户查询或未经核实的解锁。"""

from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, HTTPException, Query, Request

from app.core.response.utils import success
from app.repository.tool_call import ToolCallRepository
from app.schemas.tool_call import ToolCallItem, ToolCallPage, ToolStats


router = APIRouter(prefix="/chat", tags=["工具调用"])


@router.get("/tool-calls")
async def tool_calls(request: Request,
    session_id: str = Query(..., alias="sessionId", min_length=1, max_length=64, pattern=r"\S"),
    before_id: int | None = Query(None, alias="beforeId", gt=0),
    limit: int = Query(30, ge=1, le=100),
):
    rows, has_more = await ToolCallRepository().list_calls(request.state.user_id, session_id, before_id, limit)
    return success(ToolCallPage(items=[ToolCallItem.model_validate(row) for row in rows],
        has_more=has_more, next_before_id=rows[-1].id if has_more else None))


@router.get("/tool-stats")
async def tool_stats(request: Request,
    start: datetime | None = Query(None), end: datetime | None = Query(None),
    session_id: str | None = Query(None, alias="sessionId", min_length=1, max_length=64, pattern=r"\S"),
    turn_id: str | None = Query(None, alias="turnId", pattern=r"^[0-9a-f]{32}$"),
):
    # API 显式携带时区；默认最近24小时。内部统一为 UTC naive datetime。
    end = end or datetime.now(timezone.utc)
    start = start or end - timedelta(days=1)
    if start.tzinfo is None or end.tzinfo is None:
        raise HTTPException(422, "start/end 必须携带时区")
    start, end = start.astimezone(timezone.utc).replace(tzinfo=None), end.astimezone(timezone.utc).replace(tzinfo=None)
    if not timedelta(0) < end - start <= timedelta(days=31):
        raise HTTPException(422, "统计窗口须大于0且不超过31天")
    return success(ToolStats.model_validate(await ToolCallRepository().stats(
        request.state.user_id, start, end, session_id, turn_id)))
