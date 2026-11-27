# Hao CLI 后续路线：可嵌入的 Agent 运行时

状态：已和用户逐条确认（2026-11-26）；P0 已完成（品牌 R0 合并为 `56cbd43`，2026-11-27 又按用户要求全量改名为 haocli，取代 R1–R4）；甲-1、甲-2 已通过（2026-11-27），下一步甲-3（其余只读工具） · 取代 `docs/plans/active/PLAN-20261124-harness-upgrade.md` 里第 4 阶段以后的顺序；那份计划保留为第 0–3 阶段的实施历史

依据：用户提供的《Hao CLI 产品与架构演进实施计划》（docx，2026-11-26，未放进仓库）。本计划采纳它的定位、原则、非目标和评测纪律，按仓库实际情况调整了阶段顺序和范围，差异见文末“和原文档的差异”。

这里的 harness 指包在大模型外面的那层运行框架：Agent 循环、工具、权限、上下文管理、事件、对外接口。

## 1. 定位

三层，按重要性排：

| 层 | 是什么 | 怎么证明 |
|---|---|---|
| B 运行时 | Java 应用可以直接嵌入的 Agent 运行时：模型调用、工具、权限、上下文、事件都能复用 | 一个只依赖运行时的独立 Java 示例，不需要终端 |
| C Harness 实验 | 用固定模型和任务，验证上下文和执行机制的真实收益 | 离线回放保证协议和安全不变量；授权后做一次预先登记的真实模型对照 |
| A Coding CLI | 自己日常用它读代码、改代码、跑测试 | 在自己的仓库里完成真实小任务，记录失败和体验 |

不追求和 Claude Code、Codex 功能对齐；不做商业平台、多租户、插件市场；没有可靠性、开发体验或实测收益支撑的新功能进待办池。

## 2. 原则

- 最小内核：内核只管“一轮模型—工具循环、追加消息、调用强制策略、检查取消和预算、发事件、返回能区分的状态”。上下文装配、压缩、验证器都通过接口调用，实现不进循环
- 状态由做事的一方如实报告：工具自己声明成功还是失败，不再由外面按文字猜
- 安全由确定性代码执行，不靠提示词；被策略拒绝的操作，用户确认也放不行；审查模型不能替人做决定
- 先用依赖测试守住包边界，再拆 Maven 模块
- 每个阶段先有回放测试证明行为一致，再删旧代码；不做大爆炸式重构
- 评测驱动：效果类改动（上下文、压缩）要有真实模型数据才算数；正确性和安全类改动用离线回放证明

## 3. 当前基线（2026-11-26 核对）

- `main` = `dfe77a9`（远端，只多了第 12 轮报告）；第 11、12 轮的代码（终端改为消费事件、Runtime API 转发事件、`read_file` 失败分类）还在工作区没提交，用户本机 quick 1787 通过 / 0 失败 / 5 跳过（`docs/reviews/2026-11-26-macos-test-round12.md`）
- 大类：`Main.java` 3519 行、`ToolRegistry.java` 2203 行、`PlanExecuteAgent.java` 1416 行、`Agent.java` 653 行、`AgentLoop.java` 592 行
- ReAct 已经走统一内核 `AgentLoop`；Plan、Team 还是各自的循环
- 工具结果 `ToolOutput` 有 `successful` 和失败码（只有 `NONE`、`EDIT_SOURCE_NOT_FOUND`），但多数内置工具返回纯文字，由 `ToolRegistry.looksLikeFailureText` 按前缀猜成败
- `AgentBudget` 默认 Token 和轮数都不限；`-p`、Runtime API、后台任务没有任何上限
- 命令沙箱默认 `off`；沙箱里没有网络，也看不到 `~/.m2`
- 第 12 轮报告发现：模型失败时 `agent.turn_finished.status=failed`，外层却发 `message.delta`（错误文本）和 `turn.completed`

## 4. 阶段

顺序：P0 基线收敛 → 甲 契约 → 丙 统一执行 → 目标循环 → 安全 → 乙 拆 Main → 上下文 → 拆模块 → 收尾

不按周排期，每个阶段按自己的验收标准结束。每个阶段的公共结束条件：`mvn test -Pquick` 和安全专项（`AGENTS.md` 验证命令表里 CI 那一条）没有新失败、新增测试全部通过、测试报告写明提交号、文档同步。

### P0 基线收敛

