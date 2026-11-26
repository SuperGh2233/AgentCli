# Hao CLI Harness 审视与升级计划

状态：草案，待用户确认“需要你决定的事”之后再开工 · 编写日期：2026-11-24 · 基线：`feat/steering-phase2b` / `0ebb6fa`（工作区里另有未提交的 Phase4A-3 Plan Steering 改动）

这里的 harness 指“包在大模型外面的那层运行框架”：Agent 循环、工具、权限、上下文管理、会话存储、对外接口。模型本身不在范围内。

## 1. 目标

把 Hao CLI 从“功能很多的教学项目”升级成一个**能实际使用、结构经得起追问**的 Agent harness：

- 一个统一的 Agent 内核，ReAct / Plan / Team / 子 Agent 都是它的不同用法，而不是三套各自维护的循环
- 一套带类型的事件协议，命令行、全屏界面、微信、Runtime API、无界面模式都只是它的不同显示端
- 默认安全：沙箱、权限规则、审批、钩子形成一条清楚的检查链
- 能放进 CI 和脚本里用（无界面模式 + JSON 输出），不只是交互式演示
- 每一步都有离线自动化测试（假模型回放），不靠真实模型调用证明正确性

## 2. 参考对象：各取什么

> 说明：下面对 Claude Code、Codex、pi 的描述来自公开资料，本轮联网核对被中断，**动手实现某一项之前要再对照官方文档确认一次**。“DeepSeek harness”具体指哪个项目我没有核实，请提供链接后再补这一行。

| 参考对象 | 值得借鉴的地方 | 在 Hao CLI 里对应的做法 |
|---|---|---|
| Claude Code | 生命周期钩子（工具调用前后、提交输入、结束时）；子 Agent 作为工具调用、用 Markdown 文件定义；Plan 模式只是“只读工具 + 先出方案”的权限状态；`/rewind` 同时回退对话和文件；无界面模式 `-p` 和 `stream-json` 输出；工具很多时按需加载工具定义 | 第 5、6、7、8 阶段 |
| Codex | 系统级沙箱默认开启（macOS Seatbelt、Linux Landlock/seccomp），“只读 / 可写工作区 / 完全放开”三档；`apply_patch` 一次改多个文件；app-server 用 JSON-RPC 暴露 thread / turn / item 事件，多个前端共用；运行中继续输入（Steer / Queue） | 第 3、6、7 阶段，Steering 收尾 |
| pi | 内核极小：系统提示词很短、只有 read / write / edit / bash 几个工具；各家模型统一成一套流式事件接口；会话是树，可以分叉、回到任意节点；功能靠扩展（TypeScript 扩展、Skill、提示词模板）加，不往内核里塞 | 第 2、3、8 阶段，以及“内核只放必须的东西”这个原则 |
| DeepSeek harness | 待确认 | 待确认 |

Hao CLI 已经有、而且比参考对象做得细的地方，要保留并作为亮点：auto 模式的审查模型 + 连续被拦才转人工、项目级永久权限规则（绑定项目根和 MCP 配置指纹）、网址来源限制（只有用户原话和搜索结果里的网址能打开）、三步上下文压缩、Side-Git 快照、MCP 双协议版本和 OAuth、自建评测框架。

## 3. 现状盘点（2026-11-24 按代码统计）

| 项目 | 数字 / 事实 |
|---|---|
| 主代码 / 测试文件 | 426 / 377 个 Java 文件，单个 Maven 模块 |
| 代码行数 | 评测以外的主代码约 53,500 行；`eval/` 124 个文件、约 34,200 行，约占主代码总行数 39%（评测已按用户要求暂停） |
| 最大的几个类 | `cli/Main.java` 3518 行；`tool/ToolRegistry.java` 2202 行；`agent/Agent.java` 1505 行；`agent/PlanExecuteAgent.java` 1416 行；`mcp/McpServerManager.java` 1026 行；`tool/TurnToolPolicy.java` 1019 行；`agent/AgentOrchestrator.java` 927 行；`agent/SubAgent.java` 823 行 |
| 模型适配 | 8 家供应商的 Client 都继承 `AbstractOpenAiCompatibleClient`，只走 OpenAI 兼容的 Chat Completions；没有 Anthropic Messages 或 OpenAI Responses 的原生适配 |
| 模型接口 | `llm/LlmClient`：消息格式照搬 OpenAI Chat Completions（role 字符串 + `reasoningContent` + `toolCalls`）；流式回调只有文字和思考两种增量，没有工具调用、用量、结束原因等事件；同步调用返回整条结果 |
| 对外事件 | `runtime/api/RuntimeEvent` 只有 `type` 和 `data` 两个字符串字段，没有类型约束 |
| 钩子 / 扩展 | 没有找到钩子机制 |
| 命令沙箱 | macOS Seatbelt、Linux bubblewrap 都已实现（`tool/CommandSandbox`），但默认 `off` |
| 会话存储 | `ConversationLedger` 只追加的 JSONL + `view_checkpoint`；`/resume`、`--continue`、`--session` 只恢复 ReAct；文件回退靠 Side-Git 快照和 `revert_turn`，和对话回退是两套 |
| 运行中继续输入 | 只在 inline + ReAct + `-Dpaicli.steering.enabled=true` 时可用；Plan 的安全点在工作区里有代码但没接进 `Main` |
| 命名 | 对外叫 Hao CLI，包名、配置目录、环境变量、jar 名仍是 paicli |

