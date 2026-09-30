# 会话状态与 Agent 流转设计

## 1. 核心模型

会话维护一个主流程状态，Route Agent 将本轮输入解析为一个用户信号。
程序只通过一张转换表，用“ConversationStage + UserSignal”决定目标 Agent 和成功后的阶段。

示例：

```text
新规划 → Planner → PLAN
临时查询明天安排 → Chat 查询 → PLAN（保留当前规划）
修改每天学习时长 → Planner → PLAN
生成示意图 → Image → IMAGE（保留原 draftId）
修改图片颜色 → Image → IMAGE
同步到日历 → Executor → CHAT
```

目标节点只有 Chat、Planner、Executor、Image。准备确认、取消、更新内容等属于各处理器的内部行为，
不再分别创建 ChatNode，也不保留 READY / pending 两套执行入口。

## 2. ConversationStage

| 状态 | 含义 | 会话保留的数据 |
|---|---|---|
| CHAT | 没有活动任务 | 不保留待确认内容 |
| PLAN | 正在补充规划需求或讨论草稿 | 需求、可选 draftId、规划正文 |
| EXECUTE | 有具体写操作，等待确认或修改 | 已向用户展示的待执行内容 |
| IMAGE | 围绕规划图片继续交互 | 原规划需求、draftId、规划正文、累计图片要求 |

PLAN 中是否已有草稿由 draftId 表达。阶段决定主流程，任务字段保存产物；
不再为“规划澄清”和“已有草稿”建立两套顶层状态。
临时闲聊或查询只借用 Chat Agent，主流程状态保持不变。

EXECUTE 表示等待用户处理的会话流程，不表示写工具正在运行。
processing 是独立的请求占用标记，version 是并发版本号，均不属于 ConversationStage。

## 3. UserSignal

| 信号 | 含义 | 示例 |
|---|---|---|
| NEW_CHAT | 独立闲聊或普通问答 | “谢谢”“介绍一下番茄钟” |
| NEW_QUERY | 新的只读日程查询 | “另外查一下明天安排” |
| NEW_PLAN | 开始另一项规划，或从图片交互切回规划内容修改 | “重新做一个复习计划” |
| NEW_EXECUTE | 新的日历写操作，单日和跨日统一处理 | “删除明天的英语待办” |
| CONFIRM | 确认当前流程询问的事项 | “确认”“好的” |
| REJECT | 取消当前活动任务 | “取消这项任务” |
| MODIFY | 补充或修改当前流程的内容 | PLAN 中“每天两小时”；IMAGE 中“换成蓝色” |
| UNKNOWN | 意图或指代不清 | “那个再弄一下” |
| SYNC_PLAN | 明确同步当前规划草稿 | “同步到日历” |
| GENERATE_PLAN_IMAGE | 明确为当前规划生成图片 | “生成规划示意图” |

原 NEW_REQUEST 拆成四种新请求，才能仅凭阶段与信号分派到正确 Agent。
模型不返回 Agent、下一阶段或可执行节点。旧 READY_* 输出与无法解析的 JSON 统一回退为 UNKNOWN。

识别时先判断用户是否在回应当前任务，再判断是否开启新任务：
- PLAN 中“每天改成两小时”是 MODIFY，不是 NEW_PLAN。
- IMAGE 中修改颜色、布局、字体是 MODIFY；改变规划目标或时间安排是 NEW_PLAN，并保留相关规划上下文。
- 含糊肯定只能是 CONFIRM，不能推断为 SYNC_PLAN 或 GENERATE_PLAN_IMAGE。
- 临时查询之后的“好的”若只是回应查询结果，应为 NEW_CHAT，不能授权更早的写操作。
- “不要早起，改到晚上”是 MODIFY，不是 REJECT。

## 4. 唯一流转表

### 4.1 通用规则

