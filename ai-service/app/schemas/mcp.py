"""Java /mcp JSON-RPC 响应在 Python 边界使用的结构。"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class McpToolMetadata(BaseModel):
    """Java 工具声明的执行特性；Python 仍用本地白名单限制只读重试。"""

    readOnly: bool
    idempotent: bool | None = None
    parallelSafe: bool | None = None
    confirmationRequired: bool | None = None
    timeoutMs: int | None = None


class McpToolDefinition(BaseModel):
    """tools/list 的单个工具；inputSchema 是 JSON Schema，键由 Java 动态生成。"""

    name: str
    description: str = ""
    inputSchema: dict[str, object]
    metadata: McpToolMetadata


class McpToolList(BaseModel):
    tools: list[McpToolDefinition]


class McpTextContent(BaseModel):
    type: Literal["text"]
    text: str


class McpCallResult(BaseModel):
    """tools/call 的外层结果；content[0].text 还需继续解析为 JSON。"""

    model_config = ConfigDict(strict=True)

    isError: bool
    content: list[McpTextContent] = Field(min_length=1)


class McpToolFailure(BaseModel):
    """Java 工具失败时放在 text 中的内容，retryable 控制 Python 是否重试。"""

    model_config = ConfigDict(strict=True)

    code: str | int = "TOOL_ERROR"
    message: str = "Java 工具调用失败"
    retryable: bool = False


class JsonRpcError(BaseModel):
    code: str | int = "RPC_ERROR"
    message: str = "Java JSON-RPC 调用失败"


class JsonRpcResponse(BaseModel):
    """JSON-RPC 外壳；result 的具体类型由 tools/list 或 tools/call 再校验。"""

    jsonrpc: Literal["2.0"]
    id: str
    result: object = None
    error: JsonRpcError | None = None


class JavaErrorResponse(BaseModel):
    """登录拦截器可能以 HTTP 200 返回的非 JSON-RPC 错误。"""

    ok: Literal[False]
    code: int | None = None
    msg: str | None = None
