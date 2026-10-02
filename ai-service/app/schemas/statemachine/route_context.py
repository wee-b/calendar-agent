"""RouteAgent 判断本次意图时使用的助手回复与连续用户输入。"""

from typing import Literal

from pydantic import BaseModel, Field


class RouteContextMessage(BaseModel):
    # 当前请求尚未保存时没有 dialogueId，正文不会因此被遗漏。
    dialogueId: int | None = None
    role: Literal["user", "assistant"]
    content: str


class RouteContext(BaseModel):
    sessionId: str
    previousReply: RouteContextMessage | None = None
    userMessages: list[RouteContextMessage] = Field(default_factory=list)
