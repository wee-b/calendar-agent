# Calendar AI Service（最小对话版）

当前提供 `GET /health`、`GET /auth/me`、`POST /chat`、流式 `POST /chat/stream` 和独立的 `POST /rag/search`。对话由 LangGraph 编排模型和 Java `/mcp` 的只读 `queryDayDetail` 工具，最多三轮模型调用；RAG 暂不参与对话。持久化 checkpoint 和写操作确认仍属 09 文档后续阶段。

只读 MCP 工具的瞬态错误由 Python 最多尝试 `MCP_READ_RETRY_ATTEMPTS` 次，间隔 `MCP_READ_RETRY_DELAY_MS` 毫秒。Java MCP 每次请求只执行一次并返回 `retryable` 标记；写工具不自动重试。

代码按职责分层：`app/api/routers` 处理 HTTP，`app/core` 管理配置、中间件、异常和响应，`app/cache` 校验 Redis token 与缓存 Embedding，`app/service` 编排对话和 RAG，`app/repository` 访问 MySQL 与 Qdrant，`app/helper` 封装模型与 Java 客户端，`app/db` 提供连接，`app/schemas` 定义请求与响应。接口入口统一在 `app/api/endpoints.py` 注册。

## 本地运行（PowerShell）

```powershell
cd E:\develop\projects\workProject\calendar-agent\ai-service
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e .
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8001
```

配置分开加载：提交到仓库的 `.env` 保存完整运行配置，包括 Redis、数据库和 Qdrant 连接地址；被 Git 忽略的 `.env.prod` 保存模型 API Key。普通配置类只读取 `.env`，厂商文件中的 `SecretSettings` 只读取 `.env.prod`（也支持进程环境变量），因此 `.env` 中的同名密钥不会被读取。

`.env` 中配置连接 URL；`.env.prod` 配置以下密钥（生图接入前可暂不填写 `ARK_API_KEY`）：

```dotenv
ALIYUN_API_KEY=真实百炼 API Key
DEEPSEEK_API_KEY=真实 DeepSeek 密钥
RAG_EMBEDDING_API_KEY=真实百炼 API Key
ARK_API_KEY=真实火山方舟 API Key
```

Redis 必须连接到 Java Sa-Token 使用的同一数据库（当前开发配置是 DB 15）；MySQL 必须已有 `yl_ai_dialogue` 表。连接 URL 中用户名或密码的特殊字符需要 URL 编码。

聊天默认使用 Spring Boot 中 `chat` agent 的 DeepSeek 配置。切换到阿里云时，在 `.env` 写入 `CHAT_PROVIDER=aliyun`，在 `.env.prod` 写入 `ALIYUN_API_KEY`。阿里云 OpenAI-compatible 接口使用 API Key。

模型配置分为两层：`app/core/config/agent/providers.py` 的 `Deepseek`、`Aliyun`、`Ark` 读取厂商地址与密钥；`app/core/config/agent/agents.py` 的 Agent 配置选择模型及参数。默认配置对齐 Java `application-dev.yml`：

| Agent | 厂商 | 模型 | 温度 |
|---|---|---|---|
| Chat | DeepSeek | deepseek-flash | 0.3 |
| Route | 阿里云 | qwen3.7-plus | 0.1 |
| Planner | DeepSeek | deepseek-flash | 0.1 |
| Executor | 阿里云 | qwen3.7-plus | 0.3 |
| Summary | 阿里云 | qwen3.7-plus | 0.1 |
| Image | 火山方舟 | doubao-seedream-5-0-flash-260915 | 不适用 |

Image 另有 `size=2K`、`watermark=true`，当前仅提供配置，生图客户端后续接入。Embedding 保持阿里云 `text-embedding-v4`。每个 Agent 的厂商、模型、温度和请求超时均可在 `.env` 覆盖；Planner 沿用 `PLAN_` 前缀。超时为 Python 独立配置。若 Java 运行环境覆盖了模型 ID，需同步修改 Python 的 Agent 模型名。

配置目录按用途分组，模型相关配置只保留两个文件：

```text
app/core/config/
├── common/          # settings、qdrant、logging_config、rag、mcp、chat_stream
└── agent/
    ├── providers.py # 厂商连接、密钥、模型声明
    └── agents.py    # 每个 Agent 的配置类与共享实例，包含 Embedding
```

各 Agent 分别读取自己的环境变量前缀，不再使用统一的 `AgentSettings` 和 getter。配置在进程启动时加载，修改 `.env` 后重启服务。客户端默认使用 `chat_config`，其他 Agent 直接导入实例，或显式声明模型：

