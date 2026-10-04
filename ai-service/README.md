# Calendar AI Service

当前提供 `GET /health`、`GET /auth/me`、`POST /chat`、流式 `POST /chat/stream`、`GET /chat/history`、`GET /chat/sessions`、`GET /chat/latest`、`POST /chat/new-session`、`DELETE /chat/session`、`DELETE /chat/last-round`、独立的 `POST /rag/search` 和知识库 `GET/POST /documents`、`POST /documents/{file_id}/parse`。普通与流式对话统一经过 LangGraph 主图：Summary（达到阈值时压缩并提取偏好）→ Route → 状态转换表 → Chat / Planner / Executor / Image → 提交。Chat 和 Executor 的工具循环最多三轮模型调用；Planner 仅在本轮请求引用文档时检索用户文档。

## 状态机与节点迁移

- Planner：读取已有用户偏好、必要时澄清、生成或修改结构化草稿；校验失败允许模型修正一次。草稿由 Python 写入 `yl_plan_draft`。
- Executor：新操作先展示任务，确认后才调用 Java MCP。确认使用已保存任务；工具参数由本地白名单校验，写结果核对操作与目标，不自动重试写入。
- 同步规划：仅接受当前用户、当前会话绑定的待同步草稿，通过新增的 `batchCreateTodos` MCP 工具调用 Java 的事务批量创建，再标记草稿已同步。部署时需同时更新 Java MCP。
- Image：调用配置的图片模型生成示意图，修改时累计外观要求，始终保留对应的规划草稿。
- 跨轮状态沿用 `yl_agent_flow_state` 的 `CHAT / PLAN / EXECUTE / IMAGE` 和版本认领；LangGraph 当前不启用 checkpointer。临时闲聊/查询保留待处理任务；拒绝清空任务。
- 完整回复、会话统计、下一阶段和待处理任务在一个 MySQL 事务提交。写工具开始后的失败或断连保留 `processing=1`，防止重复确认触发重复写入。需人工核实日历结果后修复状态；尚无自动恢复或幂等账本。

Java 业务写入与 Python 草稿/状态提交不能组成同一个本地事务；批量创建保证 Java 端整批成功或回滚，跨服务响应丢失仍按结果待核实处理。Java 旧 AI 和记忆模块已删除，保留 `module/mcp` 与业务模块；偏好模型抽取和上下文摘要由 Python 实现。

## 记忆写入与摘要

升级前暂停对话写入，执行 `sql/migrations/20261003_context_message_count.sql`，继续复用已有 `yl_user_memory`、`yl_chat_context_summary`。

- `ConversationGraph` 在 Route 前执行 `SummaryNode`：检查 `yl_ai_session.message_count`，达到50条时压缩较早消息，保留最近20条原文。消息按 user/assistant 各算一条，不按问答轮数。检查发生在本轮输入落库之前；压缩成功计数为20，本轮问答提交后为22。
- SummaryAgent 使用 `summary_config`，一次模型调用同时生成结构化摘要和用户偏好；没有每轮偏好抽取，也没有独立后台图或队列。摘要合并旧摘要与较早消息，偏好可引用本次历史中的用户原文（包括保留的20条），不采用助手建议、临时要求或假设。相同键覆盖旧值，明确忘记请求写删除标记。
- 摘要、偏好与计数在同一事务保存，提交时核对旧摘要、消息快照和会话计数。失败不修改数据，下次输入重试。模型上下文读取“摘要 + 最近未压缩原文”；原消息不删除，历史分页仍可查询全部记录。
- Planner 按用户隔离读取记忆，本轮要求优先，长期目标默认90天过期。当前仅保留聊天偏好与目标，不生成或使用行为统计记忆。
- 撤回/删除继续清理摘要及流程状态；撤回后按剩余有效原文重算计数，下一轮重新检查压缩。长期记忆独立于会话保留，可通过记忆接口删除。
- Service 仅封装数据读写；模型声明、提示词、偏好提取和节点顺序归 LangGraph 节点及主图负责，相关 Service 已补充职责注释。
- `GET /memory` 查看自己的有效记忆（最多200条），`DELETE /memory/{memory_id}` 删除自己的记忆，沿用 `yvli-token` 鉴权。