1. 第 11、12 轮代码合成一个提交补上（两轮改动有重叠文件，不硬拆）
2. Runtime API 结果语义（`docs/adr/ADR-0002-result-semantics.md`）：

   | 内核结果 | 外层事件 |
   |---|---|
   | `completed` | `turn.completed`，带 `"agent_status":"completed"` |
   | `partial` | `turn.completed`，带 `"agent_status":"partial"` |
   | `failed` / `cancelled` | `turn.failed`，`error` 填原因，带 `agent_status`；不再发 `message.delta` |

   `-p` 的退出码已经区分（0 完成 / 1 失败 / 3 部分 / 130 取消），不用改。补测试：模型失败时收到 `turn.failed`、没有 `message.delta`
3. 无人值守上限（只管 `-p`、Runtime API、后台任务；交互命令行有人看着，仍不限）：

   | 上限 | 默认 | 改法 |
   |---|---|---|
   | 模型请求次数 | 50 | `--max-turns` / `HAOCLI_UNATTENDED_MAX_TURNS` |
   | 总 Token | 2,000,000 | `--max-tokens` / `HAOCLI_UNATTENDED_MAX_TOKENS` |
   | 墙钟时间 | 30 分钟 | `--timeout` / `HAOCLI_UNATTENDED_TIMEOUT` |

   超出后走现有“关工具、整理已完成部分”，状态 `partial`，退出码 3
4. 品牌 R0：审查 GitHub draft PR #2（只改 README 和 pom 的显示名），由用户合并（2026-11-27 用户改选范围更大的 `chore/hao-brand-migration`，合并为 `56cbd43`）
5. 验收：公共结束条件 + Steering 自动化测试（`AGENTS.md` 验证命令表 Steering 那一行）全过

#### P0 进展（2026-11-26，已完成：`0396dcf` 第 13 轮用户本机编译通过，quick 1797 通过 / 0 失败 / 5 跳过，见 `docs/reviews/2026-11-26-macos-test-round13.md`；品牌 R0 已合并 `56cbd43`）

- 第 1 项完成：分支 `feat/harness-runtime`，`9ff2461` 是第 11、12 轮改动（和第 12 轮报告测的内容一致：混在一起的 5 个代码文件按 P0 改动逐处还原后单独暂存），`4fabe82` 是本计划和 3 份 ADR
- 第 2 项：`RuntimeApiServer` 记下本轮的 `TurnFinished`，按上表发结束事件；runner 不发事件（比如测试里的 lambda）时按 completed 处理。`AgentEventJson.status` 改成 public
- 第 3 项：新增 `agent/RunLimits`（三个上限 + 环境变量解析 + 时长写法：纯数字按秒，可带 s / m / h）。`AgentBudget` 加时间上限（`TIME_LIMIT_EXCEEDED`），`fromSystemProperties(RunLimits)` 和系统属性取更严的那个。`AgentLoop.Turn` 带上限，`Agent.setRunLimits`；`HeadlessRunner`、`UnattendedTaskRunner` 设置上限，交互命令行仍不限
  - 和上表的差别：`--max-turns` / `--max-tokens` / `--timeout` 只加在 `-p` 上；Runtime API（`serve`）和后台任务只读环境变量 `HAOCLI_UNATTENDED_*`，不改 `serve` 的启动参数
  - 时间只在每次模型请求之前检查，正在进行的单次请求或工具不打断；到达上限后还有一次不带工具的收尾请求，所以实际请求数最多是上限加 1
  - Plan / Team 还用旧的 `AgentBudget.fromSystemProperties()`，等丙阶段迁进内核后自动带上限
- 第 4 项（品牌 R0）：已合并 `56cbd43`
- 测试：新增 `RunLimitsTest`（4 个）；`AgentBudgetTest` +2（时间上限用假时钟、上限只收紧不放宽）；`HeadlessOptionsTest` +2（参数优先于环境变量、非法和重复参数报错）；`HeadlessRunnerTest` +1（`--max-turns 1` → `partial`、退出码 3、收尾请求不带工具）；`RuntimeApiServerTest` +1（模型失败只收到 `turn.failed`，没有 `message.delta` 和 `turn.completed`）
- 文档：`docs/specs/headless-mode.md` 新增“上限”一节，`docs/phase-20-runtime-api.md` 写结束事件规则，`README.md`、`.env.example`

