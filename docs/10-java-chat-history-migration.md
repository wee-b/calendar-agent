# 10 Java 对话逻辑配套修改清单

日期：2026-10-02。

**2026-10-03 更新**：Java 旧对话、Agent、状态机、草稿、摘要和记忆模块已整体删除；工具模块已迁到 `module/mcp`，保留 `/mcp` 与日历业务服务。下面涉及 Java 类适配的内容为历史迁移记录，不再作为当前待办。对话入口统一由 Python 提供；主图路由前的上下文压缩与偏好模型抽取已迁入 Python，数据库表与历史数据继续保留。升级需执行 `20261003_context_message_count.sql`，详见 AI 服务 README。

本文记录 Python 历史对话迁移后的数据约定，以及后续在其他分支修改 Java 时需要处理的逻辑。当前分支实现 Python 和 SQL；Java 删除已经迁移的历史查询及会话管理链路，保留的 Java 消息写入、摘要逻辑尚未适配新表。前端聊天请求已切换到 Python。

## 1. 当前分支已完成

- 新增 `yl_ai_session`，保存会话标题、有效消息数、最近消息 ID 与时间。
- `yl_ai_dialogue` 一行一条消息，以 `role + content` 存储正文；移除 `user_text`、`ai_result`、`intent`、`execute_result`。
- Python `GET /chat/history` 使用消息 ID 游标向前分页，默认 20 条消息，上限 100，user 和 assistant 合计计数。
- Python `GET /chat/sessions` 直接读取会话表，避免加载全部消息再分组。
- Python `GET /chat/latest?sessionId=xxx` 读取当前会话的 RouteAgent 上下文：最后一条有效助手回复及之后全部用户消息。它不承担恢复最近会话的用途。
- Python 新增 `POST /chat/new-session`、`DELETE /chat/session`、`DELETE /chat/last-round`。
- Python `ChatRepository.append_messages()` 支持任意数量和顺序的 user/assistant 消息；消息插入与会话统计在同一个事务内提交。
- 现有 Python `save_round()` 是上述通用写入方法的调用入口，普通及流式聊天仍在完整回答产生后提交用户消息和助手消息。断连、模型失败不保存不完整回答，也不提前保存本次用户消息。
- 提供新库初始化结构、样例数据与旧库增量迁移脚本。

本次删除的 Java 代码：

| 文件（相对 `back/src/main/java/com/qiniu/back/module/assistant/`） | 删除内容 |
|---|---|
| `controller/ChatController.java` | `GET /chat/history`、`GET /chat/sessions`、`GET /chat/latest`，以及新建、删除、撤回接口 |
| `service/ChatFacade.java` | 查询、新建、删除、撤回方法声明 |
| `service/impl/ChatFacadeImpl.java` | 查询、新建、删除、撤回方法实现 |
| `service/ChatDialogueService.java` | `getHistory`、`listSessions`、`getLatestSession`、`deleteSession`、`deleteLastRound` |
| `domain/vo/ChatHistoryItemVO.java` | 已无调用方的旧消息列表 VO |
| `domain/vo/ChatSessionVO.java` | 已无调用方的旧会话列表 VO |

Python 的基础对话尚未覆盖 Java 的完整 Agent 流程、摘要与长期记忆能力，因此当前没有整体删除 Java `ChatFacade` 或 `ChatDialogueService`。

## 2. 新的数据约定

### 2.1 会话表

`yl_ai_session` 的唯一键为 `(user_id, session_id)`。所有读写必须带登录用户 ID，不能只凭 `session_id` 查询或更新。

| 字段 | 含义 |
|---|---|
| `title` | 第一条有效用户消息前 30 个字符，超过时追加 `...`；尚无用户消息为 NULL，展示为“新对话” |
| `message_count` | 未删除消息条数，不是轮数；连续 user、连续 assistant 都分别计数 |
| `last_message_id` | 未删除消息中最大的 `dialogue_id` |
| `last_message_time` | `last_message_id` 对应消息的创建时间 |
| `create_time` | 会话创建时间；迁移时取该会话最早的消息创建时间 |
| `update_time` | 会话元数据更新时间，不用于替代最近消息时间 |
| `deleted_flag` | 整个会话的删除标记，已删除会话不能通过追加消息自动复活 |

索引：`uk_user_session(user_id, session_id)`；`idx_user_recent(user_id, deleted_flag, last_message_time, id)`。

Python 在首次成功保存消息时创建会话，空 UUID 不自动出现在历史列表。列表过滤 `deleted_flag = 0 AND message_count > 0`，按 `last_message_time DESC, id DESC` 排序。

流程阶段、待确认任务、乐观锁版本等仍保存在 `yl_agent_flow_state`；会话表负责历史列表元数据。

### 2.2 消息表