可选配置（未配置时使用以下默认值）：

```dotenv
MEMORY_ENABLED=true
MEMORY_SUMMARY_TRIGGER_MESSAGES=50
MEMORY_SUMMARY_RETAIN_MESSAGES=20
MEMORY_SUMMARY_MAX_CHARS=1200
MEMORY_MESSAGE_MAX_CHARS=1000
MEMORY_SUMMARY_TIMEOUT_SECONDS=120
```

只读 MCP 工具的瞬态错误由 Python 最多尝试 `MCP_READ_RETRY_ATTEMPTS` 次，间隔 `MCP_READ_RETRY_DELAY_MS` 毫秒。Java MCP 每次请求只执行一次并返回 `retryable` 标记；写工具不自动重试。

代码按职责分层：`app/api/routers` 处理 HTTP，`app/core` 管理配置、中间件、异常和响应，`app/cache` 校验 Redis token 与缓存 Embedding，`app/all_graph` 编排对话与模型节点，`app/api/conversation_transport.py` 适配 HTTP/SSE，`app/service` 封装数据操作，`app/repository` 访问 MySQL 与 Qdrant，`app/helper` 封装模型与 Java 客户端，`app/db` 提供连接，`app/schemas` 定义请求与响应。接口入口统一在 `app/api/endpoints.py` 注册。

## 历史消息与会话列表

- `GET /chat/history?sessionId=xxx&limit=20` 获取最新一页，默认 20 条消息（user 与 assistant 合计，不是轮数），`limit` 范围为 1–100。
- `GET /chat/history?sessionId=xxx&beforeId=81&limit=20` 获取 ID 小于 81 的更早消息。返回 `data: {items, hasMore, nextBeforeId}`，页内按 ID 升序；后续使用 `nextBeforeId`。空页或最后一页的游标为 NULL。
- `GET /chat/sessions` 读取 `yl_ai_session`，按最近活动倒序，返回 `sessionId/title/createTime/lastMessageTime/messageCount`。`createTime` 是会话创建时间，排序应使用 `lastMessageTime`。
- `GET /chat/latest?sessionId=xxx` 返回供 RouteAgent 使用的上下文：`{sessionId, previousReply, userMessages}`。取最后一条有效助手回复及其后全部用户输入，没有助手回复时取全部用户输入，不按 20 条截断。可用 `throughId` 固定本次读取上界。
- RouteAgent 在进程内直接调用 `ChatService.get_route_context()`；尚未入库的当前输入用 `current_message` 追加，已入库的当前输入用 `through_id` 定位，并结合阶段与待处理任务识别信号。
- `POST /chat/new-session` 返回 UUID；`DELETE /chat/session?sessionId=xxx` 删除会话；`DELETE /chat/last-round?sessionId=xxx` 按助手回复边界撤回，支持连续多条用户输入。
- 所有接口沿用 token 鉴权；用户与会话隔离，已删除会话不可读取或重新追加消息。

前端已适配分页与 Python SSE，开发环境 `/chat`、`/rag`、`/documents` 指向 Python 8001。生产环境可用 `VITE_AI_API_BASE_URL` 配置独立 Python 地址。已删除的 Java 查询及会话管理入口与剩余配套改动见 [10 文档](../docs/10-java-chat-history-migration.md)。

仓储支持 `append_messages()` 保存连续同角色消息。聊天入口由主图 `commit` 在完整回答产生后原子保存消息、会话统计及流程状态；流式失败与断连不保存不完整回复。