```python
from app.core.config.agent.agents import PlanAgentConfig, EmbeddingConfig, route_config
from app.core.config.agent.providers import Deepseek, Aliyun
from app.helper.model_client import ModelClient

# name 使用厂商实际提供的模型 ID；下面省略 name 时读取厂商默认聊天模型。
plan_config = PlanAgentConfig(model=Deepseek())
plan_client = ModelClient(config=plan_config)
route_client = ModelClient(config=route_config)
embedding_config = EmbeddingConfig(model=Aliyun(name="text-embedding-v4"))
```

默认 Embedding 客户端仍使用 `embedding_config` 读取独立的 `RAG_EMBEDDING_BASE_URL`、模型名和超时；密钥优先使用 `RAG_EMBEDDING_API_KEY`，未配置时使用 `ALIYUN_API_KEY`。密钥仅从 `.env.prod` 或进程环境变量读取。

请求示例：

```powershell
$body = @{ sessionId = "demo-1"; message = "你好" } | ConvertTo-Json
Invoke-RestMethod -Uri http://127.0.0.1:8001/chat -Method Post -ContentType application/json -Headers @{ 'yvli-token' = '<登录返回的 token>' } -Body $body
```

流式对话使用同一请求体和 token，响应是 `text/event-stream`：

```powershell
curl.exe -N -X POST http://127.0.0.1:8001/chat/stream `
  -H 'Content-Type: application/json' -H 'yvli-token: <登录返回的 token>' `
  -d '{"sessionId":"demo-1","message":"明天有什么安排"}'
```

事件包含 `agent_status`、`assistant_delta`、`tool_call_start`、脱敏的 `tool_call_delta`、`tool_call_end`、`tool_result`、`result` 和 `done`；长时间无输出时按 `CHAT_STREAM_PING_SECONDS` 发送 `ping`。模型或工具链路失败时发送 `error`；只有正常完成才保存完整对话并发送 `done`。`CHAT_STREAM_TIMEOUT_SECONDS` 控制整次请求时限，`MODEL_REQUEST_TIMEOUT` 和 `JAVA_REQUEST_TIMEOUT` 分别控制模型与 Java 调用。

运行测试：

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

也可以用 `tests/test_main.http` 手动测试接口。需要清理源码中的 Python 缓存时运行 `python -m app.clean_pycache`。

`/chat` 的用户 ID 只来自 Redis token 映射；用户资料由 `/auth/me` 转发 token 向 Java 获取。Python 依赖 Sa-Token 1.44.0 的 `client` token 键格式，升级 Java 鉴权实现时要同步验证登录、退出和过期行为。

## RAG 语料与检索

RAG 配置分为 `EmbeddingSettings`、`QdrantSettings` 和 `RagSettings`，分别管理模型、向量库和检索参数。默认使用百炼 OpenAI 兼容接口的 `text-embedding-v4`；通过 `RAG_EMBEDDING_BASE_URL`、`RAG_EMBEDDING_API_KEY` 和 `RAG_EMBEDDING_MODEL` 配置远程模型。Qdrant 使用 REST 端口 `6333` 和现有 `rag_corpus` 集合；Embedding 向量与检索结果分别缓存到 Redis。

语料从 `ai-service/rag_data` 读取，按 Markdown 标题分块，使用 TF-IDF、SimHash、3-gram Jaccard 去重，再向量化批量写入 Qdrant。导入保留 Java 版本的确定性 UUID，重导入相同片段会覆盖原点。切换 Embedding 模型前，需确认新模型维度与现有集合一致；不一致时应使用新集合。

```powershell
# 只读取、分块和去重，不调用模型或 Qdrant
.\.venv\Scripts\python.exe -m app.import_rag_corpus --dry-run

# 向量化并写入 Qdrant
.\.venv\Scripts\python.exe -m app.import_rag_corpus
```

若要验证“清空后重新导入”，运行下面的独立集成测试。它会**删除 `QDRANT_COLLECTION` 指定的整个集合及其中所有点**，随后重建集合并导入 `rag_data`，逐项核对数量、ID 和 payload。运行前确保 Qdrant、Redis 和 Embedding 服务可用；此测试不会随普通 `unittest discover` 执行。

```powershell
$env:RUN_RAG_QDRANT_RESET_TEST='rag_corpus'
.\.venv\Scripts\python.exe -m unittest tests.manual_rag_qdrant_import.RagCorpusImportIntegrationTest.test_reset_collection_and_import_rag_data -v
Remove-Item Env:RUN_RAG_QDRANT_RESET_TEST
```

使用有效登录 token 调用独立检索接口：

```powershell
$body = @{ query = "制定英语备考计划" } | ConvertTo-Json
Invoke-RestMethod -Uri http://127.0.0.1:8001/rag/search -Method Post -ContentType application/json -Headers @{ 'yvli-token' = '<登录返回的 token>' } -Body $body
```

检索流程为中文 BM25 + Qdrant Dense → RRF → 可选模型重排，默认关闭重排。`/chat` 尚未调用 RAG。
