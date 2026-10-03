"""收集一次输入的状态流转，在结束时统一输出三行日志。"""

from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
import logging
import re
from time import perf_counter
from uuid import uuid4


logger = logging.getLogger("app.flow")
chat_logger = logging.getLogger("app.all_graph.nodes.chat_node")


@dataclass
class FlowTrace:
    user_id: int
    session_id: str
    turn_id: str = field(default_factory=lambda: uuid4().hex)
    step: str = "START"
    started: float = field(default_factory=perf_counter)
    steps: list[str] = field(default_factory=list)
    turn_level: int = logging.INFO
    session_level: int = logging.INFO
    initial_stage: str = "未读取"
    final_stage: str = "未读取"
    target_stage: str | None = None
    session_outcome: str = "未读取"
    session_details: dict = field(default_factory=dict)
    user_text: str = ""
    completed_chat: tuple[str, str] | None = None

    def flush(self) -> None:
        elapsed = int((perf_counter() - self.started) * 1000)
        session_details = {"结果": self.session_outcome, **self.session_details}
        if self.target_stage is not None:
            session_details["目标阶段"] = self.target_stage
        _write(self, "会话流转", self.initial_stage, self.final_stage,
               session_details, self.session_level, elapsed)
        turn_details = {"路径": " → ".join(self.steps), "步骤数": len(self.steps)}
        _write(self, "单轮流转", "START", self.step, turn_details, self.turn_level, elapsed)
        if self.completed_chat is not None:
            agent_label, answer = self.completed_chat
            # 保留原有对话内容日志的格式与截断长度。
            from app.all_graph.nodes.chat_node import _log_completed_chat
            _log_completed_chat(self.user_text, agent_label, answer)
        else:
            chat_logger.warning("对话结束 | 用户输入：%s | 结果：%s",
                                text_preview(self.user_text), self.step)


_current: ContextVar[FlowTrace | None] = ContextVar("flow_trace", default=None)


@contextmanager
def bind_flow_trace(user_id: int, session_id: str):
    trace = FlowTrace(user_id, session_id)
    token = _current.set(trace)
    try:
        yield trace
    finally:
        try:
            trace.flush()
        finally:
            _current.reset(token)


def record_chat_input(message: str) -> None:
    trace = _current.get()
    if trace is not None:
        trace.user_text = message


def record_completed_chat(agent_label: str, answer: str) -> None:
    trace = _current.get()
    if trace is not None:
        trace.completed_chat = (agent_label, answer)


def text_preview(value: str, limit: int = 20) -> str:
    return "".join(" " if c.isspace() else c for c in value[:limit]) + ("…" if len(value) > limit else "")


def pending_summary(pending) -> dict:
    return {"有待处理任务": bool(pending.task), "草稿ID": pending.draft_id,
            "有规划正文": bool(pending.plan_preview), "有图片要求": bool(pending.image_instruction)}


def _pending_label(value: dict | None) -> str:
    if not value:
        return "无"
    parts = []
    if value.get("有待处理任务"):
        parts.append("任务")
    if value.get("草稿ID") is not None:
        parts.append(f"草稿#{value['草稿ID']}")
    if value.get("有规划正文") and value.get("草稿ID") is None:
        parts.append("规划正文")
    if value.get("有图片要求"):
        parts.append("图片要求")
    return "、".join(parts) or "无"


def _session_label(details: dict) -> str:
    outcome = str(details.get("结果", "未知"))
    parts = [outcome]
    if signal := details.get("信号"):
        parts.append(f"信号 {signal}")
    if agent := details.get("节点"):
        parts.append(f"节点 {agent}")
    before = details.get("原任务") or details.get("任务")
    after = details.get("新任务")
    if before is not None and after is not None:
        old, new = _pending_label(before), _pending_label(after)
        parts.append(f"待处理 {old}" if old == new else f"待处理 {old} → {new}")
    elif before is not None:
        parts.append(f"待处理 {_pending_label(before)}")
    if (target := details.get("目标阶段")) and outcome != "已提交":
        parts.append(f"目标 {target}")
    if version := details.get("version"):
        parts.append(f"版本 {version}")
    if details.get("写入已开始") and outcome != "写结果待核实":
        parts.append("写入结果待核实")
    if details.get("processing") and details.get("结果") != "已提交":
        parts.append("会话仍被占用")
    if error := details.get("异常类型"):
        parts.append(f"异常 {error}")
    return " · ".join(parts)


_TOOL_LABELS = {
    "queryDayDetail": "查询日程", "queryTodoList": "查询待办", "queryMonthCount": "查询月历",
    "createTodo": "创建待办", "batchCreateTodos": "同步规划", "updateTodo": "修改待办",
    "deleteTodo": "删除待办", "toggleTodoDate": "更新完成状态", "saveDailyNote": "保存日记",
    "removeTodoDay": "移除待办日期", "addTodoDay": "添加待办日期",
}