第 13 轮报告的跟进（另一个提交）：
- 实机确认失败轮次只发 `turn.failed`；非法参数和环境变量都以退出码 2 报错；harness 11 个场景结果和上轮一致
- 报告说“`--max-turns 1` 到 partial 的端到端没有用例”：其实有，`HeadlessRunnerTest.maxTurnsStopsToolUseAndReportsPartialWithExitCodeThree`（该类第 13 轮是 9 个用例）
- 报告提醒“时间上限不是硬墙钟”“Plan / Team 不受上限约束”：在 `RunLimits` 注释和 `docs/specs/headless-mode.md` 里写明；工具自己有超时（命令 60 秒、一批 90 秒），最坏是上限再加一次请求和一批工具
- 补了“交互命令行默认不限”的用例（`RunLimitsTest.interactiveAgentStartsUnlimitedUntilACallerSetsLimits`）
- 成功路径的 `agent_status` 只有假模型覆盖；报告提出可以用真实模型跑一次几十 Token 的小任务确认，属于调用真实模型，等用户决定

当时用户本机要做的事（JDK 21；新增了类，要 clean）：

```bash
git rev-parse --short HEAD        # 报告第一行写这个提交号
mvn clean test -DskipTests=false -Dtest='RunLimitsTest,AgentBudgetTest,HeadlessOptionsTest,HeadlessRunnerTest,RuntimeApiServerTest,UnattendedTaskRunnerTest,AgentLoopTest,HarnessScenarioSuiteTest,AgentSecurityRegressionTest'
mvn test -Pquick; bash tools/test-failures.sh                                  # 应该没有输出
mvn test -DskipTests=false -Dtest='ToolRegistryTest,PathGuardTest,CommandGuardTest,AuditLogTest'   # 安全专项
mvn test -DskipTests=false -Dtest='*Steering*Test,TurnInputManagerTest,InputCoordinatorTest,TerminalInputGateway*Test,JLineInputAdapterTest,InlineApprovalGatewayTest,AgentBudgetFinalizationTest,ToolRegistryToolStartGateTest'
```

### 甲 契约

这一阶段定下来的东西会成为对外接口，之后改就是破坏兼容，所以放在最前面。分多个小提交，每批用户跑一次测试。

1. 统一 `Tool` 接口：名称、参数 Schema、是否只读可并行、`execute` 返回结构化结果。工具状态：`SUCCESS`、`FAILED`、`DENIED`、`TIMEOUT`、`CANCELLED`、`SKIPPED`、`UNKNOWN`，另带失败码、是否可重试
2. 迁移顺序：`read_file` 完整切片 → 只读工具 → 写文件工具 → 命令 → 网络和浏览器 → 其余。迁到哪个工具就把它从 `ToolRegistry` 搬成独立类（比如 `ReadFileTool`）。MCP 工具按协议的 `isError` 映射。全部迁完、回放一致后删掉 `looksLikeFailureText`
3. 调度层职责不下放：并行安全名单、参数校验（`ToolArgumentValidator`）、不可信数据包装（`ToolResultBoundary`）、大结果落盘（`ToolResultOffloader`）、策略检查仍在调度层统一做
4. 一轮的运行结果定成正式类型：执行状态（完成 / 部分 / 失败 / 取消）和部分完成的原因
5. 事件补 `schema_version` 和单调递增的 `sequence`；保证每个开始事件都有对应的结束事件（包括异常路径）；对外 JSON 只加字段不改字段
6. 模型接口：`AgentLoop` 只依赖厂商无关的模型接口（第 1 阶段已有的新类型），还没迁移的厂商客户端走适配器
7. 进程内入口 `HaoRuntime.builder()`：`.model(...)`、`.tool(...)`、`.contextProvider(...)`、`.onEvent(...)`、`.limits(...)`、`.permissions(...)`，`build().run(task)` 返回运行结果。只做显式注册（`docs/adr/ADR-0001-architecture-boundaries.md`）。默认只读
8. 依赖方向测试（ArchUnit 或扫描 import）：内核包不准引用 `cli`、`render`、`mcp`、存储实现、评测
9. 独立 Java 示例：只用进程内入口，注册一个自定义工具和一个上下文提供者，跑一个假模型任务
10. 验收：18 个内置工具都实现 `Tool` 接口；`looksLikeFailureText` 删除；示例能跑；依赖测试通过；`HarnessScenarioSuiteTest` 的场景结果不变（预期变化的要在提交说明里列出）

#### 甲阶段设计（2026-11-26，用户已确认：3 件事都按推荐）

