# Agent 日程管理系统 — 面试题整理

基于项目实际实现的 MCP 工具封装 + Supervisor 多 Agent 编排。

---

## 一、MCP 工具封装

### Q1: 什么是 MCP？你们项目中 MCP 的设计目标是什么？

**答：** MCP（Model Context Protocol）是 Anthropic 提出的一套标准协议，定义了 LLM 与外部工具之间的交互规范，核心是两个 JSON-RPC 接口：

- `tools/list` — 告知客户端有哪些工具可用（名称、描述、参数 schema）
- `tools/call` — 客户端按名称 + 参数 JSON 调用某个工具，返回执行结果

我们项目的设计目标：**一套工具定义，多处复用**。`McpToolRegistry` 是唯一的工具注册中心，既为 `OpenAI Function Calling` 提供 `toOpenAiTools()` 格式，也通过 `McpController` 对外暴露 MCP JSON-RPC 端点，供 Claude Desktop / LangGraph 等外部客户端接入，还通过 `toLangChain4jSpecifications()` 为 LangChain4j 模型提供 `ToolSpecification`。三套接口共享同一批工具定义和执行逻辑。

**对应代码：** `McpToolRegistry.java` — `toOpenAiTools()` / `toMcpToolList()` / `toLangChain4jSpecifications()`

---

### Q2: 一个 LLM 工具定义包含哪些核心要素？你们是怎么建模的？

**答：** 一个工具定义包含三个核心要素：**元数据**（名称、描述）、**参数 Schema**（JSON Schema 格式，描述每个参数的类型和含义）、**执行逻辑**（收到参数后怎么运行）。

我们的建模：

```java
// McpToolDefinition.java — 工具定义数据模型
public class McpToolDefinition {
    private String name;                          // 工具唯一标识
    private String description;                   // 给 LLM 看的描述
    private Map<String, Object> inputSchema;      // JSON Schema 参数定义
    private Function<Map<String, Object>, String> executor; // 执行函数
}
```

`inputSchema` 用的是 OpenAI/MCP 标准的 JSON Schema 格式——`{"type":"object","properties":{...},"required":[...]}`，LLM 拿到后就知道每个参数叫什么、什么类型、什么含义。

**对应代码：** `McpToolDefinition.java`、`McpToolRegistry.java` 中各 `register*()` 方法

---

### Q3: 为什么 Tool Calling 需要一个循环？你们怎么控制轮次上限？

**答：** LLM 的一次回复可能返回"我要调用工具"，调用完工具后结果需要回传，LLM 看到工具结果后可能再次决定调用其他工具或给出最终答复。所以整个对话是一个循环：

```
用户消息 → LLM决策 → 调工具? → 执行 → 结果回传 → LLM再决策 → ... → 最终文本
```

我们最多允许 5 轮（`MAX_TOOL_ROUNDS = 5`）。前 4 轮带工具列表（`tool_choice: auto`），最后一轮不带工具（强制 LLM 输出纯文本，防止死循环）。如果某个工具需要 LLM 多轮调用（比如先查列表→确认→再删除），5 轮足够覆盖最短路径。

**对应代码：** `SubAgent.java` — `execute()` 方法中的 for 循环

---

### Q4: 你们怎么把 Map.of() 构建的 JSON Schema 迁移到 LangChain4j 的类型安全方案？

**答：** 原先用 `Map.of()` 嵌套构建 JSON Schema，如：

```java
.inputSchema(Map.of("type","object", "properties", Map.of(
    "title", Map.of("type","string","description","目标名称"),
    ...
)))
```

问题：key 名写错编译器不报错、深层嵌套可读性差、无法复用。迁移后使用 LangChain4j 的 `JsonObjectSchema` Builder：

```java
JsonObjectSchema.builder()
    .addStringProperty("title", "目标名称")
    .addIntegerProperty("todoId", "待办目标ID")
    .addProperty("weekDays", JsonArraySchema.builder()
        .items(JsonIntegerSchema.builder().build())
        .description("每周执行日 1=周一至7=周日")
        .build())
    .required("title", "startDate")
    .build()
```

同样生成 OpenAI 兼容的 JSON Schema，但类型安全、IDE 有补全、字段名写错直接编译报错。

**对应代码：** `McpToolRegistry.java` — `toLangChain4jSpecifications()` / `buildJsonSchema()`

---

### Q5: 你们的 8 个 CRUD 工具是怎么分类的？为什么这样分？

**答：** 按读写能力分为两类：

