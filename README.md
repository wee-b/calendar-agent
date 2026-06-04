# 语音日历工具 (Voice Calendar)

基于语音交互的智能日历管理工具，支持通过语音添加/删除/查看待办事件。


---

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | Vue 3 + TypeScript + Vite 8 |
| 后端 | Spring Boot 3.5 + Sa-Token + MyBatis-Plus |
| 数据库 | MySQL 8.0 |
| 连接池 | Druid |
| 缓存 | Redis（Token 持久化 + RAG Embedding/结果多级缓存） |
| AI 框架 | LangChain4j 1.3 + DeepSeek v4（OpenAI 兼容 API） |
| AI 架构 | Supervisor 多智能体编排 + Tool Calling + 流式 SSE |
| 向量检索 | Milvus 2.4 + BGE-M3 Embedding（Ollama 本地部署） |
| 全文检索 | Lucene 9.11 + SmartChineseAnalyzer（BM25） |
| 语音 | Web Speech API（浏览器 STT + TTS） |
| 密码加密 | BCrypt |
| 文档 | Knife4j (OpenAPI 3) |

---

## 后端

### 项目结构

```
back/src/main/java/com/qiniu/back/
├── annotation/              # 自定义注解
│   ├── NoNeedLogin.java     #   标记接口无需登录
│   └── CheckPhone.java      #   手机号格式校验
├── config/                  # 配置
│   ├── LangChainConfig.java    # DeepSeek ChatModel Bean（流式 + 非流式）
│   ├── ClientTokenConfig.java  # Sa-Token 客户端 Token 配置
│   ├── MvcConfigure.java       # 拦截器 + CORS 注册
│   └── StartupListener.java    # 启动成功打印文档地址
├── domain/                  # 领域模型
│   ├── ErrorCode.java       #   统一错误码枚举
│   ├── ResponseDTO.java     #   统一响应体
│   ├── chat/                #   AI 对话
│   │   ├── AiDialogue.java          # 实体
│   │   ├── dto/ChatRequestDTO.java  # 请求 DTO
│   │   ├── vo/ChatResponseVO.java   # 响应 VO
│   │   ├── vo/ChatHistoryItemVO.java    # 历史记录 VO
│   │   └── vo/ChatSessionVO.java       # 会话列表 VO
│   ├── todo/                #   待办
│   │   ├── Todo.java                # 实体（目标）
│   │   ├── TodoDate.java            # 实体（每日任务）
│   │   ├── DailyNote.java           # 实体（每日日记）
│   │   ├── dto/TodoCreateDTO.java   # 创建 DTO
│   │   ├── dto/TodoUpdateDTO.java   # 修改 DTO
│   │   ├── dto/TodoDateToggleDTO.java # 完成/取消完成 DTO
│   │   ├── dto/DailyNoteSaveDTO.java  # 保存日记 DTO
│   │   ├── vo/TodoVO.java           # 待办响应 VO
│   │   ├── vo/DayTodosVO.java       # 某天详情 VO
│   │   └── vo/MonthCountVO.java     # 每日待办数量 VO
│   └── user/                #   用户
│       ├── User.java                # 实体
│       ├── dto/UserLoginDTO.java    # 登录 DTO
│       ├── dto/UserRegisterDTO.java # 注册 DTO
│       ├── vo/LoginVO.java          # 登录响应 VO
│       ├── vo/UserInfoVO.java       # 用户信息 VO
│       └── vo/UserVO.java           # 用户列表 VO
├── enumeration/             # 枚举
│   ├── DeletedFlagEnum.java #   删除状态
│   └── GenderEnum.java      #   性别
├── exception/
│   └── BusinessException.java  # 业务异常
├── handler/
│   ├── LoginInterceptor.java      # 登录拦截器（Token 校验 + 权限）
│   └── GlobalExceptionHandler.java  # 全局异常处理
├── module/
│   ├── TestController.java  # 测试接口
│   ├── user/                #   用户模块
│   │   ├── controller/UserController.java
│   │   ├── mapper/UserMapper.java
│   │   └── service/...
│   ├── todo/                #   待办模块
│   │   ├── controller/TodoController.java
│   │   ├── mapper/TodoMapper.java, TodoDateMapper.java
│   │   └── service/...
│   ├── dailyNote/           #   日记/日历模块
│   │   ├── controller/DailyNoteController.java
│   │   ├── mapper/DailyNoteMapper.java
│   │   └── service/...
│   └── chat/                #   AI 对话模块（Supervisor 多智能体编排）
│       ├── controller/ChatController.java   # 对话接口（流式 SSE + 非流式）
│       ├── mapper/AiDialogueMapper.java
│       ├── mcp/
│       │   ├── McpController.java          # MCP JSON-RPC 端点（tools/list + tools/call）
│       │   ├── McpToolDefinition.java      # 工具定义（名称/描述/Schema/执行器/重试配置）
│       │   └── McpToolRegistry.java        # 工具注册中心（10 个工具 + 自动重试）
│       ├── rag/
│       │   ├── RagService.java             # 混合检索引擎（BM25 + Dense + RRF + Rerank）
│       │   ├── RagConfig.java              # Milvus/RestTemplate Bean 配置
│       │   ├── RagProperties.java          # RAG 参数配置（TopK/阈值/缓存 TTL）
│       │   └── RagHit.java                 # 检索结果实体
│       ├── agent/
│       │   ├── AgentOrchestrator.java      # Supervisor 主控（调度 + 管线验证）
│       │   ├── SupervisorTools.java        # 子 Agent 管理 + RAG 上下文注入
│       │   └── SubAgent.java               # 子 Agent 抽象（Builder 模式 + 自纠错）
│       └── service/
│           ├── ChatService.java            # 对话管理接口
│           ├── ChatServiceImpl.java        # 对话管理实现（会话 CRUD + 历史管理）
│           └── ChatToolService.java        # 工具桥接层（10 个业务工具函数）
├── util/
│   ├── DigestUtil.java         # MD5 哈希工具（缓存 Key 生成）
│   ├── SaTokenUtil.java        # Token 工具（生成/校验/解析/注销）
│   ├── LoginUserContext.java   # 当前用户上下文（ThreadLocal）
│   ├── ResponseUtil.java       # HttpServletResponse 写 JSON
│   └── PromptLoader.java       # 提示词文件加载器
└── resources/
    └── prompt/
        ├── supervisor-system.txt   # Supervisor 系统提示词（路由规则）
        ├── planner-system.txt      # 规划师 Agent 提示词（JSON 计划输出）
        ├── query-system.txt        # 查询 Agent 提示词（只读约束）
        └── executor-system.txt     # 执行者 Agent 提示词（写入 + 自检）
```

