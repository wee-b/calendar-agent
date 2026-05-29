# 语音日历工具 (Voice Calendar)

基于语音交互的智能日历管理工具，支持通过语音添加/删除/查看待办事件。

---

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | Vue 3 + TypeScript |
| 后端 | Spring Boot 3.0 + Sa-Token + MyBatis-Plus |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis |
| AI | Spring AI |
| 文档 | Knife4j (OpenAPI 3) |

---

## 后端

### 项目结构

```
back/
├── src/main/java/com/qiniu/back/
│   ├── annotation/          # 自定义注解（@NoNeedLogin, @CheckPhone）
│   ├── config/              # 配置类（Sa-Token, MVC, 连接池）
│   ├── domain/              # 领域模型
│   │   ├── chat/            #   AI 对话实体 + DTO + VO
│   │   ├── event/           #   待办实体 + DTO + VO
│   │   └── user/            #   用户实体 + DTO + VO
│   ├── exception/           # 业务异常
│   ├── handler/             # 登录拦截器 + 全局异常处理
│   ├── module/              # 业务模块（Controller + Mapper）
│   ├── util/                # 工具类（Token, 响应, 上下文）
│   └── validator/           # 校验器
├── src/main/resources/
│   ├── application.yml      # 主配置
│   ├── application-dev.yml  # 开发环境配置
│   └── spy.properties       # SQL 监控
└── sql/
    ├── tables.sql           # 建表 DDL
    └── insert_data.sql      # 测试数据
```

### 数据库表

| 表名 | 说明 |
|------|------|
| `yl_user` | 用户表 |
| `yl_todo` | 待办表 |
| `yl_ai_dialogue` | AI 对话记录表 |

### 核心流程

```
用户语音 → 前端 STT → /api/chat → Spring AI 解析意图
    ├─ add_todo    → 创建待办
    ├─ delete_todo → 删除待办
    ├─ query_todo  → 查询待办
    ├─ update_todo → 修改待办
    └─ chat        → 闲聊
→ AI 文本响应 → TTS 生成音频 → 前端播放
```

### 快速启动

```bash
# 1. 创建数据库
mysql -u root -p < sql/tables.sql

# 2. 修改 application-dev.yml 中的数据库/Redis 连接信息

# 3. 启动后端
cd back
mvn spring-boot:run
```

### API 认证

使用 Sa-Token 框架，请求头携带 `yvli-token` 进行认证。登录接口标记 `@NoNeedLogin` 放行，其余接口由 `LoginInterceptor` 统一校验。

### 接口文档

启动后访问 `http://localhost:8080/doc.html` 查看 Knife4j 接口文档。

---

## 前端

> 待开发