`AiDialogue` 的最终字段：`dialogueId`、`userId`、`sessionId`、`role`、`content`、`aiAudioUrl`、`responseTimeMs`、`deletedFlag`、`createTime`、`updateTime`。

- 删除 Java 实体里的 `userText`、`aiResult`、`intent`、`executeResult` 字段。
- 新增 `String content`；消息保存时必须明确提供 `role`，数据库不再默认填 `user`。
- `content` 非空；旧 NULL 正文在迁移时转为空字符串。
- `responseTimeMs`、`aiAudioUrl` 可为空，保留各自原有用途。
- 查询有效消息必须同时检查所属会话和消息的 `deleted_flag = 0`。
- 新消息按 ID 确定顺序，历史查询不能再按时间单字段排序。
- 新索引：`idx_history(user_id, session_id, deleted_flag, dialogue_id)`。

`ChatResponseVO.aiResult` 是聊天 HTTP 响应字段，与删除的数据库列不是同一概念。不要仅因为移除数据库 `ai_result` 就删除对外响应的 `aiResult`。

## 3. Java 分支需要修改的逻辑

### 3.1 实体与 Mapper

- 修改 `domain/model/AiDialogue.java` 为上述新字段结构。
- 新增 `domain/model/AiSession.java` 和 `mapper/AiSessionMapper.java`。
- 在消息保存和会话统计更新中共用一个事务、同一条数据库连接。
- 不建议依赖隐式逻辑删除配置：现有 `AiDialogue.deletedFlag` 没有 `@TableLogic`，查询和删除规则应明确实现。

### 3.2 消息保存：ChatDialogueService

当前仍有 `saveUserDialogue`、`saveAssistantDialogue` 和私有 `saveDialogue`。它们还在操作旧字段，需要改成统一保存 `role + content`。

建议保留两个公开方法，内部调用通用 `appendMessages(userId, sessionId, messages)`，方便现有调用方迁移：

```text
开启事务
  INSERT 会话，唯一键冲突时做无操作更新
  SELECT 会话 WHERE user_id=? AND session_id=? FOR UPDATE
  如果会话已删除：拒绝写入
  插入 N 条 role + content 消息，取得 ID
  如果 title 为 NULL，尝试使用本批第一条用户消息生成标题
  message_count += N
  last_message_id = 本批最后一条消息 ID
  last_message_time = 该消息创建时间
提交事务
提交成功后才触发异步摘要
```

锁顺序必须与 Python 相同：先锁会话，再分配和写入消息 ID。这样同一会话的并发写入不会丢计数、覆盖最近消息或在游标分页中出现后提交的旧 ID。不要先插消息再锁会话。

`saveAssistantDialogue()` 目前直接调用 `refreshSummaryAsync()`。加入事务后应通过事务提交事件或 `afterCommit` 触发，避免摘要线程查询到尚未提交的数据。

`service/impl/ChatFacadeImpl.java` 的普通与 SSE 流程先保存用户消息，再保存助手消息，这种两次保存方式仍可使用，但每一次都要同步更新会话表。Python 当前是完整回答后一次提交两条；本次未强制统一两端失败时是否保留用户消息的语义。

### 3.3 最新助手回复

`ChatDialogueService.findLatestAssistantReply()` 仍被 Agent 流程调用，需要保留并适配：

- 过滤 `role = assistant`、`content IS NOT NULL AND content <> ''`。
- 按 `dialogue_id DESC LIMIT 1`；保留 `beforeDialogueId` 的限制。
- 返回 `getContent()`。
- 增加消息与会话的有效状态检查。

### 3.4 上下文与摘要：ChatContextSummaryService

重点修改 `buildContext` 中的消息拼接、`loadRecentDialogues`、`summarize` 和摘要触发相关辅助方法：

- 按 `role` 决定“用户/助手”标签，正文统一读取 `content`。
- 不再检查两个正文列并分别拼接，避免一条消息被计入两次。
- 读取近期消息、待摘要消息时增加有效消息及有效会话过滤。
- 当前 `recentRoundLimit * 2 + 4`、`countRounds`、`indexAfterRounds`、`keepLastRounds` 含有轮数/配对假设。改用明确的消息条数或 token 预算，连续 user/assistant 均不得漏掉。
- 模型上下文长度与页面默认 20 条是独立参数，不应把页面 limit 传进摘要策略。
- 继续保留原始消息，仅摘要压缩模型上下文。
- 删除消息后要使覆盖被删消息的摘要失效或重建；不能让摘要把已删除内容重新带入模型上下文。

### 3.5 已迁移的会话管理

当前分支已删除 Java 的新建、删除及撤回入口，由 Python 提供；其他分支若仍需保留这些能力，应对齐以下实现：

