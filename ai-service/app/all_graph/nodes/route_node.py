"""RouteAgent 自行读取对话上下文，RouteNode 将识别过程接入会话主图。"""

from __future__ import annotations

from datetime import datetime
import json
from typing import TYPE_CHECKING
from zoneinfo import ZoneInfo

from pydantic import BaseModel

from app.core.config.agent.agents import route_config
from app.helper.model_client import ModelClient
from app.schemas.statemachine.flow import ConversationStage, PendingTask, UserSignal

if TYPE_CHECKING:
    from app.service.chat import ChatService


ROUTE_PROMPT = """你是 RouteAgent。结合会话主流程、当前任务、上一轮助手回复和本轮用户输入，只输出结构化判断。
你不生成最终回复、不调用工具、不选择 Agent、不决定下一会话阶段。
只输出 JSON：{"userSignal":"NEW_CHAT","task":"本轮明确任务","publicSummary":"给用户看的简短处理说明"}
publicSummary 用一句自然中文概括你对本轮请求的理解和接下来要做的事，30 到 90 字；不要输出内部推理、工具参数，也不要声称尚未完成的操作已经完成。
userSignal 只能是以下十种：
- NEW_CHAT：独立的闲聊或普通问答。
- NEW_QUERY：新的只读日程查询，包括在规划、执行确认或生图过程中临时查询。
- NEW_PLAN：明确开始新的学习、工作、旅行等规划；IMAGE 中修改规划内容也属于 NEW_PLAN，task 应结合原规划概括完整需求。
- NEW_EXECUTE：明确提出新的日历增删改操作，包括单日、周期、跨日和日记写入。
- CONFIRM：确认当前流程正在询问的事项，不提供新的修改内容。
- REJECT：明确取消当前任务。
- MODIFY：补充或修改当前任务。PLAN 中修改需求或草稿；EXECUTE 中修改待执行内容；IMAGE 中修改图片外观。
- UNKNOWN：无法可靠判断意图、指代不明或是否授权不明确。
- SYNC_PLAN：明确要求将当前规划草稿同步到日历。
- GENERATE_PLAN_IMAGE：明确要求为当前规划生成图片或示意图。
判断顺序：
1. 先判断是否在回应当前任务，再判断是否开始新任务。
2. “每天改成两小时”等当前任务的补充是 MODIFY；明确换一项任务才是 NEW_PLAN / NEW_EXECUTE。
3. “另外查明天的安排”是 NEW_QUERY，不取消原任务。
4. “好的/可以/需要”只表示 CONFIRM，绝不能推断成 SYNC_PLAN 或 GENERATE_PLAN_IMAGE。
5. 如果上一轮是临时闲聊或查询，“好的”等仅回应上一轮回答时使用 NEW_CHAT，不能把它当作对更早写操作的确认。
6. 图片生成后，颜色、布局、字体等修改是 MODIFY；改变规划内容则是 NEW_PLAN。同步图片对应的原规划仍是 SYNC_PLAN。
7. 否定某个条件不等于取消任务：“不要早起，改到晚上”是 MODIFY。
8. 所有明确的新写操作都属于 NEW_EXECUTE，由程序统一要求确认；不要输出 READY_*、NEW_REQUEST 或 Agent 名称。
9. task 应保持本轮用户原意，结合当前日期把相对日期展开为明确日期；禁止添加用户未提出的写操作。"""


class RouteDecision(BaseModel):
    signal: UserSignal
    task: str | None = None
    public_summary: str | None = None


class RouteAgent:
    """模型输出信号、任务和公开说明；阶段与处理节点仍由转换表决定。

    增加意图时同步修改 UserSignal、ROUTE_PROMPT 和 ChatTransitionTable。
    """

    def __init__(
        self, model: ModelClient | None = None, chat_service: ChatService | None = None,
    ) -> None:
        self.model = model if model is not None else ModelClient(route_config)
        if chat_service is None:
            from app.service.chat import ChatService

            chat_service = ChatService()
        self.chat_service = chat_service

    async def route(
        self, user_id: int, session_id: str, message: str,
        stage: ConversationStage, pending: PendingTask,
    ) -> RouteDecision:
        # 每轮从 ChatService 读取最近助手回复及其后的用户消息；当前输入尚未入库。
        context = await self.chat_service.get_route_context(
            user_id, session_id, current_message=message,
        )
        today = datetime.now(ZoneInfo("Asia/Shanghai")).date().isoformat()
        system = (
            f"{ROUTE_PROMPT}\n当前日期：{today}\n会话阶段：{stage.name}"
            f"\n当前任务：{pending.task or '无'}"
            f"\n是否已有规划草稿：{pending.draft_id is not None}"
            f"\n规划摘要：{(pending.plan_preview or '')[-2000:]}"
            f"\n图片要求：{pending.image_instruction or ''}"
        )
        previous = context.previousReply.content[-1500:] if context.previousReply else ""
        messages = "\n".join(item.content for item in context.userMessages)
        answer = await self.model.complete([
            {"role": "system", "content": system},
            {"role": "user", "content": f"上一轮助手回复：\n{previous}\n\n本轮连续用户消息：\n{messages}"},
        ])
        return self.parse(answer)

    @staticmethod
    def parse(answer: str | None) -> RouteDecision:
        """非法或无法识别的模型输出一律降级为 UNKNOWN。"""
        if not answer:
            return RouteDecision(signal=UserSignal.UNKNOWN)
        start, end = answer.find("{"), answer.rfind("}")
        if start < 0 or end <= start:
            return RouteDecision(signal=UserSignal.UNKNOWN)
        try:
            payload = json.loads(answer[start:end + 1])
            if not isinstance(payload, dict):
                raise ValueError("route response must be an object")
            signal = UserSignal[payload["userSignal"]]
            task = payload.get("task")
            if task is not None and not isinstance(task, str):
                raise ValueError("task must be text")
            summary = payload.get("publicSummary")
            if not isinstance(summary, str):
                summary = None
            else:
                summary = " ".join(summary.split())[:180] or None
            return RouteDecision(signal=signal, task=task.strip() or None if task is not None else None,
                                 public_summary=summary)
        except (ValueError, KeyError, TypeError):
            return RouteDecision(signal=UserSignal.UNKNOWN)


class RouteNode:
    """LangGraph 可调用节点；框架会调用 __call__ 并合并返回的状态增量。

    RouteAgent 自行调用 ChatService.get_route_context 获取历史；图只传本轮输入
    和从状态仓储恢复的阶段与待处理任务，不在此处决定目标 Agent。
    """

    def __init__(self, agent: RouteAgent) -> None:
        self.agent = agent

    async def __call__(self, state: dict) -> dict:
        decision = await self.agent.route(
            state["user_id"], state["session_id"], state["message"],
            state["stage"], state["pending"],
        )
        return {"signal": decision.signal, "task": decision.task,
                "public_summary": decision.public_summary}