现状（按代码核对）：
- 工具是 `ToolRegistry` 里的嵌套记录 `Tool(name, description, parameters, ToolExecutor)`，`ToolExecutor` 收 `Map<String,String>`、返回 `String`；只有 `web_search`、`execute_command` 直接返回 `ToolOutput`。嵌套的 `ToolRegistry.Tool` 在类外没有人用
- 调度在 `doExecuteToolLocked`：参数校验 → 浏览器检查 → 启动闸门 → 执行 → 审计；`PolicyException` 变成“🛡️ 策略拒绝”，其他异常变成“工具执行失败”；返回纯文字的工具由 `classifyTextOutput` 按前缀猜成败
- 拒绝已经是 `ToolOutput.failure`：`HitlToolRegistry` 的 `[PERMISSION]`、`[AUTO]`、`[HITL]`，`HeadlessToolRegistry` 的“无界面模式策略拒绝”。只是没和普通失败区分开
- 读 `successful()` 的有 20 来个类，一半以上在评测包里（`BenchmarkFailureClassifier`、`F3ToolResultAudit` 等）

数据类型：

```java
public enum ToolStatus { SUCCESS, FAILED, DENIED, TIMEOUT, CANCELLED, SKIPPED, UNKNOWN }

// 已有的 ToolOutput 演进，不另起新类型；successful() 改成按状态算：status == SUCCESS
public record ToolOutput(String text, List<ContentPart> imageParts, ToolStatus status,
                         List<String> discoveredUrls, FailureCode failureCode, boolean retryable) {
    // 旧的 boolean 构造函数全部保留：true → SUCCESS，false → FAILED
    public static ToolOutput text(String text);                       // SUCCESS
    public static ToolOutput failure(FailureCode code, String text);  // FAILED
    public static ToolOutput denied(String text);                     // DENIED
    public static ToolOutput skipped(String text);                    // SKIPPED
    ...
}
```

`ToolExecutionResult`、事件 `ToolCallFinished` 同样加 `status`，旧构造函数保留；`-p` / Runtime API 的 JSON 在 `success` 旁边加 `"status"`（只加字段）。`FailureCode` 按迁移需要逐个加（比如 `NOT_FOUND`、`INVALID_ARGUMENT`），不一次列全。

谁定哪个状态：

| 状态 | 来源 |
|---|---|
| `DENIED` | 调度层的 `PolicyException`；HITL 拒绝、权限规则拒绝、auto 审查未放行；无界面白名单拒绝；本轮工具策略拒绝 |
| `SKIPPED` | 用户在确认框里选“跳过”；回复被截断时不执行的调用 |
| `CANCELLED` | 用户取消（ESC、取消令牌） |
| `TIMEOUT` / `UNKNOWN` | 超时：只读工具记 `TIMEOUT`；可能有副作用的工具（写文件、命令、MCP 等）记 `UNKNOWN`，表示“不知道副作用发生没有”，不能盲目重试（见决定 2） |
| `FAILED` | 其余失败；迁移过的工具自己返回，没迁移的仍由前缀兜底 |
| `SUCCESS` | 工具自己报告 |

`successful()` 的含义不变：除 `SUCCESS` 以外都是失败。拒绝、超时这些今天本来就算失败，所以评测包读到的成败不变，只是多了一个更细的状态。

`Tool` 接口（也是 SDK 里自定义工具用的接口，放 `com.haocli.tool`）：

```java
public interface Tool {
    String name();
    String description();
    JsonNode parameters();                      // JSON Schema，调度层照旧先校验
    default boolean readOnly() { return false; } // true 才能并行（取代按名字查 PARALLEL_SAFE_TOOLS）
    ToolOutput execute(ToolArguments args, ToolContext context) throws Exception;
}

// 包着校验后的 Map<String,String>，单值参数仍由工具自己解析（评测复算依赖这套语义）
public final class ToolArguments { String string(String name); int integer(String name, int fallback);
                                   boolean bool(String name, boolean fallback); boolean has(String name); }

public interface ToolContext {
    Path projectRoot();
    Path resolvePath(String raw);   // 走 PathGuard，越界抛 PolicyException → DENIED
    boolean cancelled();
}
```

- 嵌套记录 `ToolRegistry.Tool` 改名 `LegacyTool`，做成 `Tool` 的适配器：执行结果仍走前缀兜底。迁一个工具删一条 `LegacyTool`
- 调度层不变：参数校验、浏览器检查、启动闸门、审计、不可信数据包装、大结果落盘都还在 `ToolRegistry`；工具抛 `PolicyException` → `DENIED`，抛别的异常 → `FAILED`
- 新工具类放 `com.haocli.tool.builtin`（比如 `ReadFileTool`）；它们需要的依赖（PathGuard、LSP、快照）通过 `ToolContext` 或构造参数传入，不反向依赖 `ToolRegistry`
- 给模型看的文字一律不改，只改状态；这样提示词和评测里按文字比对的地方都不受影响

