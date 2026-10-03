# Calendar Agent

智能日程管理应用。项目从早期的语音日历工具升级为 **日历 + 待办 + 日记 + AI Agent 对话助手**：支持日程 CRUD、每日任务拆分、现代黄历、语音/文字聊天、多会话管理和规划草稿确认后同步到日历。RAG 已迁移到 Python 服务，暂未接入对话。


---

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 前端 | Vue 3.5 + TypeScript 6 + Vite 8 + Vue Router + Element Plus |
| 后端 | Spring Boot 3.5.4 + Java 17 + MyBatis-Plus + Sa-Token |
| 数据库 | MySQL 8 + Druid + P6Spy |
| 缓存 | Redis |
| AI 框架 | Python FastAPI + LangGraph，OpenAI 兼容 Chat/Streaming API |
| 默认模型配置 | Python 配置管理：百炼用于路由/执行，DeepSeek 用于对话/规划，火山方舟用于生图；详见 `ai-service/README.md` |
| RAG | Python 中文 BM25 + Qdrant Dense Vector + RRF 融合 + 可选 LLM Rerank |
| Embedding | 阿里云 `text-embedding-v4` 等远程 OpenAI 兼容模型 |
| 文档 | Knife4j OpenAPI |
| 语音 | Web Speech API + SpeechSynthesisUtterance + Web Audio API |

---

## 目录结构

```text
calendar-agent/
├── back/                    # Spring Boot 后端
│   └── src/main/
│       ├── java/com/qiniu/back/
│       │   ├── module/user/       # 用户模块
│       │   ├── module/todo/       # 待办与每日任务
│       │   ├── module/dailyNote/  # 日历统计、日详情、日记
│       │   ├── module/mcp/        # MCP 控制器、工具注册及业务调用
│       │   └── module/almanac/    # 现代黄历
│       └── resources/
│           ├── application.yml
│           └── application-dev.yml
├── front/                   # Vue 前端
│   └── src/
│       ├── api/             # user/todo/calendar/chat/almanac API 封装
│       ├── components/      # 通用组件
│       ├── views/           # Layout/Home/Today/Chat 页面
│       ├── router/
│       └── utils/
├── sql/                     # 表结构与初始化数据
└── ai-service/              # Python 对话与独立 RAG 检索、语料导入
    └── rag_data/            # RAG Markdown 原始语料
```

---

## 后端模块

| 模块 | 说明 |
| --- | --- |
| `user` | 注册、登录、当前用户信息、登出 |
| `todo` | 目标待办 CRUD、按日期展开、每日完成状态、增删单日任务 |
| `dailyNote` | 月待办数量、日详情、每日笔记 upsert |
| `mcp` | JSON-RPC 工具列表与执行，复用业务服务的用户隔离和事务 |
| `almanac` | 现代黄历计算与查询 |

AI 与 MCP 的职责：

- Python `ConversationGraph`：编排 Route、Chat、Plan、Execute、Image 节点，统一提交对话和流程状态。
- Python `ChatTransitionTable`：按阶段与用户信号选择处理节点和下一阶段。
- Python `PlanNode`：模型调用、规划校验；`PlanningService` 封装记忆查询及草稿保存。
- Python `ExecuteNode`：确认后调用 MCP，规划同步使用 `batchCreateTodos`。
- Python `ai-service/app/service/rag.py`：BM25 + Qdrant 混合检索、RRF 融合、可选 Rerank、缓存。
- Java `McpController` / `McpToolRegistry` / `McpToolService`：提供业务工具，复用日历业务权限与事务；不持有模型或会话状态。

Java 已删除旧对话入口、Agent、状态机、草稿/摘要/记忆管理、提示词和模型配置，移除 LangChain4j 依赖。AI 数据表继续供 Python 使用，清理代码不删除数据库数据。长期记忆抽取、行为刷新和上下文摘要尚待 Python 补齐。

Java 默认仅启用 `dev` profile，遗留本地 `application-unknown.yml` 不再加载或打包。模型与密钥由 Python 配置管理。

---

## 数据库表

| 表名 | 说明 |
| --- | --- |
| `yl_user` | 用户表，手机号唯一，密码 BCrypt 加密 |
| `yl_todo` | 待办目标表，包含标题、颜色、日期范围、每周执行日、整体状态 |
| `yl_todo_date` | 待办日期表，保存每天的具体任务内容和完成状态 |
| `yl_daily_note` | 每日日记/备注表，每个用户每天一条 |
| `yl_ai_dialogue` | AI 对话历史，按 `session_id` 分组 |
| `yl_plan_draft` | Planner 生成的待同步规划草稿 |
| `yl_agent_flow_state` | 会话主流程 CHAT/PLAN/EXECUTE/IMAGE、任务产物及版本认领 |
| `yl_user_memory` | 用户长期偏好、长期目标和行为习惯记忆 |
| `yl_chat_context_summary` | 长会话上下文摘要，用于压缩 Agent 历史输入 |