### 数据库表

| 表名 | 说明 | 关键设计 |
|------|------|----------|
| `yl_user` | 用户表 | phone 唯一索引，BCrypt 密码加密 |
| `yl_todo` | 待办表（目标） | 支持 startDate + endDate + weekDays，最多 180 天 |
| `yl_todo_date` | 每日任务关联表 | todo 与日期的多对多，存储每天具体任务内容 |
| `yl_daily_note` | 每日日记表 | 每个用户每天最多一条 |
| `yl_ai_dialogue` | AI 对话记录表 | sessionId 分组多轮对话，role 区分 user/assistant |

### API 接口

#### 用户模块 `/user`

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/user/register` | 注册 | 否 |
| POST | `/user/login` | 登录，返回 token | 否 |
| GET | `/user/info` | 获取当前用户信息 | 是 |
| PUT | `/user/info` | 修改用户信息 | 是 |
| POST | `/user/logout` | 退出登录 | 是 |

#### 待办模块 `/todo`

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/todo` | 创建目标（自动计算日期并批量插入） | 是 |
| GET | `/todo/list` | 查询当前用户所有待办 | 是 |
| PUT | `/todo/{todoId}` | 修改待办（删旧日期 + 重新计算 + 批量插入） | 是 |
| DELETE | `/todo/{todoId}` | 删除待办（批量删日期 + 软删除） | 是 |
| PUT | `/todo/toggle-date` | 完成/取消完成某天任务 | 是 |