| 类型 | 工具 |
|------|------|
| **只读（3 个）** | `queryTodoList`、`queryMonthCount`、`queryDayDetail` |
| **读写（5+2 个）** | `createTodo`、`deleteTodo`、`updateTodo`、`toggleTodoDate`、`saveDailyNote`、`removeTodoDay`、`addTodoDay` |

分类原因：后续 Agent 编排时，不同子 Agent 授予不同权限——**Checker（冲突检测 Agent）只拿读工具**，防止它越权修改数据；**Executor 拿全部工具**，能执行也能自查。

**对应代码：** `SupervisorTools.java` — `QUERY_TOOLS` / `EXECUTOR_TOOLS` 两个 Set

---

## 二、Agent 编排

### Q6: 你们为什么选择 Supervisor 模式而不是单个 Agent 直接操作所有工具？

**答：** 单个 Agent + 全部工具有两类问题：

1. **职责混乱**：同一个 LLM 既要规划"怎么拆备考计划"又要在执行时做"逐条创建"，prompt 越来越长、幻觉越来越多。
2. **权限难控**：无法阻止 LLM 在"查询"阶段误调 `deleteTodo`。

Supervisor 模式把"规划"、"查询"、"执行"三个职责分给三个子 Agent，每个只拿自己需要的工具。Supervisor 的职责只剩一件事：判断意图、路由到正确的子 Agent。

**对应代码：** `AgentOrchestrator.java`、`SupervisorTools.java`

---

### Q7: 三个子 Agent 各自的能力边界是什么？为什么这样划分？

**答：**

| 子 Agent | 拥有工具 | 能力边界 |
|----------|---------|----------|
| **Planner** | 无工具 | 纯推理，只输出 JSON 计划。不给工具是为了强制它只规划不执行，防止跳过 Supervisor 直接操作数据 |
| **Checker** | 3 个查询工具 | 查重预检、回答用户查询。只有读权限，**绝不可能误删数据** |
| **Executor** | 全部 10 个工具 | 执行 + 自检自证。有全部工具是为了操作完用 query 工具自查，不需要 Supervisor 多调度一轮来验证 |

**对应代码：** `SupervisorTools.java` — `init()` 方法中的工具分配

---

### Q8: Supervisor 怎么判断什么时候走规划流程、什么时候直达执行？

**答：** 靠 prompt engineering，不是靠代码 hardcode。`supervisor-system.txt` 里有一张路由表：

- **简单操作** → 直达 `execute_task`（删除、修改、toggle、单天增删、简单创建 ≤2 个）
- **查询类** → 直达 `query_calendar`
- **复杂规划** → 走完整流程 `plan_task → query_calendar(查重) → execute_task(分批)`

关键：路由规则写在 prompt 里而不是代码里，LLM 自行判断，灵活且不需要改代码。

**对应代码：** `supervisor-system.txt`

---

### Q9: 子 Agent 之间怎么通信？Supervisor 怎么把 Plan 结果传给 Executor？

**答：** 子 Agent 之间不直接通信——**所有通信都经过 Supervisor**。Supervisor 是唯一的调度节点：

```
Supervisor 调用 plan_task，Planner 返回 JSON 计划
         ↓
Supervisor 收到 Planner 的 JSON 作为工具返回结果
         ↓
Supervisor 把 JSON 内容嵌入到 execute_task 的参数中
         ↓
Executor 收到指令（包含 Planner 的计划细节），开始执行
```

技术实现：每个子 Agent 对外暴露为 `execute(task 字符串) -> 结果字符串` 函数。Supervisor 通过 `ToolExecutionResultMessage` 把子 Agent 的结果接回对话上下文，再作为下一个 `UserMessage` 传给另一个子 Agent。

**对应代码：** `SubAgent.java` — `execute()` 方法；`AgentOrchestrator.java` — `chatWithSupervisor()` 中 tool result 拼接逻辑

---

### Q10: 你们用什么框架实现 Agent 的？LangChain4j 在其中的角色是什么？

**答：** 使用 **LangChain4j 1.3.0**，角色分两层：

**底层模型调用：**
- `OpenAiChatModel` — 非流式对话，用于子 Agent 内部调用（子 Agent 之间不需要流式）
- `OpenAiStreamingChatModel` — 流式对话，用于 Supervisor 最终回复用户时逐 token 输出 SSE