- 新建会话只生成 UUID，首次写入消息才创建会话表记录。
- 删除会话先锁会话行，将会话和消息软删除，计数归零，最近消息信息清空。首轮尚未保存时也建立删除标记，阻止迟到的回复复活会话。
- 撤回按助手回复边界处理：最后一条是用户消息时，撤回最近助手回复后的全部用户输入；最后一条是助手消息时，撤回该回复及它前面的连续用户输入。连续助手回复时，一次撤回最后一条助手回复。它不依赖固定两条一轮。
- 撤回后重新计算有效消息数与最近消息；会话标题保留，撤回到空会话时历史列表隐藏该会话，但允许继续使用同一个 sessionId。
- 删除和撤回在同一个数据库事务内清理该会话的 `yl_chat_context_summary` 和 `yl_agent_flow_state`。若流程 `processing=1`，返回 409 并回滚，等待执行流程结束后再操作。
- 这些操作只影响会话记录与流程上下文，不撤销已执行的日程等外部业务操作。

未来若要求按一个跨多次助手回复的执行请求整体撤回，需引入请求分组标识；当前边界定义并不等同于任意 Agent 执行任务的边界。

### 3.6 RouteAgent 上下文

Java `RouteAgent.route(message, previousReply, state)` 目前只拿到上一条助手回复与当前单条输入。后续需把该助手回复之后的连续用户消息一起传入，以理解分多句补充的意图。

Python 本次只完成上下文读取，没有接入意图识别模型节点或改变现有 ChatGraph 的分派行为：

```python
# 当前输入尚未入库：追加到持久化上下文末尾，不会把用户的重复文字自动去重。
context = await ChatService().get_route_context(
    user_id, session_id, current_message=request.message
)

# 当前输入已经入库：以其 ID 固定读取上界，避免更晚的输入干扰当前判断。
context = await ChatService().get_route_context(
    user_id, session_id, through_id=current_dialogue_id
)
```

RouteAgent 位于同一 Python 进程时直接调用 Service，无需向本机发送 HTTP 请求。两种调用方式择一使用，已入库消息不要再传 `current_message`。

读取使用一条 SQL 获取边界与消息，避免先查助手回复、再查消息时出现不同快照。只读当前用户与当前会话的有效消息。没有助手回复时返回全部已接收用户输入；没有消息时为空上下文。历史页的 20 条限制不适用于这里。

## 4. Python HTTP 协议与前端接入

鉴权沿用 `yvli-token`，用户 ID 取自服务端 token 校验结果，不能由查询参数指定。

### 历史消息

```http
GET /chat/history?sessionId=xxx&limit=20
GET /chat/history?sessionId=xxx&beforeId=81&limit=20
```

`limit` 默认 20，允许 1–100；`beforeId` 为正整数。查询 `dialogue_id < beforeId`，不包含游标消息本身，读取 `limit + 1` 条判断后续数据。返回项在页内按 ID 升序排列。

```json
{
  "code": 0,
  "ok": true,
  "msg": "操作成功",
  "data": {
    "items": [
      {
        "dialogueId": 81,
        "role": "assistant",
        "content": "示例消息",
        "createTime": "2026-10-02T12:00:00",
        "responseTimeMs": 120
      }
    ],
    "hasMore": true,
    "nextBeforeId": 81
  }
}
```

上述是 `limit=1` 时的分页结构示例。有更早数据时 `nextBeforeId` 是本页最小消息 ID；否则为 NULL。未知会话、其他用户会话、已删除会话均返回空分页。没有 token 返回 401；参数无效返回 422。

### 会话列表与 RouteAgent 上下文

- `/chat/sessions` 的 `data` 仍为数组，包含 `sessionId`、`title`、`createTime`、`lastMessageTime`、`messageCount`。
- `createTime` 表示真正的会话创建时间；前端已改用 `lastMessageTime` 排序。
- `/chat/latest` 必须指定 `sessionId`，可传正整数 `throughId` 作为消息上界，不接受页面分页语义。

```http
GET /chat/latest?sessionId=xxx
GET /chat/latest?sessionId=xxx&throughId=85
```

```json
{
  "code": 0,
  "ok": true,
  "msg": "操作成功",
  "data": {
    "sessionId": "xxx",
    "previousReply": {"dialogueId": 81, "role": "assistant", "content": "你希望安排在什么时间？"},
    "userMessages": [
      {"dialogueId": 82, "role": "user", "content": "明天下午"},
      {"dialogueId": 85, "role": "user", "content": "三点到五点"}
    ]
  }
}
```

`previousReply` 在没有助手回复时为 NULL。HTTP 接口只读取已保存消息；Service 的 `current_message` 可追加尚未入库的当前输入，追加项的 `dialogueId` 为 NULL。前端聊天页不调用 `/latest`，页面翻页继续使用 `/history`。