#### 日历/日记模块

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/calendar/month-count?year=2026&month=6` | 当月每日待办数量（日历小圆点） | 是 |
| GET | `/calendar/day?date=2026-06-01` | 某天所有待办 + 日记 | 是 |
| PUT | `/daily-note` | 保存/修改某天日记 | 是 |

#### AI 对话模块 `/chat`

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/chat` | 发送对话消息（含 Tool Calling 自动循环） | 是 |
| POST | `/chat/new-session` | 开启新对话，返回 sessionId | 是 |
| GET | `/chat/history` | 获取指定会话历史记录 | 是 |
| DELETE | `/chat/session` | 删除整个会话 | 是 |
| DELETE | `/chat/last-round` | 撤回上一轮对话 | 是 |
| GET | `/chat/sessions` | 获取用户所有历史会话列表 | 是 |
| GET | `/chat/latest` | 获取用户最新对话历史记录 | 是 |

#### MCP 协议 `/mcp`

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/mcp` | JSON-RPC 端点（method: tools/list / tools/call） | 是 |

#### 测试模块 `/test`

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/test/testConnection` | 测试连接 | 否 |
| GET | `/test/testLogin` | 测试登录状态 | 是 |
| GET | `/test/getToken?userId=1` | 获取测试 Token | 否 |

### 核心业务流程

#### 创建待办（自动日期计算）
```
用户输入 → "每周一到周五学英语，从6月1日到6月30日"
→ POST /todo { title, startDate, endDate, weekDays: [1,2,3,4,5], dayContent, color }
→ 校验跨度 ≤ 180天 → 插入 yl_todo
→ 遍历日期区间，匹配周几 → 批量插入 yl_todo_date
→ 返回 TodoVO（含所有日期列表）
```

#### 日历查看
```
打开日历 → GET /calendar/month-count → 日历格子渲染小圆点数量
点击某天 → GET /calendar/day → 返回待办列表 + 日记内容
侧边栏点击待办 → 高亮该待办的所有日期（用 color 字段）
点击每日任务 → PUT /todo/toggle-date → 完成/取消完成（乐观更新）
```

#### AI 语音助手（Supervisor 多智能体编排）

```
用户语音/文字输入 → POST /chat { sessionId, message }
→ ChatServiceImpl 加载历史消息（最近 20 对）
→ AgentOrchestrator 构建 Supervisor 提示词（注入日期上下文）
→ Supervisor(DeepSeek, t=0.3) 决策路由：
    ├── plan_task → RagService.search() 检索 RAG 语料
    │              → Planner Agent(t=0.1) 输出 JSON 计划
    ├── query_calendar → Query Agent(t=0.3) 执行只读查询
    └── execute_task → Executor Agent(t=0.3) 执行写入操作
                         └── 管线验证：plan→execute 后自动查重确认
→ 工具调用层自动重试（瞬态异常 500ms 后重试 1 次）
→ Agent 输出自纠错（无效 JSON / 错误结果自动纠正 1 次）
→ 保存对话记录（user + assistant）→ SSE 流式输出 / 返回完整响应
```

#### RAG 混合检索流程

```
RagService.search(query)
  ├─ [L2 缓存检查] rag:result:{md5} → 命中直接返回
  ├─ searchBm25(query) → Lucene BM25 → Top-10
  ├─ searchDense(query)
  │    ├─ [L1 缓存检查] rag:emb:{md5} → 命中跳过 Ollama
  │    ├─ Ollama /api/embeddings → BGE-M3 1024d
  │    └─ Milvus COSINE 检索 → Top-10
  ├─ rrfFusion(bm25, dense, topK*3) → RRF(K=60) → 阈值过滤(≥0.01) → Top-9
  ├─ LLM Rerank(t=0.0) → DeepSeek 重排 → Top-3
  └─ [写 L2 缓存] rag:result:{md5} → TTL 30min
```

