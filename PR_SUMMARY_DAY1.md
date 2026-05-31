## 概述

完成语音日历工具 Day 1 全栈开发：数据库设计（5 张表）、后端全部 API（13 个接口）、前端页面搭建（5 个页面/组件），共 **73 个文件**、**4600+ 行新增代码**。

---

## 数据库设计

| 表名 | 说明 | 关键设计 |
|------|------|----------|
| `yl_user` | 用户表 | phone 唯一索引，BCrypt 密码加密 |
| `yl_todo` | 待办表（目标） | `startDate` + `endDate` + `weekDays`（逗号分隔，1=周一 7=周日），`color` 用于日历高亮，跨度上限 180 天 |
| `yl_todo_date` | 每日任务关联表 | todo 与日期多对多，`dayContent` 存储当日具体任务（不同于待办标题），`status` 标记完成状态 |
| `yl_daily_note` | 每日日记表 | `uk_user_date` 唯一约束，每用户每天一条 |
| `yl_ai_dialogue` | AI 对话记录表 | `sessionId` 分组多轮对话，`role` 区分 user/assistant，`aiAudioUrl` 预留 TTS |

**核心设计思想**：一个"待办"是一个跨多天的目标。创建时只需指定起止日期和每周执行日，后端自动遍历计算所有有效日期并批量写入 `yl_todo_date`。每项日期记录可以独立设置当日任务内容（dayContent）、独立钩选完成状态。

---

## 后端

### 技术栈

- Spring Boot 3.5.4 + MyBatis-Plus 3.5.12
- Sa-Token 1.44.0（认证 + Redis 持久化）
- MySQL 8.0 + Druid 1.2.24 连接池
- Knife4j 4.6.0 接口文档
- BCrypt 密码加密、P6Spy SQL 监控

### 项目结构

```
back/src/main/java/com/qiniu/back/
├── annotation/
│   ├── NoNeedLogin.java          # 免登录注解
│   └── CheckPhone.java           # 手机号校验注解
├── config/
│   ├── ClientTokenConfig.java    # Sa-Token 客户端 Token 配置（继承 StpLogic）
│   ├── MvcConfigure.java         # 拦截器注册 + CORS
│   └── StartupListener.java      # 启动打印文档地址
├── domain/
│   ├── ErrorCode.java            # 统一错误码枚举
│   ├── ResponseDTO.java          # 统一响应体
│   ├── chat/                     # AI 对话实体 + DTO + VO
│   ├── todo/                     # 待办 + 每日任务 + 日记实体 + DTO + VO
│   └── user/                     # 用户实体 + DTO + VO
├── enumeration/
│   ├── DeletedFlagEnum.java
│   └── GenderEnum.java
├── exception/
│   └── BusinessException.java    # 业务异常
├── handler/
│   ├── LoginInterceptor.java     # Token 校验 + 用户查询 + LinUserContext 注入 + 权限注解
│   └── GlobalExceptionHandler.java # Sa-Token/SaTokenException/BusinessException/参数校验
├── module/
│   ├── TestController.java       # 测试连接 / 测试登录 / 获取测试 Token
│   ├── user/                     # 用户模块 Controller + Mapper + Service
│   ├── todo/                     # 待办模块 Controller + Mapper + Service
│   └── dailyNote/                # 日记/日历模块 Controller + Mapper + Service
├── util/
│   ├── SaTokenUtil.java          # Token 生成/校验/解析/注销（静态方法 + clientTokenLogic）
│   ├── LoginUserContext.java     # ThreadLocal 用户上下文
│   └── ResponseUtil.java         # HttpServletResponse 写 JSON
└── validator/
    └── PhoneValidator.java       # 手机号正则校验
```

### API 接口（13 个）

