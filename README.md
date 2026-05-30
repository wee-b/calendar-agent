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
| 缓存 | Redis（Sa-Token 持久化） |
| AI | 原生 OpenAI API + Tool Calling + 意图路由 |
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
│   └── chat/                #   AI 对话模块
│       ├── controller/ChatController.java
│       ├── mapper/AiDialogueMapper.java
│       └── service/
│           ├── ChatService.java          # 对话管理接口
│           ├── ChatServiceImpl.java      # 对话管理实现（会话增删查 + 意图增强）
│           ├── OpenAiService.java        # OpenAI API 客户端（Tool Calling 循环）
│           └── ChatToolService.java      # 工具桥接层（8 个业务工具函数）
├── util/
│   ├── SaTokenUtil.java       # Token 工具（生成/校验/解析/注销）
│   ├── LoginUserContext.java  # 当前用户上下文（ThreadLocal）
│   ├── ResponseUtil.java      # HttpServletResponse 写 JSON
│   └── PromptLoader.java      # 提示词文件加载器
└── resources/
    └── prompt/
        └── chat-system.txt    # AI 系统提示词（意图路由 + 确认流程）
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

#### AI 语音助手
```
用户语音/文字输入 → POST /chat { sessionId, message }
→ ChatServiceImpl 构建系统提示词（注入日期上下文 + 意图关键词增强）
→ OpenAiService 调用 OpenAI API（携带 8 个 Tool Definitions）
→ LLM 返回 tool_call → executeToolCalls() 执行对应业务函数
→ 工具结果回传 LLM → LLM 生成自然语言总结
→ 保存对话记录（user + assistant） → 返回 ChatResponseVO
```

### AI 意图路由

系统提示词 `prompt/chat-system.txt` 实现了 5 种意图的标准化处理流程：

| 意图 | 触发关键词 | 流程 |
|------|-----------|------|
| 创建 | 创建/添加/新增/安排/加一个 | 追问缺失信息 → 展示方案 → 确认 → createTodo |
| 删除 | 删除/去掉/移除/取消/删了 | queryTodoList → 匹配 → 确认 → deleteTodo |
| 查询 | 查看/有哪些/今天/明天/几号 | 直接调用对应查询工具 |
| 标记完成 | 完成/搞定/做完了/打卡 | 调用 toggleTodoDate |
| 修改 | 修改/改成/改一下/调整 | queryTodoList → 确认 → updateTodo |

8 个 AI 可调用工具：`createTodo` / `queryTodoList` / `queryMonthCount` / `queryDayDetail` / `deleteTodo` / `updateTodo` / `toggleTodoDate` / `saveDailyNote`

安全约束：禁止在用户确认前调用 createTodo/deleteTodo/updateTodo，回复限制 150 字以内纯文本。

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
- [x] AI 对话模块（7 个接口 / 8 个 Tool Definitions / Function Calling 循环 / 意图路由提示词）
- [x] 测试数据（user_id=36，5 个目标覆盖不同颜色/周期/完成状态）
- [ ] TTS 语音合成朗读
- [ ] 前后端联调 + 部署

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
- **语音助手** — 右侧可折叠面板，多会话管理（下拉选择器切换/新建/删除），对话气泡展示 + typing 动画，AI 消息操作栏（复制/朗读/撤回），Web Speech API 语音识别（中文连续识别，默认开启），语音关键词控制（说"发送"提交 / "确认"执行 / "取消"放弃），Enter 键盘发送
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
- [x] 日历对接 `month-count` 接口（每日待办数量 + 颜色深浅）
- [x] 农历 + 节日/节气显示（`utils/lunar.ts`，1900-2100 年数据 + 24 节气 + 除夕）
- [x] 待办选中联动日历高亮（跳转月份 + dates 日期高亮）
- [x] 日历上下月切换按钮
- [x] 日详情面板（DayDetailPanel.vue，双栏 + 乐观状态切换 + 日记保存）
- [x] 语音助手面板（多会话管理 + 语音识别 + 打字动画 + 消息操作）
- [x] 帮助指南（Help.vue，命令卡片 + 操作流程）
- [x] 非强制登录（登录弹窗可关闭，未登录可浏览，NavBar 显示"去登录"）
- [x] 退出登录对接 `/user/logout` 接口
- [x] `.env` 环境变量配置（`VITE_TOKEN_KEY`）
- [x] Vite 开发代理配置
- [x] 日历格子响应式宽高比（`aspect-ratio: 4/3`）
- [ ] TTS 语音合成朗读（`SpeechSynthesisUtterance` Web API 已预留）
- [ ] 前后端联调 + 部署