### Supervisor 多智能体编排

系统采用 **Supervisor + 3 个子 Agent** 的协作架构，替代旧版单 Agent + 意图路由模式：

| 角色 | Agent | 温度 | 工具 | 职责 |
|------|-------|------|------|------|
| 调度者 | Supervisor | 0.3 | plan_task / query_calendar / execute_task | 分析需求，路由到正确的子 Agent |
| 规划师 | Planner | 0.1 | 无（纯推理） | 将复杂需求拆解为结构化 JSON 计划 |
| 检测员 | Query | 0.3 | queryTodoList / queryMonthCount / queryDayDetail | 只读查询，查重验证 |
| 执行者 | Executor | 0.3 | 全部 10 个工具 | 创建/修改/删除 + 操作后自查 |

**路由规则**：
- 简单操作（删除/修改/toggle/≤2 个待办创建）→ 直接 `execute_task`
- 查询询问 → 直接 `query_calendar`
- 复杂规划（模糊需求/批量创建）→ `plan_task` → `query_calendar`（查重）→ `execute_task`（分批执行）

**10 个 AI 可调用工具**：`createTodo` / `deleteTodo` / `updateTodo` / `toggleTodoDate` / `saveDailyNote` / `removeTodoDay` / `addTodoDay` / `queryTodoList` / `queryMonthCount` / `queryDayDetail`

### AI 容错与性能优化

