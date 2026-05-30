# 开发日志

## 2026-05-29

### 上午

- **项目初始化** — 搭建 Spring Boot 空项目骨架，创建前后端目录结构
- **数据库设计**
  - 完成 `yl_user` 用户表 DDL
  - 完成 `yl_todo` 待办表 DDL（初版）
  - 完成 `yl_ai_dialogue` AI 对话记录表 DDL
  - 写入初始测试数据
- **依赖管理** — 引入 MyBatis-Plus、MySQL、Druid、Knife4j、Lombok、P6Spy

### 下午

- **领域模型**
  - 创建 `domain/user` 实体 + DTO（登录/注册）+ VO（用户信息/登录响应）
  - 创建 `domain/event` 实体 + DTO（保存/查询）+ VO
  - 创建 `domain/chat` 实体 + DTO（对话请求）+ VO（对话响应）
  - 创建 `ResponseDTO` 统一响应体、`ErrorCode` 错误码枚举
- **基础组件**
  - 创建 `@NoNeedLogin`、`@CheckPhone` 注解
  - 创建 `PhoneValidator` 校验器
  - 创建 `BusinessException` 业务异常类
  - 创建 `GlobalExceptionHandler` 全局异常处理
- **认证体系** — 引入 Sa-Token，创建 `ClientTokenConfig`、`SaTokenUtil`、`LoginInterceptor`、`LoginUserContext`、`ResponseUtil`

### 晚上

- **后端-用户模块**
  - 完成注册/登录/获取用户信息/修改用户信息/退出登录接口
  - 添加 `spring-security-crypto` 用于 BCrypt 密码加密
  - 添加 `sa-token-redis-jackson` + `spring-boot-starter-data-redis`，Token 持久化到 Redis
  - 创建 `StartupListener` 启动打印文档地址
  - 创建 `TestController` 测试接口
  - CORS 跨域配置
- **前端-项目搭建**
  - Vue 3 + TypeScript + Vite 8 + Vue Router 4 项目初始化
  - 登录/注册页面 UI、主页三栏布局、日历组件
  - NavBar、ChatPanel、TodoList 组件
  - 全局复古纸张主题设计
- **数据库重构**
  - `yl_todo` 重新设计：移除 `todo_time`、`content`，新增 `start_date`、`end_date`、`week_days`、`color`
  - 新增 `yl_todo_date` 每日任务关联表（支持多日目标 + dayContent 每日具体任务）
  - 新增 `yl_daily_note` 每日日记表
  - 建表 SQL 中加入注释说明每个功能如何通过字段实现
- **后端-待办模块**
  - 创建待办（自动根据 startDate/endDate/weekDays 计算日期 → 批量插入 yl_todo_date）
  - 查询用户所有待办（含日期列表）
  - 修改待办（删旧日期 → 重新计算 → 批量插入）
  - 删除待办（批量删日期 + 软删除待办）
  - 完成/取消完成每日任务（联动更新待办整体状态）
- **后端-日历/日记模块**
  - 当月每日待办数量查询（日历小圆点）
  - 某天详情查询（待办列表 + dayContent + color + 日记内容）
  - 日记保存/修改（upsert）
- **文档** — 编写 README.md 和 CHANGELOG.md，所有 DTO 补全 `@Schema` 注解
- **测试数据** — 生成完整测试数据集（user_id=36，5 个目标涵盖不同颜色/周期/完成状态，30 行每日任务，6 条日记，10 条对话）
- **前端-API 层对接**
  - `utils/request.ts` — axios 封装，baseURL 自动注入 `yvli-token`，401 拦截跳转登录
  - `utils/auth.ts` — Token 存取工具
  - `api/user.ts` — 用户 API 模块（注册/登录/信息）
  - `api/todo.ts` — 待办 API 模块（CRUD + 完成）
  - `api/calendar.ts` — 日历 API 模块（月统计 + 日详情 + 日记）
  - `utils/lunar.ts` — 农历工具
  - `AuthModal.vue` — 登录/注册弹窗组件，已对接后端

---

## 2026-05-30

### 前端功能完善

- **登录/退出优化**
  - 退出登录改为不跳转页面（clearAuth + 显示登录弹窗）
  - 登录弹窗改为非强制（点击遮罩可关闭，未登录可浏览页面）
  - NavBar 未登录时显示"去登录"按钮，点击弹出登录弹窗，已登录时显示用户名和下拉菜单
  - 退出登录调用 `/user/logout` 接口后再清除本地凭证
  - `auth.ts` 新增 `isLoggedIn()` 判断方法
- **环境变量配置** — 创建 `.env`，提取 `VITE_TOKEN_KEY=yvli-token` 和 `VITE_API_BASE_URL`，`auth.ts` 和 `request.ts` 改为读取环境变量
- **待办清单 (TodoList.vue)**
  - 完整 CRUD 对接后端 `/todo` 接口
  - 顶部"创建待办"按钮 → 弹窗（标题/颜色/dayContent/起止日期/每周执行日 chip 选择）
  - 每行左侧颜色圆点，右侧 `···` 菜单（修改/删除），点击外部自动关闭
  - 右侧显示剩余天数圆角标签（`dates.length`），悬浮提示"还有xx天的任务"，已完成显示 ✓
  - 未登录点击创建时提示"请先登录"
- **日历增强 (HomeView.vue)**
  - 对接 `/calendar/month-count`：右上角方形标签显示每日待办数量，数量越多颜色越深
  - 农历 + 节日/节气显示（`utils/lunar.ts`，含 1900-2100 年数据、24 节气、公历/农历节日）
  - 待办选中联动：点击左侧待办 → 跳转到 dates[0] 所在月份 → 该待办所有日期高亮（半透明底色）
  - 日历格子 `aspect-ratio: 4/3`，wrapper `max-width: 980px; min-width: 500px` 防止挤压
- **日详情面板**
  - 点击日历日期 → 调用 `/calendar/day` 获取当日待办 + 日记
  - 展开面板显示：待办列表（颜色圆点/标题/每日任务描述/完成状态）+ 日记 textarea
  - 日记支持编辑 + 保存（`PUT /daily-note`）
  - 面板 `max-height: 900px`，日历 + 面板可滚动
- **API 层补充**
  - `api/todo.ts` 补充 `toggleTodoDateStatusAPI`（完成/取消完成每日任务）
  - `api/calendar.ts` 补充 `saveDailyNoteAPI`（日记保存）

---

## 待完成

- [ ] Spring AI 集成 + 意图识别
- [ ] 前端浏览器麦克风录音（STT）
- [ ] 前端 TTS 朗读播放
- [ ] 前后端联调