def _step_data(step: str) -> tuple[str, dict[str, str]]:
    name, _, arguments = step.partition("(")
    pairs = re.findall(r"([^=,()]+)=([^,()]*)", arguments) if arguments else []
    return name, dict(pairs)


def _turn_label(steps: list[str]) -> str:
    """从完整路径提炼阶段、模型轮次和工具结果，省略内部读写步骤。"""
    parts: list[str] = []
    for step in steps:
        name, info = _step_data(step)
        if name in {"RECEIVED", "LOAD_STATE", "CLAIM", "ROUTE", "MCP_CATALOG",
                    "LOAD_PLANNING_MEMORY", "CHAT_MODEL_RESULT", "EXECUTOR_MODEL_RESULT",
                    "PLAN_VALIDATED", "CHAT_RESULT", "PLANNER_RESULT", "EXECUTOR_RESULT",
                    "IMAGE_RESULT", "MARK_DRAFT_SYNCED"}:
            continue
        if name == "TRANSITION":
            parts.append(f"路由 {info.get('信号', '?')}")
        elif name in {"CHAT", "PLANNER", "EXECUTOR", "IMAGE"}:
            parts.append(name)
        elif name in {"CHAT_MODEL", "EXECUTOR_MODEL", "PLANNER_MODEL"}:
            parts.append(f"模型第{info.get('模型轮次', info.get('尝试次数', '?'))}轮")
        elif name in {"CHAT_TOOL", "EXECUTOR_TOOL"}:
            tool = info.get("工具", "工具")
            parts.append(_TOOL_LABELS.get(tool, tool))
        elif name in {"CHAT_TOOL_RESULT", "EXECUTOR_TOOL_RESULT"}:
            result = info.get("结果", "未知")
            if parts:
                parts[-1] += f"({result})"
        elif name == "COMMIT":
            parts.append("提交")
        elif name == "END":
            parts.append("完成")
        elif name == "BLOCKED":
            parts.append("认领冲突")
        elif name == "FAILED":
            parts.append("失败")
        elif name == "CANCELLED":
            parts.append("已取消")
        elif name == "WRITE_STARTED":
            parts.append("发起写入")
        elif name == "LOAD_DRAFT":
            parts.append(f"读取草稿#{info.get('草稿ID', '?')}")
        elif name == "SAVE_DRAFT":
            parts.append(f"保存草稿({info.get('待办数', '?')}项)")
        elif name == "IMAGE_MODEL":
            parts.append("生成图片")
        elif name == "IMAGE_MODEL_RESULT":
            parts.append("图片生成完成")
        elif name == "PLAN_INVALID":
            parts.append("规划校验失败")
        elif name == "READ_TOOL_RETRY":
            parts.append("只读工具重试")
        else:
            parts.append(name)
    return " → ".join(parts) if parts else "未进入处理节点"


def _write(trace: FlowTrace, scope: str, before: str, after: str,
           details: dict, level: int, elapsed: int) -> None:
    summary = _session_label(details) if scope == "会话流转" else _turn_label(trace.steps)
    logger.log(level, "%s | 用户=%s 会话=%s 轮次=%s | %s → %s | %s | 耗时=%.2f秒",
               scope, trace.user_id, trace.session_id[:8], trace.turn_id[:8],
               before, after, summary, elapsed / 1000,
               extra={"flow_scope": scope, "user_id": trace.user_id, "session_id": trace.session_id,
                      "turn_id": trace.turn_id, "from_state": before, "to_state": after,
                      "flow_details": details})


def log_turn_step(step: str, *, level: int = logging.INFO, **details) -> None:
    trace = _current.get()
    if trace is None:
        return
    # 只记录少量用于定位路径的字段，不保存模型输入、工具参数或工具结果。
    useful = {key: details[key] for key in ("信号", "节点", "模型轮次", "工具", "结果", "错误码",
                                            "尝试次数", "下一次尝试", "待办数", "草稿ID",
                                            "完成", "分发类型", "最终阶段")
              if key in details and details[key] is not None}
    label = step if not useful else f"{step}({','.join(f'{key}={value}' for key, value in useful.items())})"
    trace.steps.append(label)
    trace.step = step
    trace.turn_level = max(trace.turn_level, level)


def log_session_transition(before: str, after: str, outcome: str, *, level: int = logging.INFO, **details) -> None:
    trace = _current.get()
    if trace is None:
        return
    if outcome == "已读取":
        trace.initial_stage = before
        trace.final_stage = after
    elif outcome == "待执行":
        trace.target_stage = after
    else:
        trace.final_stage = after
    trace.session_outcome = outcome
    trace.session_details = details
    trace.session_level = max(trace.session_level, level)