---

## API 概览

### 用户 `/user`

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| POST | `/user/register` | 注册 | 否 |
| POST | `/user/login` | 登录，返回 token | 否 |
| GET | `/user/info` | 获取当前用户信息 | 是 |
| PUT | `/user/info` | 修改用户信息 | 是 |
| POST | `/user/logout` | 登出 | 是 |

### 待办 `/todo`

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| POST | `/todo` | 创建待办目标，并按日期范围展开每日任务 | 是 |
| GET | `/todo/list` | 查询当前用户所有待办 | 是 |
| PUT | `/todo/{todoId}` | 修改待办并重建日期明细 | 是 |
| DELETE | `/todo/{todoId}` | 删除待办及其日期明细 | 是 |
| PUT | `/todo/toggle-date` | 完成/取消完成某天任务 | 是 |

### 日历与日记

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| GET | `/calendar/month-count?year=2026&month=8` | 查询当月每日待办数量 | 是 |
| GET | `/calendar/day?date=2026-08-24` | 查询某天待办和日记 | 是 |
| PUT | `/daily-note` | 保存或修改某天日记 | 是 |

### AI 对话 `/chat`（Python 服务）

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| POST | `/chat` | 非流式对话 | 是 |
| POST | `/chat/stream` | SSE 流式对话，包含 `assistant_delta`、状态/工具事件、`result`、`done` | 是 |
| POST | `/chat/new-session` | 创建新会话 ID | 是 |
| GET | `/chat/history` | 查询指定会话历史 | 是 |
| DELETE | `/chat/session` | 删除指定会话 | 是 |
| DELETE | `/chat/last-round` | 撤回上一轮对话 | 是 |
| GET | `/chat/sessions` | 查询会话列表 | 是 |
| GET | `/chat/latest` | 查询最近会话历史 | 是 |

### 现代黄历 `/almanac`

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| GET | `/almanac/day?date=2026-08-24` | 查询指定日期现代黄历；不传 date 时默认当天 | 否 |

### MCP `/mcp`

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| POST | `/mcp` | JSON-RPC 端点，支持 `tools/list` 与 `tools/call` | 是 |

Java `/memory` 入口已移除，Python 当前仅在规划时读取已有有效记忆。

---

## AI 工作流

```text
用户输入
  ├─ 读取 AgentFlowState 和上一轮助手回复
  ├─ RouteAgent 结合主流程、当前任务、上一轮回复和本轮消息输出 UserSignal
  └─ 唯一转换表按 ConversationStage + UserSignal 选择 Agent 和成功后阶段
       ├─ NEW_CHAT / NEW_QUERY：Chat 回复或查询，保留当前主流程
       ├─ NEW_PLAN：Planner 处理规划，进入 PLAN
       ├─ NEW_EXECUTE：Executor 展示待执行内容，进入 EXECUTE 等待确认
       ├─ CONFIRM / MODIFY / REJECT：按当前主流程继续、修改或取消
       ├─ GENERATE_PLAN_IMAGE：Image 生图，进入 IMAGE 并保留规划草稿
       └─ SYNC_PLAN：Executor 同步当前草稿，成功后回到 CHAT
```

规划类任务会先输出草稿预览。用户明确要求同步后，Python `ExecuteNode` 将当前草稿转换为业务参数，通过 Java `batchCreateTodos` MCP 工具在一个事务中批量创建待办；普通“好的”不会触发同步。生图后仍可同步同一草稿。
完整流转表和发布步骤见 [08 设计文档](docs/08-chat-state-transition-table.md)。已有数据库启动此版本前需执行一次 `sql/migrations/20260930_agent_conversation_stage.sql`。
Planner 生成计划前会读取 active 用户记忆；公共 RAG 当前仅能通过 Python 独立检索接口调用，尚未注入规划对话。
跨轮状态保存在 MySQL；当前 LangGraph 未启用 checkpointer。写操作结果不确定时保留会话认领待核实，不自动重试。RouteAgent 读取上一轮助手回复和本轮连续用户消息；上下文摘要自动刷新尚未接入 Python。

---

## RAG 语料处理

语料位于 `ai-service/rag_data`，由 Python `ai-service` 完成 Markdown 分块、TF-IDF/SimHash/Jaccard 去重、Embedding 和 Qdrant 入库。模型、Qdrant 和检索参数分别配置在 `EmbeddingSettings`、`QdrantSettings`、`RagSettings`。

