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

## 待完成

- [ ] 前端日历对接 `month-count` + `day` 接口
- [ ] 前端待办清单对接 CRUD 接口
- [ ] Spring AI 集成 + 意图识别
- [ ] 前端浏览器麦克风录音（STT）
- [ ] 前端 TTS 朗读播放
- [ ] 前后端联调