提交顺序（每个提交用户跑一次测试）：
1. 甲-1 状态：`ToolStatus`，`ToolOutput` / `ToolExecutionResult` / `ToolCallFinished` 加状态，调度层和 HITL / 无界面拒绝改成显式状态，JSON 加 `status`。不搬任何工具
2. 甲-2 `read_file` 切片：`Tool`、`ToolArguments`、`ToolContext`、`LegacyTool` 适配器、`ToolRegistry.register(Tool)`、`ReadFileTool`
3. 甲-3 及以后：只读工具 → 写文件工具 → 命令 → 网络和浏览器 → 其余；最后删 `looksLikeFailureText`
4. 然后：运行结果类型、事件 `schema_version` / `sequence`、模型接口、`HaoRuntime`、依赖测试、示例

#### 甲-1 进展（2026-11-27，已通过：`bf10615` + 沙箱修复 `b5d06ca`，quick 1803 / 0 失败 / 4 跳过，针对性 9 类、安全专项、HITL 全过，见 `docs/reviews/2026-11-27-macos-test-round14.md`；评测包未跑）

- 新增 `tool/ToolStatus`；`ToolOutput` 的 `successful` 字段换成 `status`（再加 `retryable`），`successful()` 按状态算，旧的 boolean 构造函数全部保留；新增 `denied` / `skipped` / `cancelled` / `unknown` / `of` / `withText` / `withStatus`；`FailureCode` 加 `INVALID_ARGUMENT`
- `ToolExecutionResult` 同样换成 `status`，带 boolean 的旧构造函数保留；新增 `withResult`（大结果落盘时不丢状态）
- 调度层：取消 → `CANCELLED`；`PolicyException` 和 MCP 注册变化 → `DENIED`；参数校验失败 → `FAILED` + `INVALID_ARGUMENT`；启动闸门拦下 → `SKIPPED`；命令超时被强制终止 → `UNKNOWN`；并行批次超时按工具是否只读分 `TIMEOUT` / `UNKNOWN`
- 没迁移的工具：`legacyStatus` 按前缀推断（`🛡️` / `[HITL]` / `[AUTO]` → `DENIED`，`[HITL] 操作已被跳过` → `SKIPPED`，“用户取消了” → `CANCELLED`，“工具执行超时” → `TIMEOUT`，其余失败前缀 → `FAILED`）
- `HitlToolRegistry` 的 `[PERMISSION]`（17 处）、`[AUTO]`、`[HITL] 操作已被拒绝` → `ToolOutput.denied`，`[HITL] 操作已被跳过` → `ToolOutput.skipped`；`HeadlessToolRegistry` 白名单拒绝、`TurnToolPolicy` 本轮策略拒绝 → `DENIED`；`LoadedSkillMessages` 成败没变时保留原状态
- 事件 `ToolCallFinished` 加 `status`（4 参数旧构造函数保留）；`-p` / Runtime API JSON 的 `tool_call_finished` 加 `"status"`
- 给模型看的文字一个字都没改；`successful()` 对所有已有路径的结果和以前一样（拒绝、超时、取消、跳过本来就算失败）
- 测试：新增 `ToolStatusTest`（5 个：旧构造函数映射、前缀推断、超时分两种、调度层越界 → `DENIED` 且文字不变 / 缺参数 → `INVALID_ARGUMENT` / 取消 → `CANCELLED` 且文字不变、落盘保留状态）；`HeadlessRunnerTest` 断言 JSON 的 `status`（成功 → `success`，本轮策略拒绝 → `denied`）

用户本机要做的事（JDK 21；新增了类，要 clean）：

```bash
git rev-parse --short HEAD
mvn clean test -DskipTests=false -Dtest='ToolStatusTest,HeadlessRunnerTest,AgentLoopTest,TerminalEventRendererTest,HarnessScenarioSuiteTest,RuntimeApiServerTest,ToolRegistryTest,UnexecutedToolCallNoticeTest,AgentRunawayReminderTest'
mvn test -Pquick; bash tools/test-failures.sh                                  # 应该没有输出
mvn test -DskipTests=false -Dtest='com.haocli.hitl.*Test,PermissionCommandTest,SessionModeControllerTest'   # HITL
mvn test -DskipTests=false -Dtest='ToolRegistryTest,PathGuardTest,CommandGuardTest,AuditLogTest'           # 安全专项
mvn test -Pbenchmark; bash tools/test-failures.sh > /tmp/after.txt              # 评测包：对比改动前的 91 个已知失败，不能多
```