| 当前状态 | 信号 | 目标 Agent | 成功后阶段 | 任务处理 |
|---|---|---|---|---|
| 任意 | NEW_CHAT | Chat | 保持 | 保留当前任务 |
| 任意 | NEW_QUERY | Chat | 保持 | 保留当前任务与草稿 |
| 任意 | NEW_PLAN | Planner | PLAN | 成功后以新需求或草稿替换当前活动任务 |
| 任意 | NEW_EXECUTE | Executor | EXECUTE | 展示新操作并等待确认，替换旧待处理内容 |
| 任意 | REJECT | Chat | CHAT | 清空活动任务引用，不写日历 |
| 任意 | UNKNOWN | Chat | 保持 | 结合当前阶段提示补充 |

### 4.2 当前任务的交互规则

单元格为“目标 Agent → 成功后阶段”。

| 当前状态 | CONFIRM | MODIFY | SYNC_PLAN | GENERATE_PLAN_IMAGE |
|---|---|---|---|---|
| CHAT | Chat → CHAT | Chat → CHAT | Chat → CHAT | Chat → CHAT |
| PLAN | Planner → PLAN | Planner → PLAN | Executor → CHAT | Image → IMAGE |
| EXECUTE | Executor → CHAT | Executor → EXECUTE | Chat → EXECUTE | Chat → EXECUTE |
| IMAGE | Chat → IMAGE | Image → IMAGE | Executor → CHAT | Image → IMAGE |

CHAT 中没有待处理产物时，Chat 提示先描述任务或生成规划。
EXECUTE 中请求同步规划或生图时，Chat 提示先处理当前待执行内容。

表中的下一阶段是正常成功路径。缺少草稿、模型继续追问等未完成结果保持原阶段及产物；
外部调用异常同样不提交下一阶段。Agent 处理器返回结果和产物，不自行写入会话阶段。

## 5. 四个 Agent 的处理职责

### Chat

NEW_CHAT 调用对话模型，NEW_QUERY 调用现有只读工具。
取消、上下文不足和 UNKNOWN 使用当前流程对应的确定性回复，避免为固定提示额外调用模型。
REJECT 只取消活动任务，不删除历史草稿。

### Planner

NEW_PLAN 继续使用 PlanClarificationPolicy 判断是否有必要补充信息：
- 需要补充：保存需求，进入 PLAN，draftId 为空。
- 可以生成：生成并保存结构化草稿，进入 PLAN，保存 draftId 和正文。

PLAN + CONFIRM：
- 没有草稿时继续生成规划。
- 已有草稿时询问用户明确选择同步、生图或修改，不自动同步。

PLAN + MODIFY 合并用户反馈并生成新的草稿。新草稿成功绑定会话后，旧草稿不再是活动任务。
生成或会话提交失败时保留旧草稿的可用性；不在生成新草稿前提前作废旧草稿。

### Executor

所有 NEW_EXECUTE，包括明确的单日操作，都只准备并展示待执行内容，进入 EXECUTE。
EXECUTE + MODIFY 更新已展示的内容，继续等待确认。
EXECUTE + CONFIRM 使用保存的待执行内容，不能用 Route 输出的另一项 task 替换已确认任务。

模型只追问或只查询而没有成功调用写工具时，保留 EXECUTE。
成功调用写工具并返回结果后，清空活动任务，回到 CHAT。

SYNC_PLAN 必须按 userId + sessionId + 当前 draftId 查找草稿，再同步。
不允许通过“最新草稿”兜底，避免同步到另一项任务。同步成功回到 CHAT。

确认执行使用 McpToolRegistry.executeOnce：不自动重试，工具异常向上抛出。
执行计数只在写工具成功返回后增加，不能凭模型说“已完成”就清空任务。

### Image

只使用当前显式关联的草稿生成图片。成功后进入 IMAGE，同时保留原规划 draftId 和正文。
IMAGE + MODIFY 累计图片要求并重新生成；图片修改不覆盖规划内容。
当前使用文生图接口按原规划和累计要求重新生成，并非对原图片像素进行编辑。
生图失败保留原阶段、原规划和既有图片要求，可继续修改或重试。

## 6. 请求流程与并发

```text
建立用户、会话上下文
→ getOrCreate 会话记录
→ 按 userId + sessionId + version 原子认领 processing
→ 读取上一轮助手回复
→ Route Agent 输出 UserSignal
→ ChatTransitionTable 返回 Agent 和成功后阶段
→ ConversationAgentDispatcher 调用对应 Agent 处理器
→ 成功后按同一认领版本提交阶段、任务产物，释放 processing
→ 外层保存助手回复并输出 SSE
```