### 新建、删除与撤回

```http
POST /chat/new-session
DELETE /chat/session?sessionId=xxx
DELETE /chat/last-round?sessionId=xxx
```

新建返回 `data: {sessionId}`；删除及撤回成功返回 `data: null`。全部沿用 token 鉴权。删除幂等；空会话撤回成功但不修改消息。

### 前端实现与部署

`front/vite.config.ts` 已把 `/chat`、`/rag` 代理到 Python 8001，其余业务保持 Java 8080。`front/src/api/chat.ts` 使用独立的 `VITE_AI_API_BASE_URL`；开发环境为空时走 Vite 代理，不继承 Java 的 `VITE_API_BASE_URL`。生产环境设置 Python 对外地址或由同源网关转发 `/chat`、`/rag`。

两个聊天面板均接收 `data.items` 分页结果，提供“加载更早的消息”，使用 `nextBeforeId` 并保留滚动位置。撤回后刷新消息和会话统计。侧栏按最近消息时间排序。

SSE 已适配 Python JSON 事件：仅 `assistant_delta.delta` 作为正文，状态及工具事件用作进度，`result` 标记回答已保存，收到 `done` 才视为成功。`error` 或提前 EOF 按失败处理，`ping` 忽略。

## 5. 数据迁移与部署顺序

脚本：`sql/migrations/20261002_chat_session_content.sql`。适用 MySQL 8.0 的旧表，一次性执行；新数据库使用 `sql/tables.sql`、`sql/insert_data.sql`。

1. 准备数据库备份，停止旧 Java/Python 的消息读写入口与异步摘要任务。
2. 检查异常角色、空会话 ID、正文出现在错误角色列等脏数据。脚本遇到这些情况会报错，避免静默丢弃正文。
3. 脚本保存完整旧表副本 `yl_ai_dialogue_backup_20261002`，回填 content 和会话元数据，再删除旧字段并建立索引。
4. NULL 正文转为空字符串；所有消息 ID、创建时间、更新时间保留。会话计数只包含未删除消息；全部消息已删除的会话被标记为已删除。
5. 核验消息行数、有效消息总数、正文、标题、最近消息；启动使用新表结构的 Python 服务。
6. 删除与撤回还依赖已有的 `yl_agent_flow_state` 和 `yl_chat_context_summary` 表，新库初始化需包含它们。Java 若继续读写此库，必须先完成第 3 节适配，或者停用对应旧入口和异步任务。删除已迁移接口不足以让仍保留的旧 Java 聊天流程兼容新表。
7. 配置生产环境 Python 请求地址或网关分流，确认鉴权、SSE、分页和会话管理联调后开放入口。

MySQL DDL 不能整体事务回滚；不要用 `mysql --force` 跳过脚本错误。中途失败时保留备份，检查已执行的 DDL 和同名存储过程，不要直接重跑。若需回退且尚无新写入，可停止服务后使用旧表副本恢复旧结构，并恢复旧应用；已有新写入时需先合并新消息，不能直接用旧快照覆盖。

备份中的 `intent`、`execute_result` 仅用于回滚核验，运行中的新消息表不再保留这两个字段。

## 6. 验证与后续验收

Python 测试：`ai-service/tests/test_chat_history.py`，使用 `CHAT_TEST_MYSQL_URL` 指向隔离 MySQL。测试会创建 `chat_history_test_<随机ID>` 数据库并清理，不读取应用 `DATABASE_URL`，不清空 URL 中指定的数据库。

覆盖默认 20 条、可配置条数、空页与最后一页、翻页期间新增消息、用户与会话隔离、软删除、连续同角色消息、助手历史进入模型上下文、并发首次写入、统计一致性、失败回滚及旧表迁移。

验证使用独立的 MySQL 8.0.45 容器，包含新库样例数据初始化、旧库迁移、RouteAgent 上下文边界和会话管理事务。前端通过 `npm run build` 和 `npm run test:chat` 验证；Java 通过 `mvn -q -DskipTests test` 检查删除接口后的主代码与测试代码编译。业务数据库未执行迁移。

Java 分支验收：

- 保存连续 user/assistant 消息后，正文、计数、标题与最近消息正确。
- Java 与 Python 对同一会话并发追加消息，计数不丢失，锁顺序一致。
- 摘要在事务提交后运行，不引用已删除消息，裁剪不依赖两条一轮。
- 删除会话时阻止迟到的模型回复重新写入。
- 撤回遵守本文的助手回复边界；若需跨多次回复撤回一个任务，另行引入请求分组。
- 运行 `ChatContextSummaryServiceTest`、`ChatServiceImplTest` 及新增的持久化集成测试；更新依赖旧实体字段的测试替身。