需要用户决定的 3 件事（已决定）：
1. 沿用并演进 `ToolOutput`，还是新建 `ToolResult` 类型（推荐演进：它已经带图片、网址来源和失败码，新建要在所有调用处换类型）
2. 超时分两种状态：只读工具 `TIMEOUT`、可能有副作用的工具 `UNKNOWN`（推荐：和 ADR-0002 的定义一致，`UNKNOWN` 提醒“先核实再重试”）
3. `read_file` 的 `offset` 超过文件行数，今天算成功（文字是“offset 超出范围”），迁移后改成 `FAILED` / `INVALID_ARGUMENT`（推荐改：如实报告是这一阶段的目的；给模型的文字不变）。这会让少数场景的成功 / 失败计数变化，会在提交说明里列出

#### 甲-2 进展（2026-11-27，`read_file` 切片，已通过：macOS JDK 21，针对性 10 类 102 个、quick 1810 / 0 失败 / 4 跳过、HITL + 安全 254 个全过；评测包未跑）

- 新增 `tool/Tool`（`name` / `description` / `parameters` / `readOnly` / `execute(ToolArguments, ToolContext)`）、`ToolArguments`（解析规则和原来的 `parseInt` / `parseBoolean` 一致：坏值回落默认值，`true` / `1` / `yes` 算真）、`ToolContext`（`projectRoot`、`resolvePath` 走 PathGuard、`cancelled`）
- 嵌套记录 `ToolRegistry.Tool` 改名 `LegacyTool`（包内可见）并实现 `Tool`：执行结果仍按前缀推断；`tools` 表的类型换成 `Tool`
- `ToolRegistry.registerTool(Tool)`：同名覆盖，拒绝空名和 `mcp__` 前缀。并行仍看 `PARALLEL_SAFE_TOOLS` 名单，改成看 `readOnly()` 留到后续一步
- `tool/builtin/ReadFileTool`：文字和以前逐字相同；文件不存在 → `FAILED` + `NOT_FOUND`（`FailureCode` 新增），不是普通文件、`offset` 超出行数 → `FAILED` + `INVALID_ARGUMENT`（决定 3），读取异常 → `FAILED`；越界照旧抛 `PolicyException` → `DENIED`。`load_skill` 读参考文件复用 `ReadFileTool.read(...)` 的文字
- 行为变化：`read_file` 的 `offset` 超出行数，`successful()` 从 true 变成 false。harness 11 个场景不受影响；评测包是否受影响没跑（评测暂停）
- 已知例外：重写旧版 `executeTool(String)` 的子类（`isLegacyExecuteToolOverride`）仍按文字推断，看不到 `offset` 越界的 `FAILED`
- 测试：新增 `ReadFileToolTest`（7 个：全文 / 分页文字不变、`offset` 越界、不存在 / 目录、批量路径保留状态、`readOnly` 和上下文、自定义工具注册、参数解析）

### 丙 统一执行

1. 丙 1 等价迁移：Plan 的每个任务节点、Team 的每个 worker 改为调用 `AgentLoop`，删掉各自的循环。先补等价回放测试再改。必须保住：
   - planner 和 reviewer 的请求不给工具（评测审计约定）
   - `parsePlan` 提取代码块的正则和 `src/main/resources/benchmark/e1_replay.py` 完全一致
   - 重规划规则（完成度低于 50% 才重规划，最多 `HAOCLI_PLAN_MAX_REPLANS` 次，到上限后剩下的标 SKIPPED）
   - reviewer 只认 JSON 里的 `approved: true`，失败的 reviewer 阻断依赖它的任务
   - Plan 审阅按键交互；Plan 并行任务和 Team worker 各用一份网址策略副本
2. 丙 2 子 Agent 工具：主 Agent 像调用工具一样派生子 Agent（受限工具、独立上下文、独立预算，Token 计入父任务的总上限），结果作为工具结果返回；定义放 `.haocli/agents/*.md`。子 Agent 的权限只能比父 Agent 少，走同一个 `executeTools()` 和策略，网址策略继承父任务的副本。Team 之后改为编排多个子 Agent
3. Steering 真实终端验收（ReAct 和 Plan 一起），提供手工检查清单
4. 验收：Plan / Team 现有测试 + 等价回放通过；截断保护、预算、停滞检测、事件在三种执行方式里同义

### 目标循环