## 4. 需要改进或重构的地方（按影响排序）

### 4.1 三套 Agent 循环各管各的（重构，最重要）

`Agent`、`PlanExecuteAgent`、`AgentOrchestrator` + `SubAgent` 各自实现“调模型 → 执行工具 → 写回历史”。同一条规则（取消、预算、压缩、Steering 安全点、工具结果包装、`view_checkpoint`）要在三处分别实现和测试，Steering 计划里 Plan 的安全点就是因为这个要单独做一遍。参考对象的做法都是**一个循环内核**：Plan 是权限状态 + 提示词，子 Agent 是“用受限工具集再跑一次内核”。

### 4.2 `Main.java` 是“什么都管”的大类（重构）

3518 行里混着：组装各个组件、命令行循环、斜杠命令、审批界面、计划审阅、Steering 输入循环、MCP 提示打印。任何新入口（无界面模式、新前端）都得改它。应拆成：组装（composition root）、会话控制、命令注册表、各前端。

### 4.3 模型接口太贴近某一家（重构）

消息模型是 OpenAI Chat Completions 的形状，各家差异（思考内容回传、DeepSeek 的 DSML、Anthropic 的内容块和缓存标记、OpenAI Responses 接口）只能在各个 Client 里打补丁。流式接口不发工具调用、用量和结束原因事件，界面也就没法实时显示“正在调用哪个工具”。应改成**和厂商无关的内容块消息 + 统一流式事件**，各家 Client 只负责双向转换。

### 4.4 没有统一的事件协议和无界面模式（补能力）

inline、Lanterna、微信、Runtime API 各自从 Agent 取数据；`RuntimeEvent` 是字符串。没有 `-p` 一次性执行和 JSON 输出，没法接 CI、脚本或别的程序。应定义带类型的“会话 → 轮次 → 条目”事件（消息、思考、工具调用、工具结果、审批请求、用量、错误），所有前端只消费事件。

### 4.5 安全检查分散，沙箱默认关（加固）

检查分在 TurnToolPolicy、HitlToolRegistry、权限规则、ToolRegistry、PathGuard、CommandGuard、命令沙箱好几处，顺序靠调用关系保证，没有一份“这次调用为什么被放行/拒绝”的完整记录。沙箱已经实现两个平台却默认关。应合并成一个策略引擎，输出带每一步理由的决定记录；沙箱默认 `auto`（能用就开，开不了明确提示），并提供“只读 / 可写工作区 / 放开”三档。

### 4.6 没有钩子和扩展点（补能力）

团队想在工具调用前做检查、在结束时跑格式化或测试，只能改源码。应提供钩子：输入提交、工具调用前（可拒绝或改参数）、工具调用后、轮次结束、会话开始；先支持“执行外部命令 + JSON 输入输出”这一种。

### 4.7 会话只能线性恢复（补能力）

对话回退和文件回退是两套机制，不能分叉。应把会话做成树：任一用户消息处可以分叉；`/rewind` 同时回到那一刻的对话和文件（复用 Side-Git 快照）。

### 4.8 工具层（改进）

- `ToolRegistry` 2202 行，内置工具实现、并行调度、参数校验、MCP 注册都在一起，应拆成“工具接口 + 每个工具一个类 + 调度器”
- 增加一次改多个文件的补丁工具（`apply_patch` 格式），和 `edit_file` 并存
- MCP 工具多时，先只给模型工具名和一句话描述，用到再加载完整定义，省上下文

### 4.9 工程结构（改进）

- 拆成多个 Maven 模块：内核、模型适配、工具、MCP、命令行/界面、评测、微信。评测和微信不再拖慢主构建
- 用假模型 + 录制回放写循环级测试，保证重构前后行为一致（评测里已有 `TracingLlmClient`，可以借鉴）
- 补可观测性：每轮、每次模型调用、每次工具调用的耗时和用量，可选导出 OpenTelemetry