**消息与工具抽象：**
- 消息体系：`SystemMessage` / `UserMessage` / `AiMessage` / `ToolExecutionResultMessage` — 替代手写的 `Map.of("role","user","content",...)`
- 工具定义：`ToolSpecification` + `JsonObjectSchema` — 替代嵌套的 `Map.of()` 构建 JSON Schema
- 工具调用：`ChatResponse.aiMessage().hasToolExecutionRequests()` + `ToolExecutionRequest` — LangChain4j 自动把 LLM 返回的工具调用 JSON 反序列化为类型安全的对象

**我们不用的部分：** LangChain4j 的 `@Tool` 注解 + `AiServices` 自动代理。因为 Supervisor 需要跨子 Agent 调度，子 Agent 需要动态选择工具子集，`AiServices` 的静态代理模式不够灵活。

**对应代码：** `LangChainConfig.java`、`AgentOrchestrator.java`、`SubAgent.java`

---

### Q11: 流式输出中，子 Agent 的工具调用过程是流式的吗？为什么？

**答：** 不是。**只有 Supervisor 的最终回复是对用户流式的，子 Agent 内部调用全是非流式（blocking）。**

原因：
1. 子 Agent 是内部调用，用户不需要看到 Planner 逐 token 生成 JSON、Executor 逐 token 调用工具
2. 流式 + 工具调用循环组合会让异步逻辑复杂很多（需要 `CompletableFuture` 桥接）
3. 非流式的 `ChatModel.chat()` 是同步阻塞的，子 Agent 执行完直接返回结果，Supervisor 拿到结果继续下一步——逻辑清晰

实现：Supervisor 在 `onCompleteResponse` 中如果检测到 tool call，先用非流式 `ChatModel` 执行子 Agent，结果回传后再进入下一轮流式循环。

**对应代码：** `AgentOrchestrator.java` — `streamWithSupervisor()` 方法，子 Agent 调用走 `supervisorTools.executeSupervisorTool()` → `SubAgent.execute()` → `chatModel.chat()`

---

### Q12: 你怎么防止子 Agent 越权？比如 Planner 创建了待办、Checker 删除了数据？

**答：** 两层防御：

**第一层（工具分配）：** `SupervisorTools` 用白名单 `Set<String>` 控制每个子 Agent 的工具列表。Planner 传 `List.of()`（空列表）——它根本没有工具，LLM 想调也调不了。Checker 只有 3 个查询工具，物理上无法调用 `deleteTodo`。

**第二层（Prompt 约束）：** 子 Agent 的 system prompt 各有限制：
- Planner: "你没有任何工具，只能输出计划文本。不要尝试调用工具"
- Checker: "严格只用查询工具，绝对不调用 createTodo/deleteTodo/updateTodo..."
- Executor: "查询工具只用于验证，不用于回答用户的问题"

**对应代码：** `SupervisorTools.java` — `QUERY_TOOLS` / `EXECUTOR_TOOLS` 白名单；`planner-system.txt` / `query-system.txt` / `executor-system.txt`

---

### Q13: `yl_todo` 和 `yl_todo_date` 两张表的设计解决了什么问题？

**答：** 这是**目标—实例分离**的经典设计：

- `yl_todo`（父）：代表一个跨天目标（如"CPA备考，7/1-7/31，工作日"），存标题、日期范围、周频率等模板信息
- `yl_todo_date`（子）：代表目标在**某一天的具体实例**（如"7月3日 CPA备考 - 复习第3章"），有独立的 `dayContent`（当天任务描述）和 `status`（当天完成状态）

解决的问题：**单天操作不影响整体**。你在 7 月 3 号删了这一天（`removeTodoDay`），7 月 1、2、4 号不受影响。在之前，只能用 `deleteTodo` 删掉整个目标，其他天的计划也都没了。

---

## 三、架构对比题

### Q14: 你们从"单 Agent + Map.of() 手写 HTTP"演进到"Supervisor + LangChain4j"，核心变化是什么？

**答：**

| | 之前 | 之后 |
|---|---|---|
| **模型调用** | `RestTemplate` 手写 HTTP + 手动解析 SSE | LangChain4j `ChatModel` / `StreamingChatModel` |
| **消息构建** | `Map.of("role","user","content",...)` | `UserMessage` / `AiMessage` 等类型安全类 |
| **工具 Schema** | 深层嵌套 `Map.of()` | `JsonObjectSchema.builder()` |
| **工具循环** | 手动解析 JSON 中的 `tool_calls`、手动拼接分片 | `AiMessage.hasToolExecutionRequests()` + `ToolExecutionRequest` |
| **流式 Tool Call** | 手写 `ToolCallAccumulator` 按 index 聚合分片 | LangChain4j 内置 `ToolCallBuilder` 自动聚合 |
| **Agent 架构** | 1 个 Agent + 所有工具 + hardcode 意图检测 | Supervisor + 3 个子 Agent，LLM 自动判断路由 |
| **代码量** | ~550 行（`OpenAiService` + `ChatServiceImpl`） | ~350 行（分散在 `SubAgent`、`AgentOrchestrator`、`SupervisorTools` 中，职责更清晰） |