#### 用户模块

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/user/register` | 注册（手机号唯一 + BCrypt 加密 + 自增 userCode） | 否 |
| POST | `/user/login` | 登录（密码校验 + 状态检查 + Token 发放） | 否 |
| GET | `/user/info` | 获取当前用户信息 | 是 |
| PUT | `/user/info` | 修改用户信息 | 是 |
| POST | `/user/logout` | 退出登录（销毁 Token） | 是 |

#### 待办模块

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/todo` | 创建目标（自动计算日期 → 批量插入 `yl_todo_date`） | 是 |
| GET | `/todo/list` | 查询当前用户所有待办 | 是 |
| PUT | `/todo/{todoId}` | 修改待办（删旧日期 → 重新计算 → 批插） | 是 |
| DELETE | `/todo/{todoId}` | 删除待办（批量删日期 + 软删除） | 是 |
| PUT | `/todo/toggle-date` | 完成/取消完成某天任务（联动待办整体状态） | 是 |

#### 日历/日记模块

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| GET | `/calendar/month-count?year=2026&month=6` | 当月每日待办数量（日历小圆点） | 是 |
| GET | `/calendar/day?date=2026-06-01` | 某天所有待办 + 日记 | 是 |
| PUT | `/daily-note` | 保存/修改某天日记（upsert） | 是 |

### 关键实现细节

- **日期计算**：`calcDates(startDate, endDate, weekDays)` 遍历起止区间，`LocalDate.getDayOfWeek().getValue()` 匹配 1-7，生成所有执行日期
- **修改待办**：事务内先 `DELETE` 旧的 `yl_todo_date` 全部行，再重新计算并 `INSERT` 新行
- **完成联动**：勾选某天完成时检查该待办下是否还有未完成日期，若全部完成则自动将 `yl_todo.status` 设为 1；取消勾选时恢复为 0
- **Token 体系**：自定义 `ClientTokenConfig extends StpLogic`，反射注入 `SaTokenConfig`，支持单端登录、自动续签、Redis 持久化

---

## 前端

### 技术栈

- Vue 3.5 + TypeScript 6.0 + Vite 8.0 + Vue Router 4.6
- 复古纸张主题设计（统一配色：`#fdfae9`/`#eaddc4`/`#5c4b37`/`#d3c4a1`）

### 项目结构

```
front/src/
├── App.vue                      # 根组件（全局重置 + router-view）
├── main.ts                      # 入口（Vue + Router 挂载）
├── router/index.ts              # 路由（/login + /）+ 路由守卫
├── api/user.ts                  # 用户 API 封装
├── utils/
│   ├── request.ts               # axios 封装（拦截器 + token 注入）
│   └── auth.ts                  # token 存储工具
├── components/
│   ├── NavBar.vue               # 顶部导航栏（面板切换 + 退出登录）
│   └── AuthModal.vue            # 登录/注册弹窗组件
└── views/
    ├── LoginView.vue            # 登录/注册页面
    └── HomeView/
        ├── HomeView.vue         # 主页布局（三栏 + 日历核心逻辑）
        └── components/
            ├── TodoList.vue     # 左侧待办清单面板
            └── ChatPanel.vue    # 右侧语音助手面板
```

### 页面说明

- **登录/注册页** — 表单切换，已对接 `/user/login` 和 `/user/register`
- **主页** — 三栏布局可折叠：左 TodoList + 中日历 + 右 ChatPanel
- **日历** — 月视图 7×6 网格，月份 `<input type="month">` 选择器，日期点击选中与取消，行内编辑面板动画
- **待办清单** — 左侧可折叠面板，静态示例数据，预留给 `/todo/list` 接口对接
- **语音助手** — 对话气泡 + 按住说话按钮（预留 `startRecord/stopRecord`，待接入浏览器麦克风和 STT）

---

## 测试数据

```sql
-- 用户：U00001 / 13800138000
-- "学英语" 6/1-6/5 周一至周五，绿色 #4CAF50，每天不同学习内容
-- "健身计划" 6/1, 6/3, 6/5 周一三五，蓝色 #2196F3
-- 6/1 和 6/3 有日记记录
```