测试命令（在 `ai-service` 下运行）：

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -p "test_*.py"
# 可选：指向隔离 MySQL，账号需能创建和删除测试数据库；不会读取业务 DATABASE_URL。
$env:CHAT_TEST_MYSQL_URL = 'mysql+aiomysql://test_user:test_password@127.0.0.1:3306/'
.\.venv\Scripts\python.exe -m unittest tests.test_chat_history -v
.\.venv\Scripts\python.exe -m unittest tests.test_flow_persistence -v
```

MySQL 集成测试创建随机 `chat_history_test_*` 数据库并在结束时清理，覆盖游标分页、同角色消息、并发写入、事务回滚、版本认领、草稿隔离及迁移脚本。未设置测试 URL 时仅跳过这些数据库用例。使用 `caching_sha2_password` 且未启用 TLS 的 MySQL 测试连接需要在虚拟环境安装 `cryptography`。

## 本地运行（PowerShell）

```powershell
cd E:\develop\projects\workProject\calendar-agent\ai-service
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e .
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8001
```

配置分开加载：提交到仓库的 `.env` 保存完整运行配置，包括 Redis、数据库和 Qdrant 连接地址；被 Git 忽略的 `.env.prod` 保存模型 API Key。普通配置类只读取 `.env`，厂商文件中的 `SecretSettings` 只读取 `.env.prod`（也支持进程环境变量），因此 `.env` 中的同名密钥不会被读取。

启动日志打印接口文档地址，默认 `http://127.0.0.1:8001/docs`。修改监听端口或通过网关访问时，可在环境变量或 `.env` 中设置 `PUBLIC_BASE_URL`（例如 `http://127.0.0.1:9001`），用于生成日志中的文档链接，不改变 Uvicorn 监听配置。

普通和流式对话完成并保存后，打印一条对话内容日志，汇总用户输入（前 20 个字符）、命中的 Agent 及实际模型厂商（如 `chatAgent(deepseek)`）、完整模型回复的前 50 个字符。超长内容追加省略号，空白字符转为空格。

### 状态流转日志

每次用户输入在运行过程中收集状态，结束时统一打印三行：会话流转、单轮流转、对话内容。普通和流式入口一致。控制台使用中文摘要、会话/轮次短 ID 和秒数；完整 ID、原始步骤与字段仍保存在日志记录中。成功的 HTTP 访问和 `httpx` 上游 200 请求不单独打印，失败访问与异常仍打印。

- **会话流转**：用一行汇总 `CHAT / PLAN / EXECUTE / IMAGE` 的原阶段、最终阶段、拟定目标、路由信号、节点和任务产物摘要。数据库事务完成后结果为 `已提交`；失败为 `未提交` 或 `写结果待核实`。
- **单轮流转**：只展示路由、处理节点、模型轮次、工具结果和提交等关键步骤；内部读取/认领步骤留在结构化记录中。
- **对话内容**：成功时保留原有的 `对话完成` 日志；失败或取消时打印输入摘要和结束状态，不误报模型回复。
- 每次输入仅记录前 20 个字符，不输出 token、模型密钥、工具参数、工具返回正文或完整草稿；流式文本片段不逐条记日志。

例如一次查询请求结束后的三行日志（省略时间）：

```text
会话流转 | 用户=36 会话=3820af9b 轮次=fe6400aa | CHAT → CHAT | 已提交 · 信号 NEW_QUERY · 节点 CHAT · 待处理 无 · 版本 8 | 耗时=9.82秒
单轮流转 | 用户=36 会话=3820af9b 轮次=fe6400aa | START → END | 路由 NEW_QUERY → CHAT → 模型第1轮 → 查询日程(成功) → 模型第2轮 → 提交 → 完成 | 耗时=9.82秒
对话完成 | 用户输入：明天有什么安排 | 命中agent：chatAgent(deepseek) | 模型回复：明天暂无日程…
```

`.env` 中配置连接 URL；`.env.prod` 配置以下密钥（使用图片节点需要 `ARK_API_KEY`）：

```dotenv
ALIYUN_API_KEY=真实百炼 API Key
DEEPSEEK_API_KEY=真实 DeepSeek 密钥
RAG_EMBEDDING_API_KEY=真实百炼 API Key
ARK_API_KEY=真实火山方舟 API Key
```

Redis 必须连接到 Java Sa-Token 使用的同一数据库（当前开发配置是 DB 15）；MySQL 必须已有新结构的 `yl_ai_dialogue`（`role + content`）和 `yl_ai_session` 表；删除/撤回还需现有 `yl_agent_flow_state`、`yl_chat_context_summary` 表，以清理待确认状态和摘要。旧库使用 `sql/migrations/20261002_chat_session_content.sql` 迁移；Java 配套改动与部署约束见 [10 文档](../docs/10-java-chat-history-migration.md)。连接 URL 中用户名或密码的特殊字符需要 URL 编码。