## 5. 目标架构

```
前端：inline 命令行 │ Lanterna 全屏 │ 无界面(-p / JSON) │ Runtime API │ 微信
            ▲ 只消费事件，提交输入和审批结果
事件协议：Session → Turn → Item（message / reasoning / tool_call / tool_result / approval / usage / error）
            ▲
内核 AgentLoop：调模型 → 策略引擎 → 执行工具 → 写回历史 → 压缩 → 安全点（Steer / 取消 / 预算）
   │ 用法：普通对话 │ Plan（只读工具 + 方案审阅）│ 子 Agent 工具 │ Team（多个子 Agent 编排）
   ├─ 模型适配：统一内容块 + 流式事件（DeepSeek / GLM / Kimi / 混元 / OpenAI 兼容 / Anthropic）
   ├─ 策略引擎：网址来源 → 权限规则 → 会话模式 / 审查模型 / 人工审批 → 路径与命令检查 → 沙箱，输出决定记录
   ├─ 工具：内置工具 │ MCP │ Skill │ 钩子
   └─ 会话树存储：JSONL 追加 + 分叉 + 文件快照
```

## 6. 分阶段计划

每个阶段单独提交，结束条件都包括：`mvn test -Pquick` 失败名单不多于开始前、新增测试全部通过、文档同步（`AGENTS.md` 只在规则变化时改）。全程不调用真实模型、不跑 Docker、不碰评测代码（用户已暂停评测）。

| 阶段 | 内容 | 结束条件 |
|---|---|---|
| 0 基线 | 记录当前 quick 失败名单；新增假模型 `ScriptedLlmClient`（按脚本返回文字和工具调用），给 ReAct 写 5～10 个循环级回放测试，覆盖工具调用、并行工具、取消、压缩、Steering 安全点 | 回放测试在重构前全部通过，作为后面每阶段的对照 |
| 1 模型接口 | 新增厂商无关的消息和流式事件类型；先给 DeepSeek 和一个 OpenAI 兼容 Client 加转换层，旧接口保留为适配器 | 两个 Client 在新旧接口下回放结果一致；已有 llm 测试不退化 |
| 2 统一内核 | 从 `Agent.java` 抽出 `AgentLoop`，ReAct 改为调用它；预算、RunawayGuard、压缩、Steering 安全点、`view_checkpoint` 只在内核实现一次 | 阶段 0 的回放测试全部通过；`Agent.java` 只剩组装和对外方法 |
| 3 事件协议 + 无界面模式 | 定义 Session / Turn / Item 事件；内核只发事件；新增 `-p "任务"` 和 `--output-format text|json|stream-json`；Runtime API 改发同一套事件 | 无界面模式有端到端测试（假模型）；inline 显示不变 |
| 4 拆 `Main` | 组装、会话控制、命令注册表、前端分开；斜杠命令改为注册表 | `Main.java` 降到 500 行以内；命令解析和 TUI 测试不退化 |
| 5 Plan / 子 Agent / Team 迁移到内核 | Plan = 只读工具 + 方案审阅；新增子 Agent 工具，定义放 `.paicli/agents/*.md`；Team 改为编排多个子 Agent；Plan 的 Steering 安全点随之接通 | Plan / Team 已有测试通过；评测审计约定不变（planner / reviewer 不给工具） |
| 6 策略引擎 + 沙箱 + 钩子 | 合并各处检查为一个引擎并输出决定记录；沙箱默认 `auto` 并提供三档；新增钩子（外部命令 + JSON） | 安全策略测试（CI 那条）全部通过；新增决定记录和钩子测试 |
| 7 会话树 + 回退 | 会话分叉；`/rewind` 同时回退对话和文件；`/resume` 支持 Plan / Team 会话 | 分叉、回退、恢复都有测试；旧会话文件仍能读 |
| 8 工具层与工程 | 拆 `ToolRegistry`；补丁工具；MCP 工具按需加载；多模块拆分；可观测性 | 各模块能单独构建测试；`ToolRegistry` 拆分后行为不变 |
| 9 收尾 | 品牌和包名迁移（如果决定做）；README 重写架构说明；简历用的架构图和数据 | 兼容旧的 `~/.paicli` 和 `PAICLI_*` 至少一个版本 |

顺序理由：先有回放测试才敢动内核（0）；内核依赖新的模型接口（1→2）；事件协议依赖统一内核（2→3）；拆 `Main` 要等事件协议定下来，否则前端会改两次（3→4）；Plan / Team 迁移依赖内核和事件（5）；其余可以并行或往后放。

### 第 0 阶段进展（2026-11-25，已完成：回放测试和两个 macOS 修复都在用户本机通过）

