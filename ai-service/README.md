# Calendar AI Service（最小对话版）

当前提供 `GET /health`、`GET /auth/me` 和非流式 `POST /chat`。`/chat` 只做普通对话和会话落库；日历工具调用与 SSE 将在后续接入。

代码按职责分层：`app/api/routers` 处理 HTTP，`app/core` 管理配置、中间件、异常和响应，`app/cache` 校验 Redis token，`app/service` 编排对话业务，`app/repository` 与 `app/models` 负责 AI 对话表的数据访问，`app/helper` 封装模型与 Java 客户端，`app/db` 提供连接，`app/schemas` 定义请求与响应，`app/utils` 放无业务状态的工具函数。接口入口统一在 `app/api/endpoints.py` 注册。

## 本地运行（PowerShell）

```powershell
cd E:\develop\projects\workProject\calendar-agent\ai-service
py -3.13 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e .
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8001
```

配置分两层加载：先读取 `.env.example` 中的完整公共配置，再读取被 Git 忽略的 `.env`，由 `.env` 中的同名字段覆盖敏感占位值。

`.env` 只需要保存真实敏感值，例如：

```dotenv
REDIS_URL=redis://:真实密码@127.0.0.1:6379/15
DATABASE_URL=mysql+aiomysql://真实用户:真实密码@127.0.0.1:3306/yl_database?charset=utf8mb4
DEEPSEEK_API_KEY=真实 DeepSeek 密钥
```

Redis 必须连接到 Java Sa-Token 使用的同一数据库（当前开发配置是 DB 15）；MySQL 必须已有 `yl_ai_dialogue` 表。连接 URL 中用户名或密码的特殊字符需要 URL 编码。

聊天默认使用 Spring Boot 中 `chat` agent 的 DeepSeek 配置。切换到阿里云时，在 `.env` 写入 `CHAT_PROVIDER=aliyun` 和 `ALIYUN_API_KEY`。阿里云 OpenAI-compatible 接口使用 API Key，预留的 `ALIYUN_API_ID`、`ALIYUN_API_SECRET` 当前不参与模型请求。

请求示例：

```powershell
$body = @{ sessionId = "demo-1"; message = "你好" } | ConvertTo-Json
Invoke-RestMethod -Uri http://127.0.0.1:8001/chat -Method Post -ContentType application/json -Headers @{ 'yvli-token' = '<登录返回的 token>' } -Body $body
```

运行测试：

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

也可以用 `tests/test_main.http` 手动测试接口。需要清理源码中的 Python 缓存时运行 `python -m app.clean_pycache`。

`/chat` 的用户 ID 只来自 Redis token 映射；用户资料由 `/auth/me` 转发 token 向 Java 获取。Python 依赖 Sa-Token 1.44.0 的 `client` token 键格式，升级 Java 鉴权实现时要同步验证登录、退出和过期行为。