---

## 四、场景题

### Q15: 用户说"把明天的 CPA 学习调到后天"，整个调用链是怎样的？

**答：**

```
1. Supervisor 收到消息，判断路由：简单操作（单天调换）→ 直达 execute_task
2. Supervisor → execute_task("把明天(7/3)的CPA学习调到后天(7/4)")
3. Executor 收到指令：
   a. queryTodoList → 找到 "CPA学习" 的 todoId=5，确认 7/3 存在
   b. removeTodoDay(todoId=5, date="2026-07-03") → 移除 7/3
   c. addTodoDay(todoId=5, date="2026-07-04", dayContent="复习第3章") → 加 7/4
   d. queryDayDetail(date="2026-07-04") → 自查确认 7/4 已有 CPA 学习
   e. 汇总回复 Supervisor："已将CPA学习从7/3调到7/4，操作成功"
4. Supervisor 收到 Executor 的成功报告，回复用户："已帮你把明天的CPA学习调到后天。"
```

---

### Q16: 如果用户说"下个月备考 CPA 会计，帮我安排学习计划"，整个编排流程是怎样的？

**答：**

```
1. Supervisor 判断：复杂规划需求 → 走完整流程
2. plan_task("用户要备考CPA会计，下个月(7月)开始，请制定每日学习计划")
   → Planner 推理，输出 JSON: {goal:"CPA备考",todos:[{title:"会计概述",...},...]}
3. query_calendar("查询7月已有日程，检查是否与备考计划冲突")
   → Checker 查询 7 月待办，返回现有日程情况
4. Supervisor 检查 Plan 和查重结果，决定分批
5. execute_task("请创建以下3个待办：1) CPA会计概述 7/1-7/5...")
   → Executor 批量创建 → 自查 → 返回"已创建3个"
6. execute_task("请创建以下3个待办：4) CPA第4章 7/20-7/25...")
   → Executor 继续第二批
7. query_calendar("验证7月备考计划是否完整")
   → Checker 最终确认
8. Supervisor 回复用户："已为你制定7月CPA备考计划，共6个待办，覆盖整月。"
```

---

## 五、设计决策题

### Q17: 为什么不让 Planner Agent 直接调用 createTodo？非要让 Supervisor 转发给 Executor？

**答：** 这是 **单一职责原则** 在 Agent 设计中的应用：

1. **可控性**：如果 Planner 能创建待办，LLM 可能在规划时"手滑"直接创建了错误的待办。让它只输出 JSON，相当于"审批制"——Supervisor 先审计划，没问题再交给 Executor 执行。
2. **可观察性**：每一步都在 Supervisor 的对话历史里可见——知道 Planner 输出了什么、Checker 查重结果是什么、Executor 执行了什么。出问题时可以精确定位。
3. **可中断性**：未来如果在 Planner 输出后加上"用户确认"步骤（"这是为你制定的计划，确认创建吗？"），架构不需要改——Supervisor 在 `plan_task` 和 `execute_task` 之间插入一个回复即可。

### Q18: 如果 LLM 的 tool choice 调用了一个不存在的工具名，你们怎么处理？

**答：** 两层容错：
1. `McpToolRegistry.execute()` 检查工具名，不存在返回 `"未知的工具: " + name`——LLM 看到这个错误消息通常会自我纠错，下次调用正确的工具名
2. `SubAgent.execute()` 中的循环有最大轮次限制（5 轮），LLM 不会无限纠错——如果 5 轮后还没给出纯文本回复，会被强制拉回文本模式

### Q19: Prompt 文件为什么放在 resources/prompt/ 下而不是硬编码在 Java 里？

**答：** 
1. **热更新**：改 prompt 不需要重新编译，重启即可生效
2. **可读性**：prompt 几百字长，放在 .txt 里比嵌在 Java 字符串里的可读性好得多，支持多行、空行、列表等 Markdown 格式
3. **协作友好**：运营/产品同学可以直接改 prompt 调优，不需要懂 Java
4. **版本管理**：prompt 的 git diff 独立于代码，变更历史清晰