开发分工：用户在本机跑测试，助手改代码（助手所在环境没有 JDK 和 Maven，没有编译过）。

- `src/test/java/com/paicli/testing/ScriptedLlmClient.java`：共用的假模型，按脚本返回回复，记录每次请求的消息和工具定义；可指定第 N 次请求失败，或在第 N 次请求时回调（用来模拟取消）。脚本用完再请求会报错
- `src/test/java/com/paicli/testing/ToolProtocolAssertions.java`：检查每个工具调用都紧跟着恰好一条对应的工具结果
- `src/test/java/com/paicli/agent/ReActLoopReplayTest.java`：9 个回放测试，覆盖直接回答、单个工具调用、并行只读工具保持顺序、未知工具、参数缺失、多步循环、供应商报错、模型回复后取消、两轮对话复用历史
- `tools/test-failures.sh`：从 Surefire 报告里列出失败测试，用来对比改动前后的失败名单
- 已有测试里已经覆盖、这次没有重复写的：Steering 安全点（`AgentSteeringSafePointTest`）、预算收尾（`AgentBudgetFinalizationTest`）、runaway 提醒（`AgentRunawayReminderTest`）。第 2 阶段把它们和本组测试一起当作对照
- 还没覆盖：上下文压缩。压缩要假模型按四栏格式返回摘要，等第 2 阶段动压缩代码时再补
- 已有的十几个测试各自写了一份假模型，第 2 阶段再逐步换成 `ScriptedLlmClient`，这次不动

用户本机要做的事：

```bash
mvn test -Pquick; bash tools/test-failures.sh > /tmp/quick-baseline.txt   # 记录基线失败名单
mvn test -Dtest=ReActLoopReplayTest -DskipTests=false                   # 新测试应全部通过
```

如果新测试有失败，把失败信息贴回来：可能是测试对现有行为的理解有偏差（改测试），也可能是发现了真问题（单独记录，不在第 0 阶段修）。

本机第 1 轮结果（详细记录见 `docs/reviews/2026-11-25-macos-test-round1.md`）：

- `ReActLoopReplayTest` 9 个全部通过，没有改测试
- quick 基线（JDK 21）1707 个测试里 16 个报错（报告目录里累计 18 个），全是 macOS 才暴露的两个老问题，已修，等复测：
  - `McpTrustStore` 检查目录时，祖先路径里只要有符号链接就拒绝，而 macOS 的临时目录在 `/var → /private/var` 下面。改成属主是 root 的系统符号链接放行，用户能改的仍然拒绝；新增 `McpTrustStoreTest.userCreatedSymlinkedParentDirectoryIsRejected`
  - `ResourceFailureObserver` 用真实路径当项目根，但模型给的绝对路径还带着 `/var` 前缀，前缀对不上就漏算。改成只把根这一段换成真实路径；新增 `ResourceFailureObserverTest.absolutePathThroughSymlinkedRootMatchesRelativePath`（在 Linux 上也能复现）
- JDK 25 下有 3 个终端测试类在全量运行时卡在 JLine 的 `JniNativePty.close`，Mockito 5.11 还多报一个错；JDK 21 没有这些问题。本机回归暂时固定用 JDK 21，升级 JLine / Mockito 另排

本机第 2 轮：5 个相关测试类全部通过（McpTrustStoreTest 9、McpServerManagerTrustTest 12、McpServerManagerTest 15、McpConfigLoaderTest 14、ResourceFailureObserverTest 10）；报告目录里只剩 `McpElicitationCapabilitiesTest.plainSupportsBothAndDefaultRendererStaysEmpty`，是上面说的 JDK 25 + Mockito 问题，JDK 21 下通过

### 第 1 阶段进展（2026-11-25，已完成：用户本机 quick 1732 个测试 0 失败）

做法：先加新接口和双向转换，旧接口一行不删；所有 Client 通过 `LegacyModelClient` 自动获得新接口，Agent 第 2 阶段再切过去。

- 新包 `src/main/java/com/paicli/llm/core/`：
  - `ContentBlock`：内容块，文字 / 思考 / 图片 / 工具调用四种（sealed interface，只能是这四种）
  - `ChatMessage`：角色（system、user、assistant、工具结果）+ 内容块；构造时检查工具结果必须带调用 id、只有助手消息能带思考和工具调用
  - `StopReason`：正常答完 / 要调工具 / 被长度截断 / 被审核拦下 / 不明。截断优先于工具调用（参数可能是半截 JSON）；DSML 转出的调用算要调工具；说 tool_calls 却没有调用算不明
  - `TokenUsage`：用量，带“供应商有没有返回”的标记，没返回不当成 0
  - `StreamEvent`：思考增量、正文增量、工具调用完成、用量、结束。工具调用等整条回复结束才发
  - `ModelResponse`、`ToolSpec`、`ModelClient`（新接口）
  - `LegacyMessageCodec`：新旧消息格式互转；`LegacyModelClient`：把任意 `LlmClient` 包成 `ModelClient`
