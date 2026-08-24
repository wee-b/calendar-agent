# Calendar Agent

智能日程管理应用。项目从早期的语音日历工具升级为 **日历 + 待办 + 日记 + AI Agent 对话助手**：支持日程 CRUD、每日任务拆分、现代黄历、语音/文字聊天、多会话管理、规划草稿确认后同步到日历，以及基于 RAG 的学习/备考类计划生成。

---

## 当前能力

- 用户注册、登录、登出、资料维护，使用 Sa-Token + Redis 保存登录状态。
- 目标待办管理：创建、修改、删除、按周重复、日期展开、每日完成/取消完成。
- 日历视图：月统计、某天详情、日记保存、待办颜色联动高亮。
- 今日视图：聚合展示当天待办与完成状态。
- 独立对话页：会话列表、历史切换、新建/删除会话、撤回上一轮、复制/朗读 AI 回复。
- AI 日程助手：Supervisor 判断任务类型，Query/Planner/Executor 子 Agent 分工处理。
- 确认式执行：创建/修改/删除等写操作会先进入待确认状态，用户确认后执行。
- 计划草稿：长期/复杂规划先生成草稿，用户确认后批量同步为待办。
- 长期记忆：从用户明确表达中保存长期偏好、目标，并从最近 30 天待办完成情况归纳行为习惯。
- 快捷指令：简单创建、查询、删除某天任务、移动任务、完成打卡等可走本地快路径。
- RAG 知识库：对学习、备考、考试等规划类问题提供检索增强上下文。
- MCP JSON-RPC 端点：暴露日程工具列表与工具调用能力。
- 现代黄历：根据日期返回宜忌、建星等信息。

---

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 前端 | Vue 3.5 + TypeScript 6 + Vite 8 + Vue Router + Element Plus |
| 后端 | Spring Boot 3.5.4 + Java 17 + MyBatis-Plus + Sa-Token |
| 数据库 | MySQL 8 + Druid + P6Spy |
| 缓存 | Redis |
| AI 框架 | LangChain4j 1.3，OpenAI 兼容 Chat/Streaming API |
| 默认模型配置 | 阿里云百炼兼容接口，默认 `qwen3.7-plus`，Planner 可用 `deepseek-v4-flash` |
| RAG | Lucene BM25 + Qdrant Dense Vector + RRF 融合 + LLM Rerank |
| Embedding | Ollama `bge-m3` 或阿里云 `text-embedding-v4` |
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
│       │   ├── module/chat/       # AI 对话、Agent、RAG、MCP
│       │   └── module/almanac/    # 现代黄历
│       └── resources/
│           ├── prompt/            # Supervisor / Planner / Query / Executor 提示词
│           ├── application.yml
│           └── application-dev.yml
├── front/                   # Vue 前端
│   └── src/
│       ├── api/             # user/todo/calendar/chat/almanac API 封装
│       ├── components/      # 通用组件
│       ├── views/           # Layout/Home/Today/Chat 页面
│       ├── router/
│       └── utils/
├── scripts/                 # RAG 语料处理脚本
│   ├── dedup_corpus.py
│   └── rag_data/
├── sql/                     # 表结构与初始化数据
└── data/lucene-rag-index/   # Lucene 本地索引
```

---

## 后端模块

| 模块 | 说明 |
| --- | --- |
| `user` | 注册、登录、当前用户信息、登出 |
| `todo` | 目标待办 CRUD、按日期展开、每日完成状态、增删单日任务 |
| `dailyNote` | 月待办数量、日详情、每日笔记 upsert |
| `chat` | 多会话对话、SSE 流式响应、Agent 调度、草稿确认、MCP 工具、RAG 检索 |
| `almanac` | 现代黄历计算与查询 |

核心 Agent 类：

- `ChatServiceImpl`：对话入口，负责历史加载、确认状态、快捷指令、落库、SSE 进度事件。
- `AgentOrchestrator`：Supervisor 调度循环，判断是否调用子 Agent。
- `SupervisorTools`：向 Supervisor 暴露 `plan_task`、`query_calendar`、`execute_task`。
- `SubAgent`：Planner、Query、Executor 的通用执行抽象，包含工具调用和自纠错。
- `PlanDraftService`：保存规划草稿，并在确认后批量创建待办。
- `DirectCommandService`：处理简单自然语言指令的本地快路径。
- `RagService`：BM25 + Qdrant 混合检索、RRF 融合、Rerank、缓存。
- `McpToolRegistry`：注册 AI/MCP 可调用的日程工具。

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
| `yl_agent_flow_state` | 当前会话的 Agent 待确认/待反馈状态 |
| `yl_user_memory` | 用户长期偏好、长期目标和行为习惯记忆 |

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

### AI 对话 `/chat`

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| POST | `/chat` | 非流式对话 | 是 |
| POST | `/chat/stream` | SSE 流式对话，包含 `progress` 和 `responseTime` 事件 | 是 |
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

### 记忆 `/memory`

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| GET | `/memory` | 查询当前用户 active 长期记忆，可用 `type` 过滤 | 是 |
| DELETE | `/memory/{memoryId}` | 删除一条长期记忆 | 是 |
| POST | `/memory/refresh-behaviors` | 根据最近 30 天待办完成情况刷新行为习惯记忆 | 是 |

---

## AI 工作流

```text
用户输入
  ├─ 异步抽取长期偏好/目标记忆
  ├─ 已有 AgentFlowState？
  │    ├─ 用户取消：清理状态
  │    ├─ 用户确认：进入 Planner 或 Executor
  │    └─ 用户补充：更新 pending_task，必要时重新规划
  ├─ 命中待同步 PlanDraft 确认语义？
  │    └─ 批量同步待办到日历
  ├─ DirectCommandService 快捷指令命中？
  │    └─ 本地直接执行/查询
  └─ Supervisor 判断类型
       ├─ NONE：直接回复
       ├─ QUERY：调用 Query Agent
       ├─ EXECUTE：写入执行确认状态，等待用户确认
       └─ PLAN_CONFIRM：写入规划确认状态，等待用户确认后调用 Planner