1. 只在 `-p` 和 Runtime API 提供：`--verify "命令"` / 请求体字段 `verify`
2. 一轮结束后执行验收命令（走同一套白名单和沙箱，`--verify` 里的命令视为用户逐字授权）；退出码 0 算通过；否则把截断后的输出作为不可信数据还给模型再跑一轮，最多重试 2 次，计入无人值守总预算
3. 新增 `goal_status`：`unverified`（没给验收命令）/ `passed` / `failed` / `blocked`（预算用完或命令不允许执行）；事件、JSON、Runtime API 都带上
4. 不用模型当裁判；交互命令行不加
5. 验收：假模型回放覆盖“一次通过 / 重试后通过 / 重试用完 / 命令被拒 / 预算用完”

### 安全

1. 统一策略入口：把散在 `ToolRegistry`、`HitlToolRegistry`、`HeadlessToolRegistry`、微信通道里的检查合成一条链（本轮工具策略 → 权限规则 → 人工确认 → 参数 / 路径 / 命令检查 → 沙箱 → 审计），每次决定输出决定编号和原因
2. 沙箱（`docs/adr/ADR-0003-sandbox-escalation.md`）：交互默认 `auto`，只读访问 `~/.m2`、`~/.gradle` 等依赖缓存，网络关闭；命令看起来是被沙箱挡住时，弹确认框问“在沙箱外重跑这一次”，人批准才重跑并写审计；审查模型不能代批；判断不出是不是沙箱导致的就不提示升级。无人值守入口 `required`，沙箱不可用就拒绝，绝不升级
3. 微信通道并进统一入口，保持默认拒绝
4. Linux（bubblewrap）和 macOS（Seatbelt）分别验证；Windows 不声称同等隔离
5. 验收：安全专项全过；新增决定记录、沙箱升级、无人值守拒绝的测试；路径测试含真实路径和符号链接

### 乙 拆 Main

1. 组装、会话控制、命令注册表、前端分开；斜杠命令改为注册表
2. 验收：`Main.java` 500 行以内；命令解析和 TUI 测试（`-Pphase16-smoke`）不退化

### 上下文

1. 增强 `/context`：分类显示 system、项目规则、记忆、工具定义、历史、工具结果、输出预留各占多少；有真实用量时才显示缓存命中
2. `ContextBlock`（来源、可信度、作用范围、Token 估算、优先级、是否固定）替换 `PromptAssembler` 里拼接的字符串；外部数据（网页、MCP、日志）不能进高优先级 system（2026-11-27 已实现：`PromptAssembler.assembleBlocks` 按块装配，外部数据单独成段并标明不可信，`/context` 按来源显示各块 Token；HAO.md 超预算时渐进加载：放得下的原样放，其余给“文件 + 行号 + 标题”目录，模型按需 read_file）
3. 已有三层（大结果落盘、清理旧工具结果、摘要）收进统一压缩流程，不变量测试：工具调用和结果成对、每次改动写 `view_checkpoint`、失败保留旧视图、强制规则和待执行工具不交给摘要
4. 模型报上下文超长：重新估算 → 紧急压缩一次 → 还不够返回 `partial`，保留原始历史（2026-11-27 已实现：`LlmErrors.isContextOverflow` 识别、`TurnHook.onContextOverflow` 紧急压缩，先做不调用模型的三层，减不动才做摘要）
5. “历史裁剪”“局部折叠”两层只写设计，不实现，由下面的实验结果决定做不做（2026-11-27 用户改为先全部实现再评测：第 2 层历史裁剪 `HistoryTrimmer`、第 3 层局部折叠 `LocalFolder` 已实现，各自可关，见 `PLAN-20261127-terminal-bench-eval.md`）
6. 真实模型对照实验：先写预登记（任务清单 10–20 个自己仓库的真实小任务、验收命令判定成败、基线和候选提交、模型 DeepSeek V4 Flash、重复次数、上限 50 元），用户批准后才跑；每次跑前报预计花费，超限立即停；结果不论好坏如实记录，不挑结果、不自动重试。Native AgentBench 继续暂停，不混用

### 拆模块

| 模块 | 内容 | 不准依赖 |
|---|---|---|
| `hao-core` | 接口和数据类型（模型接口、`Tool`、工具结果、`AgentEvent`、运行结果）、`AgentLoop`、预算、取消、策略接口 | 其他三个模块、SQLite、MCP、终端 |
| `hao-runtime` | 进程内入口、默认内置工具、模型客户端、策略和沙箱实现、会话存储、上下文装配；可选能力（Plan、Team、MCP、RAG、记忆、LSP）各占独立包，由依赖测试隔离 | `hao-cli`、`hao-eval` |
| `hao-cli` | `Main`、inline / Lanterna 界面、`-p`、Runtime API 服务、微信 | `hao-eval` |
| `hao-eval` | 评测 | 不被任何模块依赖 |