- 旧代码的改动只有三处：`StreamListener` 加默认空方法 `onFinishReason`；`AbstractOpenAiCompatibleClient` 在请求成功返回前回调它（重试中途的尝试不回调）；DeepSeek 的 DSML 过滤 listener 转发它。`Message` 加了公开的 `textOf`
- 测试：
  - `LegacyMessageCodecTest`（12 个）：典型对话来回转换不变、块顺序、空串和 null 区分、图片、多段文字、单段文字会被拍平（写明的有意差异）、非法角色、回复转换、停止原因换算
  - `ModelClientReplayTest`（6 个）：同一段流式响应分别走新旧接口，断言请求体、回复、流式文字完全一致；覆盖 DeepSeek 思考回答、DeepSeek 工具调用、DeepSeek DSML（线上 stop → 新接口 TOOL_USE）、GLM 截断的工具调用（MAX_TOKENS）、GLM 普通回答、不传事件回调时推断停止原因
- 已知限制：评测包里几个包装 listener 的类（`ContextWindowCappedLlmClient` 等）没转发 `onFinishReason`，经过它们时停止原因按有没有工具调用推断。评测已暂停，不改
- 工具参数的逐字流式（像 Codex / pi 那样边生成边显示参数）这次没做：OpenAI 兼容接口在“还没有正文输出”时会自动重试，提前发工具调用片段会让界面看到重复内容。第 3 阶段做事件协议时再定重试和流式显示怎么配合

用户本机要做的事（JDK 21）：

```bash
mvn test -DskipTests=false -Dtest='LegacyMessageCodecTest,ModelClientReplayTest'
mvn test -DskipTests=false -Dtest='com.paicli.llm.*Test,ReActLoopReplayTest'   # 已有 llm 测试不退化
mvn test -Pquick; bash tools/test-failures.sh                                  # 应该没有输出
```

### 第 2 阶段进展（2026-11-25，已完成：两步都在用户本机通过，quick 失败名单为空）

分两步走，降低风险：

1. **第一步（这次）：把 ReAct 循环原样搬进内核，行为不变。** 不改任何对外方法，22 个直接用 `Agent` 的已有测试和阶段 0 的回放测试都是对照
2. **第二步（下次）：内核改用第 1 阶段的 `ModelClient`，按停止原因处理“被长度截断的工具调用”**（现在会把半截 JSON 当正常调用执行）。这一步会改行为，单独提交

第一步的改动：

- `agent/AgentLoop.java`（新，内核）：取消检查 → LSP 诊断注入 → 上下文压缩 → 预算检查（超了就无工具收尾）→ Steering 安全点 → 调模型 → 执行工具（统一走 `TurnToolPolicy.execute`）→ 工具结果加不可信标记 → 图片和 Skill 正文注入 → 重复调用提醒。返回 `Result(状态, 文字)`，状态是 完成 / 部分完成 / 取消 / 失败
  - 内核不碰终端：流式文字交给 `AgentLoop.Display`，其他提示、状态栏、最终回复格式、自动提取记忆交给 `AgentLoop.Host`。第 3 阶段把这两个接口换成统一事件
- `agent/ConversationContext.java`（新）：发给模型的那份对话 + 会话记录 + 压缩 + 检查点。往对话加消息都走 `append`（同时写会话记录），压缩后自动写 `view_checkpoint`；`Agent` 的 `conversationHistory` 字段就是它的消息列表本身，不是副本
- `agent/ReActStreamRenderer.java`：原来 `Agent` 里的流式渲染内部类，用脚本原样搬出来，只改了类名和 5 个方法的可见性
- `agent/ToolResultSummaries.java`、`agent/RequestContextLogger.java`：工具结果摘要行、每次请求的上下文日志，原样搬出
- `Agent.java` 从 1505 行降到 644 行，只剩组装、每轮开始前的准备（本轮工具策略、长期记忆放进 system prompt、写入用户输入）和对外方法（清空、恢复、手动压缩、状态和上下文统计）
- 新测试 `AgentLoopTest`（4 个）：直接测内核的四种结果状态，以及显示和收尾回调的先后顺序（和重构前的代码顺序逐行对过）
- 没动的：Plan 和 Team 的循环（第 5 阶段迁移）；`Agent` 的所有 public 方法签名；日志里循环相关的记录器名字从 `Agent` 变成 `AgentLoop`

