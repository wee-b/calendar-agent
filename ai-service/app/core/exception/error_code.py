"""应用错误码、文案和 HTTP 状态的统一定义。"""

from enum import Enum


class ErrorCode(Enum):
    """前三项是公开错误信息；legacy_http 保留旧接口的 detail 响应结构。"""

    BUSINESS_ERROR = (4000, "业务错误", 400)
    CHAT_SESSION_DELETED = (40901, "会话已删除，请开启新对话", 409)
    CHAT_SESSION_PROCESSING = (40902, "会话正在执行操作，请完成后重试", 409)
    MODEL_TEXT_DELTA_INVALID = (50201, "模型文本增量格式异常", 502)
    MODEL_TOOL_DELTA_INVALID = (50202, "模型工具增量格式异常", 502)
    MODEL_MESSAGE_INVALID = (50203, "模型消息格式异常", 502)
    CHAT_NO_FINAL = (50204, "对话未生成有效回答", 502, True)
    MODEL_ROUND_LIMIT = (50205, "模型超过工具调用轮数", 502, True)
    MODEL_EMPTY_ANSWER = (50206, "模型未返回有效回答", 502, True)
    MODEL_TOOL_CALL_INVALID = (50207, "模型工具调用格式异常", 502, True)
    MODEL_TOOL_UNAPPROVED = (50208, "模型请求了未开放的工具", 502, True)
    MODEL_TOOL_ARGUMENT_INVALID = (50209, "模型工具参数格式异常", 502, True)
    MODEL_TOOL_DATE_INVALID = (50210, "模型工具日期格式异常", 502, True)
    MODEL_TOOL_ID_INVALID = (50211, "模型工具调用 ID 异常", 502, True)
    MODEL_TOOL_INCOMPLETE = (50212, "模型工具调用不完整", 502, True)
    MODEL_STREAM_EARLY_END = (50213, "模型流提前结束", 502, True)
    MODEL_STREAM_ERROR = (50214, "模型流返回错误", 502, True)
    MODEL_STREAM_INVALID = (50215, "模型流格式异常", 502, True)
    MODEL_UNAVAILABLE = (50216, "无法连接模型服务", 502, True)
    MODEL_HTTP_ERROR = (50217, "模型服务返回错误", 502, True)
    MODEL_RESPONSE_INVALID = (50218, "模型响应格式异常", 502, True)
    JAVA_USER_RESPONSE_INVALID = (50219, "Java 用户服务返回格式异常", 502, True)
    MCP_AUTH_FAILED = (40101, "用户 token 无效或无权调用工具", 401, True)
    TOKEN_INVALID = (40102, "无效的用户 token", 401, True)
    LOGIN_INVALID = (40103, "登录状态无效", 401, True)
    MODEL_PROVIDER_UNSUPPORTED = (50301, "不支持的聊天模型提供商", 503, True)
    MODEL_CONFIG_INCOMPLETE = (50302, "模型配置未完成", 503, True)
    AUTH_UNAVAILABLE = (50303, "鉴权服务暂不可用", 503, True)
    JAVA_USER_UNAVAILABLE = (50304, "无法连接 Java 用户服务", 503, True)
    MODEL_TIMEOUT = (50401, "模型响应超时", 504, True)
    JAVA_USER_TIMEOUT = (50402, "Java 用户服务响应超时", 504, True)

    def __init__(self, code: int, message: str, status_code: int,
                 legacy_http: bool = False):
        self.code = code
        self.message = message
        self.status_code = status_code
        self.legacy_http = legacy_http


class StreamErrorCode(Enum):
    """SSE 在响应头已发出后使用的字符串错误码，保持前端协议稳定。"""

    CHAT_ERROR = ("CHAT_ERROR", "对话处理失败")
    CHAT_TIMEOUT = ("CHAT_TIMEOUT", "对话请求超时")

    def __init__(self, code: str, message: str):
        self.code = code
        self.message = message
