"""为 Planner 读取已有有效记忆；仅访问 AI 表。"""

from sqlalchemy import text

from app.db.session import get_session_factory


PREFERENCE_TYPES = {"PREFERENCE_TIME", "PREFERENCE_LOAD", "PREFERENCE_STYLE",
                    "AVOIDANCE", "DOMAIN_PREFERENCE", "LONG_TERM_GOAL"}


class PlanningMemoryRepository:
    async def relevant(self, user_id: int, requirement: str) -> list[dict]:
        async with get_session_factory()() as session:
            rows = [dict(row) for row in (await session.execute(text(
                "SELECT memory_type, content, confidence, update_time FROM yl_user_memory "
                "WHERE user_id=:user_id AND status='active' "
                "AND source <> 'behavior' AND memory_type <> 'BEHAVIOR_PATTERN' "
                "AND (expire_time IS NULL OR expire_time > CURRENT_TIMESTAMP) "
                "ORDER BY confidence DESC, update_time DESC"
            ), {"user_id": user_id})).mappings()]
        words = ("刷题", "学习", "复习", "考试", "hot100", "英语", "四级", "六级", "考研", "驾照", "周末", "晚上", "早上")
        priorities = {"AVOIDANCE": 30, "PREFERENCE_LOAD": 30, "PREFERENCE_TIME": 30,
                      "LONG_TERM_GOAL": 20}
        def score(row):
            return priorities.get(row["memory_type"], 10) + 20 * sum(
                word in requirement.lower() and word in row["content"].lower() for word in words)
        return sorted(rows, key=score, reverse=True)[:8]