用户本机要做的事（JDK 21）：

```bash
mvn test -DskipTests=false -Dtest='AgentLoopTest,ReActLoopReplayTest'
mvn test -DskipTests=false -Dtest='com.paicli.agent.*Test,UnexecutedToolCallNoticeTest,StartupSessionResumeTest,MainPlanAgentFactoryTest,DeepSeekConversationReplayTest'
mvn test -DskipTests=false -Dtest='*Steering*Test,TurnInputManagerTest,InputCoordinatorTest,TerminalInputGateway*Test,JLineInputAdapterTest,InlineApprovalGatewayTest,AgentBudgetFinalizationTest,ToolRegistryToolStartGateTest'
mvn test -Pquick; bash tools/test-failures.sh                                  # 应该没有输出
```

本机第 1 轮：`AgentLoopTest` 4、`ReActLoopReplayTest` 9、Steering / 会话记录 / 预算 / 重复提醒相关测试全部通过，报告目录 1731 个测试 0 失败。
但 `AgentStreamRendererTest` 用反射按名字加载 `Agent$StreamRenderer`，这个内部类已经搬走，它之所以“通过”是因为没 clean，`target/classes` 里还留着旧 class。已改成直接构造 `ReActStreamRenderer`。教训写进了 `AGENTS.md`：删除、改名或搬走类之后要 `mvn clean test`。

复测（必须 clean）：

```bash
mvn clean test -Pquick; bash tools/test-failures.sh                            # 应该没有输出
```

另外建议手工跑一次 jar（`mvn clean package` 后 `java -jar target/paicli-1.0-SNAPSHOT.jar`），随便问一个要读文件的问题，看思考、工具调用、回答的显示和以前一样。

第二步（模型调用走新的模型层，按停止原因处理截断）：

- 取舍：对话仍存成旧格式（`LlmClient.Message`），只把“调模型”这一步换成新层。整份对话换成新格式会牵动会话记录的存储格式、压缩和 Plan / Team，放到第 7 阶段改会话存储时一起做。这样发给供应商的内容逐字不变
- `LegacyModelClient.exchange`（新方法）：直接收旧格式的对话和工具定义，原样交给原 Client，同时返回新格式结果（停止原因、事件）和原样的旧回复。原样返回是为了不把 null 变成空串（来回转换会丢掉这个区别，会话记录的 JSON 就变了）
- `AgentLoop`：每次请求都经过 `exchange`，流式增量从事件转给显示
  - 停止原因是“被长度截断”且有工具调用：不执行，每个调用回一条固定说明（`TRUNCATED_TOOL_CALL_RESULT`，让模型缩短参数重试），终端提示一行；调用和结果仍一一配对。一直截断时，预算的停滞检测（连续 5 步同样的调用）会兜底收尾
  - 最终回复被截断：末尾加提示 `TRUNCATED_ANSWER_NOTICE`
  - 其他停止原因行为不变
- 测试：`ScriptedLlmClient` 可以指定每步的停止原因（`truncated(...)`、`ofSteps(...)`）；`AgentLoopTest` 加 3 个（截断的写文件调用不执行且配对完整、截断回答带提示、正常停止原因行为不变）；新增 `LegacyModelClientTest`（2 个：旧回复原样返回、length 报 MAX_TOKENS）
- 还没覆盖：Plan 和 Team 的循环仍会执行被截断的调用，第 5 阶段迁到内核后自动获得

用户本机要做的事（JDK 21）：

```bash
mvn clean test -DskipTests=false -Dtest='AgentLoopTest,LegacyModelClientTest,ReActLoopReplayTest,ModelClientReplayTest,LegacyMessageCodecTest'
mvn test -Pquick; bash tools/test-failures.sh                                  # 应该没有输出
```

### 第 3 阶段进展（2026-11-25，第一步已完成：用户本机全部通过；第二步（交互终端改消费事件、Runtime API 发同一套事件和白名单）待排期）

同样分两步：

1. **第一步（这次）：定义事件，内核发事件；新增无界面模式 `-p`，它只消费事件。** 交互终端不动，仍走 `Display` / `Host`，显示一定不变
2. **第二步（下次）：交互终端改成消费事件，`Display` / `Host` 里纯显示的部分删掉；Runtime API 改发同一套事件**

第一步的改动：