| 机制 | 层级 | 说明 |
|------|------|------|
| 工具级重试 | McpToolRegistry | 瞬态异常（DB 超时/连接断开/网络抖动）自动重试 1 次，间隔 500ms |
| Agent 自纠错 | SubAgent | Planner 输出非 JSON / 工具返回 error 时，追加纠正提示重新执行 1 次 |
| 管线验证 | AgentOrchestrator | plan_task → execute_task 链路走完后自动注入 query_calendar 验证操作落地 |
| Embedding 缓存 | RagService L1 | `rag:emb:{md5(query)}` TTL 1h，命中免 Ollama 调用 |
| RAG 结果缓存 | RagService L2 | `rag:result:{md5(query)}` TTL 30min，命中跳过全链路 |
| 上下文截断 | SubAgent / Orchestrator | 工具结果 800 字符 / 子 Agent 结果 1500 字符截断，控制 prompt token |
| 温度分离 | 各 Agent | Planner 0.1（确定性 JSON）→ Supervisor/Query/Executor 0.3（低幻觉）→ Rerank 0.0 |
| 召回阈值 | RagService | RRF 融合后过滤 score < 0.01 的低质语料，减少 Rerank 噪声 |
| Prompt 精简 | prompt/*.txt | 去表格/去冗余否定/合并重复，每条消息减少 ~33% token |

### 快速启动

```bash
# 1. 创建数据库并导入表结构和测试数据
mysql -u root -p < sql/tables.sql
mysql -u root -p < sql/insert_data.sql

# 2. 修改数据库/Redis 连接信息 + AI API 配置
#    编辑 back/src/main/resources/application-dev.yml
#    需配置：spring.datasource / spring.data.redis / app.ai.*

# 3. 启动后端
cd back
mvn spring-boot:run

# 4. 获取测试 Token
curl http://localhost:8080/test/getToken?userId=36

# 5. 启动前端
cd front
npm install && npm run dev
```

### 接口文档

启动后访问 **http://localhost:8080/doc.html** 查看 Knife4j 接口文档。

### 认证流程

1. 调用 `/user/login` 或 `/test/getToken` 获取 token
2. 后续请求 Header 携带 `yvli-token: <token值>`
3. `LoginInterceptor` 自动解析 token → 查询用户 → 存入 `LoginUserContext`
4. 业务代码通过 `LoginUserContext.getUser()` 获取当前用户
5. 登出时调用 `/user/logout`，token 存储在 Redis 15 号库

### 开发进度

- [x] 项目骨架搭建 + 依赖管理
- [x] 数据库表设计（用户表 / 待办表 / 日期关联表 / 日记表 / 对话表）
- [x] 实体类 + DTO + VO
- [x] Sa-Token 认证 + 登录拦截器 + Redis 持久化
- [x] 用户模块（注册 / 登录 / 信息 / 修改 / 登出）
- [x] 待办模块（创建 / 列表 / 修改 / 删除）
- [x] 每日任务完成/取消完成（含自动联动待办整体状态）
- [x] 日历模块（当月每日待办数量 / 某天详情）
- [x] 日记模块（保存/修改某天日记）
- [x] AI 对话模块（7 个接口 / SSE 流式输出 / 会话管理 / 历史撤回）
- [x] Supervisor 多智能体编排（AgentOrchestrator + 3 子 Agent 分治协作）
- [x] MCP 协议支持（tools/list + tools/call JSON-RPC 端点）
- [x] RAG 混合检索（BM25 + BGE-M3 Milvus + RRF 融合 + LLM Rerank）
- [x] 日程规划领域 RAG 知识库（备考/课业/考试 8 大场景语料）
- [x] 10 个工具完整覆盖（含 removeTodoDay / addTodoDay 按日精细化管控）
- [x] Redis 多级缓存（Embedding L1 + RAG 结果 L2）
- [x] 温度分离（Planner 0.1 / Supervisor 0.3 / Query 0.3 / Executor 0.3 / Rerank 0.0）
- [x] 工具级重试（瞬态异常自动重试，可重试/不可重试分类判断）
- [x] Agent 输出自纠错（非 JSON 自动纠正，错误结果启发式重试）
- [x] 管线验证（plan→execute 后自动查重确认操作落地）
- [x] 上下文截断（工具结果 800 字符 / 子 Agent 结果 1500 字符）
- [x] Prompt 精简 + TopK 参数调优 + 召回阈值过滤
- [x] 工具结果结构化错误响应（`{"error":"...","retried":N}`）
- [x] N+1 查询优化（listByUser 批量 IN 查询 / batchInsert 一条 SQL）
- [x] 历史消息加载修复（ASC→DESC+reverse，始终取最新 N 条）
- [x] TTS 语音合成朗读（SpeechSynthesisUtterance + 暂停/继续/重播）

---

## 前端

### 项目结构

```
front/src/
├── App.vue                      # 根组件（全局样式重置）
├── main.ts                      # 入口（Vue + Router 挂载）
├── router/index.ts              # 路由（Login / Home）+ 路由守卫
├── api/                         # API 封装层
│   ├── user.ts                  #   用户 API（登录/注册/信息）
│   ├── todo.ts                  #   待办 API（CRUD + 完成）
│   ├── calendar.ts              #   日历 API（月统计 + 日详情 + 日记）
│   └── chat.ts                  #   对话 API（发送消息 + 会话管理）
├── utils/
│   ├── request.ts               #   axios 实例（baseURL + 拦截器 + token 注入）
│   ├── auth.ts                  #   token 存取工具
│   └── lunar.ts                 #   农历工具（1900-2100 年数据 + 24 节气 + 节日）
├── .env                          # 环境变量（VITE_TOKEN_KEY）
├── components/
│   ├── NavBar.vue               #   顶部导航栏（面板切换 + 退出 + 帮助入口）
│   ├── AuthModal.vue            #   登录/注册弹窗（已对接后端）
│   └── Help.vue                 #   语音助手使用指南（命令卡片 + 操作流程）
└── views/
    ├── LoginView.vue            # 登录/注册页面
    └── HomeView/
        ├── HomeView.vue         # 主页布局（三栏 + 日历核心逻辑）
        └── components/
            ├── TodoList.vue     # 左侧待办清单面板（完整 CRUD + 日历联动）
            ├── ChatPanel.vue    # 右侧语音助手面板（多会话 + 语音识别）
            └── DayDetailPanel.vue  # 底部日详情弹窗（待办勾选 + 日记编辑）
```

### 技术栈

| 技术 | 版本 |
|------|------|
| Vue | 3.5 |
| TypeScript | 6.0 |
| Vite | 8.0 |
| Vue Router | 4.6 |

### 页面说明

- **登录/注册** — `AuthModal.vue` 弹窗组件，已对接 `/user/login` 和 `/user/register`，Token 自动存入 localStorage，可关闭不强制登录
- **主页** — 三栏布局可折叠：左待办 + 中日历 + 右语音助手，NavBar 未登录时显示"去登录"
- **日历** — 月视图 7×6 网格，支持上月/下月切换（`◀` `▶`）和月份快速选择器；农历/节日/节气显示（1900-2100 完整数据）；每日待办数量标记（右上角圆角方形，数量越多颜色越深）；待办选中日期高亮联动
- **待办清单** — 左侧可折叠面板，完整 CRUD（创建/修改弹窗、删除二次确认、星期 chip 多选），颜色圆点 + 剩余天数标记，点击选中联动日历跳转月份并高亮日期
- **日详情面板** — 底部弹窗组件，上滑动画，双栏布局；左栏展示当日待办列表（状态圆圈点击切换，乐观更新 UI），右栏日记 textarea 编辑 + 保存
- **语音助手** — 右侧可折叠面板，多会话管理（下拉选择器切换/新建/删除），对话气泡展示 + typing 动画，AI 消息操作栏（复制/朗读暂停继续/撤回），Web Speech API 语音识别（中文连续识别，默认关闭手动/语音双模式切换），语音关键词控制（说"发送"提交 / "确认"执行 / "取消"放弃），Enter 键盘发送，语音模式 AI 回复后自动朗读，真实音频分贝检测条（Web Audio API + AnalyserNode 10 段频段）
- **帮助指南** — 底部弹窗，2 列命令卡片（查看待办/查看安排/创建/修改/删除/完成任务/写日记），语音操作 + 键盘快捷键说明
- **API 层** — `request.ts` 统一封装 axios，Vite 代理转发 `/api`，自动注入 `yvli-token`，401 拦截显示登录弹窗

### 快速启动

```bash
cd front
npm install
npm run dev
```

### 开发进度

- [x] Vue 3 项目搭建 + 路由
- [x] 登录/注册 UI + 后端 API 对接
- [x] axios 封装 + token 自动注入 + 统一错误处理
- [x] 主页三栏布局 + 日历组件
- [x] 待办 API 模块（`api/todo.ts`）
- [x] 日历 API 模块（`api/calendar.ts`）
- [x] 对话 API 模块（`api/chat.ts`，6 个函数）
- [x] 待办清单面板（完整 CRUD 弹窗 + 日历联动高亮）
- [x] 日历对接 month-count + 彩色圆点（按待办颜色渲染，匹配左侧列表）
- [x] 农历 + 节日/节气显示（1900-2100 年数据 + 24 节气 + 除夕）
- [x] 待办选中联动日历高亮（跳转月份 + dates 日期高亮）
- [x] 日历上下月切换按钮 + 月份快速选择器
- [x] 日详情面板（DayDetailPanel，双栏 + 乐观状态切换 + 日记保存）
- [x] 语音助手面板（多会话管理 + 语音识别 + 打字动画 + 消息操作）
- [x] 语音模式（手动/语音双模式，切换后持久化，AI 返回后自动恢复）
- [x] 分贝检测条（Web Audio API 真实音频检测，10 段频段，抽屉式展开/收起动画）
- [x] TTS 朗读（语音模式自动朗读 AI 回复，每条消息可暂停/继续/重播）
- [x] 语音错误自动恢复（识别失败静默重启，不弹错误提示）
- [x] 帮助指南（Help.vue，命令卡片 + 操作流程）
- [x] 非强制登录（登录弹窗可关闭，未登录可浏览）
- [x] 退出登录对接 `/user/logout` 接口
- [x] `.env` 环境变量配置（`VITE_TOKEN_KEY`）
- [x] Vite 开发代理配置
- [x] 聊天超时 30s（组件级超时提示）