坐标用 `hao-*`，包名保持 `com.haocli`。验收：各模块单独 `clean build` 和测试；示例只依赖 `hao-runtime`；关掉所有可选能力仍能读、改、验证一个小 Java 项目。

### 收尾

1. 品牌 R1、R2：已取消。2026-11-27 用户要求全量改名（包名、类名、文件名、配置目录、环境变量、`HAO.md`），并选择不兼容旧名字、不迁移旧数据
2. README 架构说明、快速开始、示例、已知限制、发布说明
3. 发行门槛：代码和证据同一提交；`clean build` + quick + 安全专项；最小 CLI 和 SDK 示例；新旧配置、会话、API 兼容；无人值守的失败和取消语义一致；文档列出已知限制和安装 / 回滚方式

## 5. 一直有效的规则

- 在 `feat/harness-runtime` 分支上做，`main` 只在阶段验收后合并。每次提交前先给用户看文件列表和提交说明，用户同意才提交；推送由用户做
- 测试在用户 Mac（JDK 21）上跑；跑不了的标“未验证”，不说成“通过”
- 上下文阶段的实验预登记批准之前，不调用真实模型、不跑 Docker、不写评测代码
- 冻结：Lanterna 全屏界面、微信通道、浏览器只修崩溃和安全问题，不加新功能；README 标 Lanterna 为实验性
- 对外文档和简历不引用基于 Claude Code 泄露 source map 的第三方分析；分层设计的依据只写 Anthropic 公开文章和官方文档
- 名字一律用 haocli 系列（`com.haocli`、`~/.haocli`、`HAOCLI_*`、`HAO.md`），不加旧名字别名

## 6. 待办池（这条路线不做）

- 包名和 Maven 坐标整体迁移（R3）、弃用旧名（R4）
- ServiceLoader 自动加载第三方扩展 jar
- 模型当验证裁判；交互命令行的目标循环
- 历史裁剪、局部折叠的实现（等实验数据）
- 可选能力各自单独成模块
- 会话树和 `/rewind`

## 7. 风险

| 风险 | 处理 |
|---|---|
| 甲阶段太大 | 拆成按工具类别的小提交，每批单独验收；`looksLikeFailureText` 兜底到最后 |
| 迁移 Plan / Team 改坏评测审计约定 | 先补等价回放；planner / reviewer 不给工具、`parsePlan` 正则作为显式测试 |
| 沙箱升级被滥用 | 只有人能批准、一次一批、写审计；无人值守永不升级；判断不准就不提示 |
| 真实实验超支 | 预登记写死上限 50 元，跑前报预计花费，超限即停 |
| 文件系统慢导致测试证据和代码对不上 | 报告第一行写提交号；没提交的改动不算验收 |

## 8. 和原文档的差异

| 原文档 | 本计划 | 原因 |
|---|---|---|
| 12 周排期 | 只按阶段，不排期 | 用户决定 |
| SDK 在 P6（第 9–10 周）才出现 | 进程内入口和示例提前到甲阶段，模块最后拆 | 产品方向不能最后才看到；入口反过来约束内核边界 |
| P1 先拆 Main | 拆 Main 排在安全之后 | 进程内入口不经过 `Main`，拆它不挡任何事；避免同一段组装代码改两遍 |
| 只给 SSE 追加 `agent_status` / `goal_status` | 失败改发已有的 `turn.failed`；`goal_status` 随目标循环再加 | 只加字段老客户端看不到，仍会把失败当成功 |
| `ToolRegistry` 拆分在安全阶段 | 工具跟着甲阶段逐个拆 | 每个工具只动一次；内置工具用同一个 `Tool` 接口验证 SDK 设计 |
| 目标循环可插拔验证器 | 只做确定性验收命令 | 可复现、不花 Token；模型裁判是评测里最容易被质疑的部分 |
| ServiceLoader 发现本地扩展 | 只做显式注册 | 嵌入方自己控制 classpath；命令行不执行第三方 jar，第三方扩展走 MCP 独立进程 |
| 五层压缩全部实现 | 只收拢已有三层 + 不变量；另两层等实验数据 | 评测暂停期间无法证明收益 |
| 品牌 R0–R4 | 原定做 R0、R1、R2；2026-11-27 用户改为一次全量改名、不兼容旧名字 | 用户决定 |
| 模块 6 类以上 | 4 个模块 | 一个人维护，模块越多构建和 CI 成本越高 |