- 唯一键 userId + sessionId 防止首次并发创建重复记录。
- 认领时检查版本和 processing，版本在认领、完成或释放时递增。
- 同一会话包含路由、只读查询在内的请求均串行；冲突请求返回 PROCESSING 提示。
- 新规划或新操作只有成功提交后才替换原任务；替换会清空旧待执行确认。
- 已进入写调用后发生异常，保留 processing，不自动重新执行。需要核实业务结果后人工恢复。
- 写调用前或生图、规划等失败可释放占用，保持原任务。
- 不再按 30 分钟自动删除会话状态；尤其不能因超时清除不确定写入的占用。
- version 防止跨请求重复确认和状态覆盖；它不是工具级幂等键。进程崩溃后仍需核实业务结果。
- 对话记录仍由现有 ChatFacade 保存；本次未把模型、工具、会话状态及聊天记录包进一个数据库事务。
  若助手记录保存失败，会话状态可能已经提交，需要通过状态记录和日志核查。

## 7. 类型与文件职责

| 文件 | 职责 |
|---|---|
| ConversationStage | 四种会话主流程 |
| UserSignal | 十种输入意图 |
| AgentType | 四个 Agent 目标 |
| ChatTransitionTable | 唯一 Agent 选路和成功阶段表 |
| ConversationTurn | 本轮请求上下文及写调用开始标记 |
| PendingTask / AgentTurnResult | 类型化任务产物和 Agent 处理结果 |
| ConversationAgentDispatcher | 四类 Agent 内部处理，不另行决定路由或写阶段 |
| AgentFlowStateService | 版本认领、阶段和产物提交 |
| ChatServiceImpl | 读取状态、调用 Route、查表、分派和提交的统一入口 |

删除旧 ChatNode、ExistingFlowStateProcessor，以及 READY_FOR_INPUT / AWAITING_* 会话枚举。
不再保留“新请求执行 switch”和“待处理流程执行 switch”两套入口。

## 8. 数据库与对外协议

yl_agent_flow_state.stage 直接存 CHAT / PLAN / EXECUTE / IMAGE。
新增：
- processing：请求占用或结果待核实标记。
- version：会话并发版本。
- image_instruction：累计图片修改要求。

current_agent、next_agent 保留用于现有返回字段兼容；不参与路由判断。
currentAgent 表示本轮选中的 Agent，nextAgent 表示主流程后续归属；CHAT 的 nextAgent 为 NONE。
flowStage 输出四种新的阶段名。dispatchType 保留 CHAT、QUERY、PLAN、EXECUTE_CONFIRM、REFINE、
PLAN_IMAGE、EXECUTE、CANCEL、PROCESSING、ERROR 等响应标签，它们不是选路节点。

已有数据库升级：先停止旧应用写入，再执行一次
sql/migrations/20260930_agent_conversation_stage.sql，完成后启动新版本。
新建数据库使用 sql/tables.sql；不要在已有数据库直接执行其中的 DROP TABLE。

迁移将 WAIT_CONFIRM 根据旧 next_agent 转成 PLAN 或 EXECUTE，WAIT_FEEDBACK 转成 PLAN，
旧 PROCESSING 记录保留 processing=1。未知旧阶段不自动回退 CHAT，须人工核对。
迁移脚本和 MySQL 并发行为需要部署环境验证；本地单测使用模型与数据库替身。

## 9. 重点验收场景

1. PLAN 中临时查询后仍能修改原规划。
2. PLAN 中“好的”不触发同步或生图。
3. 生成图片、修改图片后仍能同步同一 draftId。
4. 明确的单日写操作也必须先展示确认。
5. 修改待执行内容后，确认只执行修改后的内容。
6. 取消或新任务替换后，旧确认不能再次执行。
7. 两条并发确认只有一条进入 Executor。
8. 生图失败保留原规划；写结果不明确时阻止重复提交。
9. 同名 sessionId 在不同用户之间隔离。
10. 旧枚举、无效模型输出回退 UNKNOWN，不授权写操作。