```

规划类任务会先输出草稿预览。用户确认后，`PlanDraftService` 将 Planner 的结构化 JSON 转换为多个 `TodoCreateDTO`，批量写入日历。
Planner 生成计划前会读取 active 用户记忆，并按“本轮明确指令 > pending 状态 > 长期记忆 > 公共 RAG”的优先级作为参考。

---

## RAG 语料处理

语料位于 `scripts/rag_data/`，处理脚本为 `scripts/dedup_corpus.py`。流程包括 Markdown 分块、TF-IDF 去重、SimHash 去重、MinHash 去重、Embedding、Qdrant 入库，并导出处理统计。

```bash
cd scripts
pip install -r requirements.txt

# 去重 + Ollama bge-m3 embedding + Qdrant 入库
python dedup_corpus.py -i ./rag_data --embedding-backend ollama --qdrant

# 只做去重并导出 JSON
python dedup_corpus.py -i ./rag_data --skip-embedding

# 使用 OpenAI 兼容 embedding 服务
python dedup_corpus.py -i ./rag_data \
  --embedding-backend openai \
  --embedding-url https://dashscope.aliyuncs.com/compatible-mode \
  --embedding-model text-embedding-v4 \
  --embedding-api-key your_api_key \
  --qdrant
```

后端默认读取 `data/lucene-rag-index` 作为 Lucene 索引目录，Qdrant 默认连接 `localhost:6334`，集合名为 `rag_corpus`。

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
- Qdrant：默认 gRPC 端口 `6334`，集合 `rag_corpus`
- Embedding：可使用 Ollama `bge-m3`，或阿里云 `text-embedding-v4`
- Chat Model：OpenAI 兼容接口，默认配置在 `back/src/main/resources/application-dev.yml`

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
  ai:
    api-key: ${unknown.aliyun.api-key}
    base-url: ${ALIYUN_AI_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
    model: ${ALIYUN_QWEN_MODEL:qwen3.7-plus}
    planner-model: ${ALIYUN_PLANNER_MODEL:deepseek-v4-flash}
  qdrant:
    host: localhost
    port: 6334
    collection: rag_corpus
  rag:
    embedding-provider: aliyun
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

Vite 会代理 `/user`、`/todo`、`/chat`、`/calendar`、`/almanac`、`/memory`、`/daily-note`、`/test` 到 `http://localhost:8080`。

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
- [x] Supervisor + Planner + Query + Executor 多 Agent 架构
- [x] 写操作确认流与 AgentFlowState 状态管理
- [x] Planner 草稿预览与确认后同步到日历
- [x] MySQL 长期偏好记忆与行为习惯记忆
- [x] DirectCommand 快捷指令快路径
- [x] MCP `tools/list` / `tools/call`
- [x] Lucene BM25 + Qdrant 向量检索 + RRF + Rerank
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