- `agent/event/AgentEvent`（新）：一轮执行的事件，9 种——开始、思考增量、正文增量、工具调用开始、工具调用结束、工具调用跳过、提示、用量、结束。文字不带颜色代码。`AgentEventListener` 订阅
- `AgentLoop`：在原来的位置同时发事件（显示动作不变）；监听器抛异常只记日志，不打断任务；`Result` 多了 `answer`（原始回答，不含思考过程和终端提示）。`Agent.setEventListener` 对外订阅
- `headless/`（新）：
  - `HeadlessOptions`：参数解析（`-p`、`--output-format`、`--permission-mode`、`--allow-command`）
  - `HeadlessToolRegistry`：没人确认，所以按白名单放行。默认只读；`edit` 才能写文件；命令只放行逐字列出的；其余（MCP、浏览器、记忆、回滚）不暴露也不执行。原有的 PathGuard、CommandGuard、沙箱照常检查
  - `SilentRenderer`：Agent 内部写终端的内容全部丢掉，保证标准输出干净
  - `AgentEventJson`：事件转 JSON（下划线字段名，对外约定）
  - `HeadlessRunner` / `HeadlessCommand`：组装、订阅事件、按格式输出、退出码（0 完成 / 1 失败 / 2 参数错误 / 3 部分完成 / 130 取消）
- `Main.main` 只加了一个入口判断（看到 `-p` 就交给 `HeadlessCommand`），其余不动
- 规格：`docs/specs/headless-mode.md`（命令、权限、退出码、三种输出格式、事件字段）
- 测试：`HeadlessOptionsTest`（5 个）、`HeadlessRunnerTest`（8 个，假模型端到端：text 只有回答、json 结果对象、stream-json 事件顺序、只读模式不暴露也不执行写文件、edit 模式能写、失败退出码、text 模式失败不污染标准输出、命令白名单逐字匹配）、`AgentLoopTest` 加 3 个（事件顺序、监听器出错不影响任务、截断时的跳过事件和不带颜色的提示）

安全备注（发现的问题，这次没改）：Runtime API（`serve --http`）和后台任务（`DurableTaskManager`）调 `Main.runHeadlessTask`，用的是普通 `ToolRegistry`，没有 HITL、没有白名单，模型可以执行任意命令（只剩 CommandGuard 黑名单和可选的沙箱）。建议改用 `HeadlessToolRegistry` 同一套白名单；这会改变现有接口的行为，需要你同意后再做（可以并进第 3 阶段第二步）。

用户本机要做的事（JDK 21）：

```bash
mvn clean test -DskipTests=false -Dtest='HeadlessOptionsTest,HeadlessRunnerTest,AgentLoopTest,ReActLoopReplayTest'
mvn test -Pquick; bash tools/test-failures.sh                                  # 应该没有输出
mvn clean package
java -jar target/paicli-1.0-SNAPSHOT.jar -p "用一句话说明这个项目是做什么的"             # 会调用真实模型，消耗少量 Token
java -jar target/paicli-1.0-SNAPSHOT.jar -p "列出 src/main/java/com/paicli 下的包" --output-format stream-json
```

最后两条会调真实模型（按 AGENTS.md 要你同意才跑；不跑也不影响测试结论，只是少了一次真机验证）。

### 技术评估后的 P0：无人值守入口统一白名单（2026-11-26，已完成：用户本机全部通过，quick 失败名单为空）

依据：`docs/reviews/2026-11-25-tech-hotspot-evaluation.md` 第 2.5 节和 ADR-001。用户回复“继续推进”后开始做。

- 问题：Runtime API（`serve --http`）和后台任务调 `Main.runHeadlessTask`，用裸 `ToolRegistry`，没有审批也没有白名单。顺带发现：它返回 `agent.run()` 的值，真实模型流式输出时这个值是空串，Runtime API / 后台任务拿不到回答
- `headless/UnattendedPermissions`（新）：从 `serve` 参数取 `--permission-mode`、`--allow-command`，或从配置值取模式；默认只读、不执行命令
- `headless/UnattendedTaskRunner`（新，实现 `TaskRunner`）：每个任务一个 Agent，`HeadlessToolRegistry` 白名单 + 静默渲染器，结果取 `TurnFinished` 事件里的原始回答
- `Main`：删除 `runHeadlessTask`；`startRuntimeApiAndBlock` 和 `openTaskManager` 改用 `UnattendedTaskRunner`；启动时打印权限说明；后台任务读 `PAICLI_TASK_PERMISSION_MODE`
- **行为变化**：以前这两条路径什么都能执行，现在默认只读。需要写文件的调用方要加 `--permission-mode edit` 或设置 `PAICLI_TASK_PERMISSION_MODE=edit`；已写进 README、`.env.example`、`AGENTS.md`、`docs/specs/headless-mode.md`
- 测试：
  - `UnattendedTaskRunnerTest`（6 个）：流式回答能拿到；原裸 Agent 路径流式时返回空串（记录问题）；默认拒绝命令和写文件；edit 能写；参数解析；配置值解析
  - `security/AgentSecurityRegressionTest`（5 个，评估报告 Phase 1 的“安全回归集”第一批）：假模型完全听从注入内容的最坏情况下——路径穿越和绝对路径读不到项目外文件、项目内符号链接逃不出去、文件里的注入命令不会被执行（且文件内容带不可信标记）、工具结果里出现的网址不能被抓取、只读模式下注入不能写文件
  - `ScriptedLlmClient` 加 `streamingContent()`，模拟真实供应商的流式正文