聊天默认使用 Python `chat_config` 的 DeepSeek 配置。切换到阿里云时，在 `.env` 写入 `CHAT_PROVIDER=aliyun`，在 `.env.prod` 写入 `ALIYUN_API_KEY`。阿里云 OpenAI-compatible 接口使用 API Key。

模型配置分为两层：`app/core/config/agent/providers.py` 的 `Deepseek`、`Aliyun`、`Ark` 读取厂商地址与密钥；`app/core/config/agent/agents.py` 的 Agent 配置选择模型及参数。Java 不再配置模型，Python 默认配置如下：

| Agent | 厂商 | 模型 | 温度 |
|---|---|---|---|
| Chat | DeepSeek | deepseek-flash | 0.3 |
| Route | 阿里云 | qwen3.7-plus | 0.1 |
| Planner | DeepSeek | deepseek-flash | 0.1 |
| Executor | 阿里云 | qwen3.7-plus | 0.3 |
| Summary | 阿里云 | qwen3.7-plus | 0.1 |
| Image | 火山方舟 | doubao-seedream-5-0-flash-260915 | 不适用 |

Image 另有 `size=2K`、`watermark=true`，客户端通过 `/images/generations` 返回图片 URL。Embedding 保持阿里云 `text-embedding-v4`。每个 Agent 的厂商、模型、温度和请求超时均可在 `.env` 覆盖；Planner 沿用 `PLAN_` 前缀。

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

公共语料检索流程为中文 BM25 + Qdrant Dense → RRF → 可选模型重排，默认关闭重排。

## 用户知识库

运行 `sql/migrations/20261003_user_documents.sql` 创建 `yl_file` 和 `yl_document`；完整新库结构也包含在 `sql/tables.sql`。在 `ai-service/.env` 配置 `MINIO_ENDPOINT=localhost:9000`、`MINIO_SECURE=false`、`MINIO_BUCKET=calendar-documents` 和可选的 `QDRANT_DOCUMENT_COLLECTION=user_documents`，在 `ai-service/.env.prod` 配置 `MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`。上传时若 MinIO 桶不存在会自动创建；首次解析时创建独立的 Qdrant 集合。

知识库页支持 TXT、Markdown、可提取文本的 PDF 和 DOCX，单文件上限 10 MB。`POST /documents` 只保存原文件到 MinIO 并登记 `uploaded` 状态，不解析、不请求 Embedding 或 Qdrant；`POST /documents/{file_id}/parse` 从 MinIO 读取原文件，按段落切片、去重、向量化，并写入 `yl_document` 和 Qdrant，完成后变为 `ready`。`POST /documents/{file_id}/reparse` 替换旧解析结果；`DELETE /documents/{file_id}/parse` 只清理向量和片段，保留源文件；`DELETE /documents/{file_id}` 删除源文件以及全部解析结果。删除操作按登录用户校验归属，正在处理的文件不能删除。重复上传同一用户的相同内容会复用已有文件。对话请求可附 `documentIds: [文件ID]`（最多五个），Planner 只接受 `ready` 文件并仅检索这些文件；没有引用时不会查询文档向量库。扫描版 PDF 需要先进行 OCR。

## 规划图片归档

其他已有数据库部署时运行 `sql/migrations/20261004_image_files.sql`，为 `yl_file` 增加文件类型、桶名和仅针对文档的去重键；本地开发数据库已执行该迁移。图片模型返回下载地址后，Python 后端下载原始 JPEG、PNG 或 WebP 字节（上限 20 MB），先在 `yl_file` 登记 `image/uploading`，再保存到 MinIO 的 `IMAGE_MINIO_BUCKET`，默认 `calendar-images`；成功变为 `uploaded`，失败变为 `upload_failed`。桶不存在时自动创建。图片不进入知识库文档列表或解析流程。对话消息保存 `minio://桶名/对象路径`、预览图和“下载原图”链接；预览及下载通过 Python `/images/...` 签名地址从私有桶读取，无需将 MinIO 桶设为公开。签名地址使用 `MINIO_SECRET_KEY` 生成，修改该密钥会使历史图片链接失效。前端开发代理已转发 `/images`；前后端分域部署时沿用 `VITE_AI_API_BASE_URL`。
