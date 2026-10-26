"""工具尝试先记录后执行；写记录失败时禁止发出写请求。"""

from datetime import datetime, timezone

from sqlalchemy import case, func, select, update
from sqlalchemy.exc import IntegrityError

from app.db.session import get_session_factory, session_factory_for_engine
from app.models.tool_call import ToolCall
from app.core.exception.error_code import ErrorCode
from app.core.exception.exceptions import BusinessException


class ToolCallRepository:
    def __init__(self, engine=None):
        self.sessions = session_factory_for_engine(engine) if engine is not None else get_session_factory()

    async def start(self, values: dict) -> None:
        try:
            async with self.sessions() as session, session.begin():
                session.add(ToolCall(**values))
        except IntegrityError as exc:
            # 仅唯一键冲突解释为重复写，其他约束错误继续暴露。
            if values.get("operation_key") and getattr(exc.orig, "args", (None,))[0] == 1062:
                raise BusinessException(ErrorCode.MODEL_TOOL_DUPLICATE) from exc
            raise

    async def finish(self, attempt_id: str, values: dict) -> None:
        async with self.sessions() as session, session.begin():
            result = await session.execute(update(ToolCall).where(
                ToolCall.attempt_id == attempt_id, ToolCall.status == "running",
            ).values(**values, finished_at=datetime.now(timezone.utc).replace(tzinfo=None)))
            if result.rowcount != 1:
                raise RuntimeError("工具尝试终态更新失败")

    async def list_calls(self, user_id: int, session_id: str, before_id: int | None, limit: int):
        conditions = [ToolCall.user_id == user_id, ToolCall.session_id == session_id]
        if before_id is not None:
            conditions.append(ToolCall.id < before_id)
        async with self.sessions() as session:
            rows = (await session.scalars(select(ToolCall).where(*conditions)
                .order_by(ToolCall.id.desc()).limit(limit + 1))).all()
        return rows[:limit], len(rows) > limit

    async def stats(self, user_id: int, start: datetime, end: datetime,
                    session_id: str | None = None, turn_id: str | None = None):
        conditions = [ToolCall.user_id == user_id, ToolCall.started_at >= start, ToolCall.started_at < end]
        if session_id is not None:
            conditions.append(ToolCall.session_id == session_id)
        if turn_id is not None:
            conditions.append(ToolCall.turn_id == turn_id)
        # P95 为 nearest-rank，不在进程内加载全部样本。
        terminal = select(
            ToolCall.tool_name, ToolCall.status, ToolCall.elapsed_ms,
            func.row_number().over(partition_by=ToolCall.tool_name,
                                   order_by=ToolCall.elapsed_ms).label("rn"),
            func.count().over(partition_by=ToolCall.tool_name).label("n"),
        ).where(*conditions, ToolCall.status != "running").subquery()
        tool_stats = select(
            terminal.c.tool_name, func.count().label("count"),
            *[func.sum(case((terminal.c.status == status, 1), else_=0)).label(status)
              for status in ("success", "timeout", "error", "cancelled")],
            func.avg(terminal.c.elapsed_ms).label("avg_ms"),
            func.max(case((terminal.c.rn == func.ceil(terminal.c.n * .95),
                           terminal.c.elapsed_ms))).label("p95_ms"),
        ).group_by(terminal.c.tool_name)
        ordered = select(ToolCall.elapsed_ms,
            func.row_number().over(order_by=ToolCall.elapsed_ms).label("rn"),
            func.count().over().label("n"),
        ).where(*conditions, ToolCall.status != "running").subquery()
        turns = select(ToolCall.turn_id, func.count().label("count"),
                       func.count(func.distinct(ToolCall.logical_call_id)).label("logical_count"))\
            .where(*conditions).group_by(ToolCall.turn_id).subquery()
        async with self.sessions() as session:
            async with session.begin():
                tools = [dict(row) for row in (await session.execute(tool_stats)).mappings()]
                total = (await session.execute(select(
                    func.count().label("count"),
                    func.sum(case((ToolCall.status == "running", 1), else_=0)).label("running"),
                    func.sum(case((ToolCall.outcome == "uncertain", 1), else_=0)).label("uncertain"),
                ).where(*conditions))).mappings().one()
                per_turn = (await session.execute(select(
                    func.count().label("turns_with_calls"), func.avg(turns.c.count).label("avg_calls"),
                    func.max(turns.c.count).label("max_calls"),
                    func.coalesce(func.sum(turns.c.logical_count), 0).label("logical_count"),
                ))).mappings().one()
                overall_p95 = await session.scalar(select(func.max(ordered.c.elapsed_ms)).where(
                    ordered.c.rn == func.ceil(ordered.c.n * .95)))
        for item in tools:
            for key in ("count", "success", "timeout", "error", "cancelled"):
                item[key] = int(item[key])
            item["avg_ms"] = float(item["avg_ms"])
            item["success_rate"] = item["success"] / item["count"]
        completed = {key: sum(item[key] for item in tools)
                     for key in ("count", "success", "timeout", "error", "cancelled")}
        count = completed["count"]
        completed.update(success_rate=completed["success"] / count if count else None,
            avg_ms=sum(item["avg_ms"] * item["count"] for item in tools) / count if count else None,
            p95_ms=overall_p95)
        return {"count": total["count"], "running": total["running"] or 0,
                "uncertain": total["uncertain"] or 0, "completed": completed,
                "tools": tools, "per_turn": dict(per_turn)}