```bash
cd ai-service

# 只验证语料读取、分块和去重，不调用外部接口
python -m app.import_rag_corpus --dry-run

# 调用已配置的阿里云 Embedding，并写入 Qdrant
python -m app.import_rag_corpus
```

默认读取 `ai-service/rag_data`，可用 `--source-dir` 指定其他目录。导入使用与旧 Java 实现兼容的确定性 UUID，重复执行会覆盖同一语料点。Qdrant 默认连接 REST 端口 `6333`，集合名为 `rag_corpus`。独立接口为 `POST /rag/search`，需要登录 token；当前不参与对话。

---

## 快速启动

### 1. 初始化数据库

```bash
mysql -u root -p < sql/tables.sql
mysql -u root -p < sql/insert_data.sql
```

### 2. 准备依赖服务

- MySQL：默认库名 `yl_database`
- Redis：默认 `localhost:6379`，数据库 `15`
- Qdrant：Python 默认使用 REST 端口 `6333`，集合 `rag_corpus`
- Embedding：使用阿里云 `text-embedding-v4` 等远程 OpenAI 兼容模型
- Chat Model：OpenAI 兼容接口，配置在 Python `ai-service/.env`，密钥在 `.env.prod` 或环境变量

### 3. 配置后端

按本地环境修改：

```text
back/src/main/resources/application-dev.yml
```

关键配置：

```yaml
app:
  datasource:
    host: localhost
    port: 3306
    database: yl_database
    username: root
    password: 123
  redis:
    host: localhost
    port: 6379
    database: 15
  sa-token:
    token-name: yvli-token
    timeout: 604800
```

### 4. 启动后端

```bash
cd back
mvn spring-boot:run
```

启动后访问 Knife4j：

```text
http://localhost:8080/doc.html
```

### 5. 启动前端

```bash
cd front
npm install
npm run dev
```

Vite 将 `/chat`、`/rag` 代理到 Python `http://localhost:8001`，用户、日历、待办和日记请求代理到 Java `http://localhost:8080`。Python 启动和模型配置见 [AI 服务说明](ai-service/README.md)。

---

## 认证方式

1. 调用 `/user/login` 获取 token，开发时也可用 `/test/getToken?userId=36`。
2. 后续请求携带 Header：`yvli-token: <token>`。
3. `LoginInterceptor` 校验 token 后写入 `LoginUserContext`。
4. 业务层通过 `LoginUserContext.getUserId()` 获取当前用户。
5. 调用 `/user/logout` 后 token 失效。

---

## 前端页面

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/` | Layout + 默认首页 | 应用布局入口 |
| `/calendar-view` | 日历页 | 月视图、待办高亮、日详情、日记 |
| `/today` | 今日页 | 当天待办聚合 |
| `/conversation` | 对话页 | AI 助手、多会话、SSE 进度、语音输入与朗读 |

主要组件：

- `LayoutView`：整体布局、导航、会话侧栏。
- `HomeView`：日历主体、待办列表、日详情面板。
- `TodayView`：今日待办。
- `ChatView`：独立聊天页。
- `ChatPanel`：消息列表、输入框、会话切换、确认弹窗、语音交互。
- `AuthModal`：登录/注册弹窗。

---

## 开发进度

- [x] Spring Boot + Vue 项目骨架
- [x] MySQL 表结构与初始化数据
- [x] 用户认证与 Redis Token 持久化
- [x] 待办目标与每日任务 CRUD
- [x] 日历月统计、日详情、日记保存
- [x] 前端日历页、今日页、对话页、布局导航
- [x] 多会话聊天、历史记录、撤回上一轮
- [x] SSE 流式响应与进度事件
- [x] Web Speech API 语音识别、TTS 朗读、音量条
- [x] RouteAgent 结构化事件 + 状态机确定性执行 + Planner / Executor 架构
- [x] 写操作确认流与 AgentFlowState 状态管理
- [x] Planner 草稿预览与确认后同步到日历
- [x] MySQL 长期偏好记忆与行为习惯记忆
- [x] 长会话自动摘要与上下文压缩
- [x] Executor 单日及通用写操作工具
- [x] MCP `tools/list` / `tools/call`
- [x] Python 中文 BM25 + Qdrant 向量检索 + RRF + 可选 Rerank（独立能力）
- [x] RAG 语料去重、Embedding、Qdrant 入库脚本
- [x] 现代黄历模块
- [x] Knife4j 接口文档

---

## 常用命令

```bash
# 后端测试
cd back
mvn test

# 前端类型检查与构建
cd front
npm run build

# 查看 Git 状态
git status --short
```