用户本机要做的事（JDK 21，新增了类，要 clean）：

```bash
mvn clean test -DskipTests=false -Dtest='UnattendedTaskRunnerTest,AgentSecurityRegressionTest,HeadlessRunnerTest,HeadlessOptionsTest'
mvn test -Pquick; bash tools/test-failures.sh                                  # 应该没有输出
```

### 技术评估后的 Phase 1：Harness 回归场景（2026-11-26，已完成：用户本机 11 个场景 + HeadlessRunnerTest 8 个全部通过，quick 没有失败）

依据：评估报告 3.4 节 / ADR-004 的“免费层评测”。用户回复“继续推进 按照你的推荐来”后开始做。

- `headless/HarnessMetrics`（新）：从事件统计 harness 指标（模型请求数、工具调用 / 成功 / 失败 / 跳过、成功率、提示数、Token、耗时）；`Collector` 订阅事件。无界面 `json` / `stream-json` 的结果对象新增 `tools` 和 `notices` 字段（旧字段不变）。真实模型对照实验以后用同一口径
- `HarnessScenarioSuiteTest`（新，11 个场景）：假模型固定脚本，走完整无界面链路，逐项精确断言指标：直接回答、读文件、并行读、改文件失败后恢复、参数缺失后改正、截断后重试、同一失败调用三次触发提醒、只读模式拒绝写、硬轮数上限出部分结果、供应商失败、一个“已知问题定格”场景。跑完写 `target/harness-metrics/scenarios.jsonl`（不含耗时，逐行可比）
- 用法：改内核或工具层前后各跑一次，`diff` 两份 `scenarios.jsonl`；数字变化要么是有意的（同时改断言、在这里写明），要么是回归

发现的问题（这次没改）：`read_file` 读不到文件时返回“读取文件失败: …”（`ToolRegistry.java:368`），这个前缀不在 `looksLikeFailureText`（`ToolRegistry.java:1664`）的失败名单里，于是被算成成功。影响：事件里的成功标记、harness 指标、`RunawayGuard` 的同类错误计数（它只数失败结果）。修法很小（失败名单加这个前缀，或让 `read_file` 直接返回 `ToolOutput.failure`），但会改变工具结果的成败口径，评测的工具调用统计可能依赖它，需要确认后再改。场景 `known_issue_read_failure_counted_as_success` 定格了现在的行为，修好后改断言

验收（2026-11-26 用户本机，JDK 21）：`HarnessScenarioSuiteTest` 11/11、`HeadlessRunnerTest` 8/8 通过；quick 的 233 份报告里没有失败或报错。第一份 `scenarios.jsonl` 和上面每个场景的期望数字一致（精确数字就写在测试断言里，断言本身就是基线）。

复跑命令：

```bash
mvn clean test -DskipTests=false -Dtest='HarnessScenarioSuiteTest,HeadlessRunnerTest'
cat target/harness-metrics/scenarios.jsonl                                      # 第一份基线，可以存一份留着对比
mvn test -Pquick; bash tools/test-failures.sh                                  # 应该没有输出
```

## 7. 需要你决定的事

1. **包名和配置目录要不要从 paicli 改成 haocli**：改了简历上更统一，但影响所有用户配置和文档；建议放到最后一阶段，并保留旧路径兼容
2. **要不要拆多模块**：建议拆，但会动构建脚本和 CI
3. **Lanterna 全屏界面和微信通道还要不要继续维护**：如果不常用，建议冻结只修 bug，把精力放在内核和无界面模式
4. **“DeepSeek harness”指哪个项目**：请给链接
5. **Steering 的真实终端验收**：Phase3B-3A 一直等你在真实终端手工验收，内核重构前最好先做完，否则重构后要重新验收

## 8. 这一轮不做的事

- 不重写成别的语言，不换掉 JLine / Lanterna
- 不实现旧的 MCP sampling，不做通用容器或虚拟机沙箱
- 不恢复评测开发，不调用真实模型做验证
- 不为了“看起来新”引入用不上的框架
