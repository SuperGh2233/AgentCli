# PaiCLI Mid-turn Steering · Phase 0 静态基线审查

> 审查日期：2026-10-20  
> 来源：`docs/mid-turn-steering-implementation-plan.md`  
> 阶段：**Phase 0 - 静态审查完成；执行基线尚未完成**  
> 操作范围：只读取本机 `paicli-main` 的源文件与测试；仅新增本审查报告，**未修改业务源码、未调用模型、未启动 Codex Agent**。

## 1. 本轮结果与准入状态

| 项目 | 状态 | 依据及说明 |
|---|---|---|
| 仓库可访问 | 已确认 | GPT-Free-Mac 的 `projects/paicli-main` 可读写 |
| 开发约束已阅读 | 已确认 | `AGENTS.md` 与 steering 设计计划 |
| ReAct / Plan / Team 架构 | 已确认 | `Agent.java`、`PlanExecuteAgent.java`、`AgentOrchestrator.java` |
| CLI ESC / HITL 输入竞争现状 | 已确认 | `Main.runWithCancelSupport`、`readEscCancel`、`TerminalInputLock` |
| 现有自动化测试清单 | 已确认 | JUnit 测试目录及部分测试实现 |
| Java 编译目标 | 已确认 | `pom.xml` 的 source/target 为 Java 17 |
| 仓库 HEAD / 当前分支 / 工作区是否干净 | **未能确认** | 文件插件禁止访问 `.git` 隐藏目录，且无 Git status 命令 |
| `mvn test -Pquick` 当次实际结果 | **未执行** | Mac 文件插件无 Shell / Maven 执行入口；不能拿旧报告代替 |
| Phase 1 启动条件 | **待确认** | 缺 HEAD、dirty 文件列表、当前测试失败清单；按计划在验收后才进入 |

此报告只证明**已看到的代码**，不声称当前本机编译成功、测试通过，或新 Steering 功能已实现。

## 2. 关键事实与风险

### A. CLI 输入是第一阻塞点（高）

- `cli/Main.java`：主 REPL 使用 JLine `LineReader.readLine(...)`。
- 执行中 `runWithCancelSupport` 将 Agent 放在 Future/Executor 中；主线程进入 raw mode，轮询 `pollEscCancel → readEscCancel`。
- `readEscCancel` 遇到非 ESC 输入会读取、丢弃残余终端字节，而不是传给完整的输入编辑器。**直接增加 Steering Queue 不会让执行中的输入变得可用**。
- `util/TerminalInputLock.java` 已用锁尽量避免 ESC 轮询与计划审阅/HITL 抢终端，但它不是统一读入协调器。
- 改造方案：**单一终端读取所有者** + 输入模式路由；不能同时开两个 Reader。必须用 PTY 和真实 Mac 终端验证方向键、中文输入、括号粘贴、ESC、审批。

### B. ReAct Loop 是适当的首个接入点（中）

- `agent/Agent.java` 在每轮 LLM 请求前调用 `maybeCompactHistory()`，再决定本轮工具暴露、`chat` 和工具执行。
- 模型的 tool calls 被加入 History，随后 `executeToolCalls` 返回结果再追加 tool-result。
- 可在 **所有 tool-result 写入历史后、下一次 LLM 请求前** 注入可信的 Steer 消息。
- **必须**把最后一次队列检查和 Turn 完成判定做成原子操作，避免模型刚输出终结答案、用户同时发送补充时丢消息。
- `LoadedSkillMessages`、历史压缩与 `TurnToolPolicy` 都会影响上下文，不能通过 UI 线程直接修改 `conversationHistory`。

### C. 权限与来源控制需要扩展而不是覆盖（高）

- `Agent.runInternal` 在 Turn 初始用 `submittedUserInput` 构造 `TurnToolPolicy`；执行过程中目前保持局部策略对象。
- 输入新增后若只是 `conversationHistory.add(userMessage)`，模型看得到新约束，但**工具权限策略未必同步生效**。
- 修复前必须定义可信用户消息与工具结果的来源边界，保证“不联网”“只允许特定 URL”等限制仍适用；不能通过模型输出给自己授予权限。
- 首版仍让已开始的工具按当前取消协议完成，先保证结果完整、审批不绕过，再考虑更积极的未启动工具暂停机制。

### D. 取消令牌要按 Run 隔离（高）

- `runtime/CancellationContext.java` 当前含 `static AtomicReference<CancellationToken> CURRENT` 与 `InheritableThreadLocal`。
- 对同时有前台 CLI 与后台任务的情况，仅凭全局 `CURRENT` 可能出现错误归属风险；需先添加并发隔离测试，不在 Phase 0 直接重构。
- `runtime/task/DurableTaskManager.recoverRunningTasks()` 把 RUNNING 重新排为 ENQUEUED；有副作用工具可能因此被重新执行，**不可称为精确断点续跑**。持久 Steering 队列延后 Phase 5。

### E. Plan/Team 审阅原有行为需保留（高）

- `PlanExecuteAgent.java` 已支持计划前审阅与 `Planner.revisePlan`；不能把计划审阅期间的 `I` / `Enter` 改成 Steer。
- `AgentOrchestrator.java` 可并发分配 Worker；后续 Steering 需由 Orchestrator 统一消费，再版本化广播给未启动的下游节点。
- 不能只因 DAG 两节点无依赖就默认其文件写入完全独立。

## 3. 当前测试入口（只确认存在，不代表本轮已通过）

- CLI：`cli/MainInputNormalizationTest.java`、`CliCommandParserTest.java`、`PlanReviewInputParserTest.java`、`MainRunModeTest.java`。
- History：`history/ConversationLedgerTest.java`、`agent/AgentConversationLedgerTest.java`、`agent/AgentSessionResumeTest.java`。
- Agent / Plan：`agent/PlanExecutionControlTest.java`、`PlanExecuteAgentTest.java`、`AgentOrchestratorTest.java`。
- 取消：`runtime/CancellationContextTest.java`。
- 审批：`hitl/TerminalHitlHandlerTest.java`、`RendererHitlHandlerTest.java`、`SwitchableHitlHandlerTest.java`。
- 工具安全：`tool/TurnToolPolicyTest.java`、`ToolArgumentValidationTest.java`、`ToolRegistryTest.java`。

`AGENTS.md` 已列出历史失败或环境不兼容项：部分 `eval.benchmark.*`、`TerminalMarkdownRendererTest`，以及新版本 macOS 上的 `ToolRegistryTest.macSandboxAllowsDevelopmentRuntimesAndDevNull`。后续必须保存**失败测试名称和错误摘要**，不能用“失败数相同”代表无回归。

## 4. Phase 1–3 用的 16 条确定性场景

| ID | 场景 | 目标阶段 | 推荐方式 | 验收条件 |
|---|---|---|---|---|
| T01 | LLM 正在响应时收到 Steer | 3 | FakeLLM + latch | 不修改在途请求，新消息仅在下一请求出现一次 |
| T02 | read_file 运行中收到 Steer | 3 | BlockingTool | 完整工具结果先入 History，后再注入 Steer |
| T03 | 最终答复与 Steer 同时到达 | 1、3 | 原子关闭竞态测试 | 无静默丢失，明确接收或 stale |
| T04 | 先后 Queue 两条消息 | 1、3 | FIFO unit test | 新 Turn 顺序执行，旧 Turn 不包含它们 |
| T05 | HITL 等待 y/n 时输入 | 2 | PTY / 假输入 | 审批优先，输入不被误判 Steer |
| T06 | ESC 与 Queue 并发 | 1、2 | 伪终端及 latch | 取消当前 Turn，Queue 留存且暂停 |
| T07 | Steer 包含 URL 与禁止联网 | 3 | Fake policy tools | 只有可信用户文本可改权限，外部内容不得提升权限 |
| T08 | 多个 tool_call 同时返回 | 3 | Fake parallel tools | 每个 call ID 配套 tool-result，不漏不重 |
| T09 | 同 ID 消息重复提交 | 1 | unit test | 只存一条，交付一次 |
| T10 | 收到 Steer 后触发压缩 | 3 | Fake compactor | 未纳入模型请求的约束保留原文 |
| T11 | Plan 修改尚未执行节点 | 4 | Fake planner + DAG | 不重跑已完成节点，无环 |
| T12 | Team 多 Worker 接收新约束 | 5 | deterministic mock | 版本单调、无重复广播 |
| T13 | 中文、方向键、粘贴与 ESC | 2 | PTY + 手工检查 | 无抢读、乱码、错误取消 |
| T14 | 模型失败/超时 | 3 | Fake LLM throws | 消息状态明确，待交付可重试 |
| T15 | 文件修改后用户发取消 | 3、5 | temp repo + fake tool | 不能声称自动回滚，可展示已发生的变更 |
| T16 | 重启时 pending 消息 / 未决工具 | 5 | sqlite restart test | 用户消息不丢，写入工具不盲重放 |

不要为 Phase 1 提前运行真实 Provider、Native AgentBench、Docker 或昂贵的端到端评测。

## 5. 待补的实际命令和记录格式

**仅在有权限的 Mac 本地终端执行以下轻量命令**，不要启动 Codex CLI / Agent。执行前如工作区已有改动，先记录，不自动清理。

```bash
cd /path/to/paicli-main

git rev-parse --short HEAD
git branch --show-current
git status --short

java -version
mvn -version
mvn test -Pquick
```

如只想先验证相关子集，可增加：

```bash
mvn test -Dtest=MainInputNormalizationTest,PlanReviewInputParserTest,CancellationContextTest,ConversationLedgerTest,AgentConversationLedgerTest,PlanExecutionControlTest,TerminalHitlHandlerTest -DskipTests=false
```

将输出保存为：时间戳、命令、exit code、失败测试名称及首个根因、Java/Maven 版本、HEAD、dirty 文件清单。**不要将 .env / API Key / 原始隐私会话记录写进报告。**

## 6. Phase 0 结论与下一阶段准入

1. **源码边界已明确**：下一阶段最小实现应从 `runtime/input` 的纯状态机与单元测试开始，不直接侵入 `Main.java`。
2. **当前不满足完整 Phase 0 验收**：缺少本地 HEAD / status 和当次 Maven 测试结果。
3. **本阶段只读取源码与添加本报告**；没有实施 Phase 1、没有修改 Agent Loop、没有测试运行结果。
4. 用户明确同意下一阶段并补齐/接受基线限制后，才启动 Phase 1；Phase 1 优先设计可测试、无 JLine 依赖的 `TurnInputManager`，默认 feature flag 关闭。

## 7. Phase 0 运行基线尝试（2026-10-20）

- 用户已明确批准一次范围严格限定的 Codex 检查 Agent。
- 已提交 ServerFS Mac Agent task：`agt_c951c37a18e55b7beb1b0fb8`，目标是读取仓库约束，运行 Git 状态、Java/Maven 版本与离线 `mvn -o test -Pquick`。
- **实际状态：FAILED（AGENT_PROVIDER_ERROR）**。Codex Provider 在初始化时向 API 返回 `401 Unauthorized: Missing bearer or basic authentication in header`，重复重连后退出。
- 从任务事件中只观察到初始化、连接重试和失败，没有 Git/Maven 命令执行或测试结果。**Phase 0 执行基线仍未完成；Phase 1 尚未获准启动。**
- 失败归因是 Mac Codex Runtime 认证/连接配置，非 PaiCLI 应用代码错误。不要因此修改业务源码。
- 建议先修复 Mac 端 Codex Agent 凭证或登录状态（不要在日志、聊天中明文发送密钥）；恢复后重新提交同范围基线任务。


## 8. Mac 本地认证复查与恢复（2026-10-20）

### 已验证的事实

- 本地 `codex login status` 返回 `Logged in using ChatGPT`（exit 0），无需据此重新登录。
- GPT-Free-Mac 的失败任务原因为 `401 Unauthorized: Missing bearer or basic authentication in header`，请求目标是 OpenAI Responses API；没有运行 Git/Maven 的证据。
- Bridge 的配置目录是 `<USER_HOME>/.codex`，与 CLI 一致；Bridge 连接的后台服务版本为 `0.162.0-alpha.17.2`，进程自 2026-10-13 启动。
- 对该后台服务执行只读 `account/read`，包括 `refreshToken=true` 的刷新尝试，均返回 `account=null`、`requiresOpenaiAuth=true`。这证明服务当前没有读到可用账户；尚不能单凭这些结果认定凭证过期。
- 重启前 `thread/loaded/list` 返回空列表，Bridge 数据库只有 2 个失败任务，没有活动任务或待审批请求。
- 计划恢复方式：仅重启上述空闲的 Codex 后台服务，然后复查账户。不变更登录凭证、模型、权限或业务源码；恢复结果见下方追加记录。

### 本地 Phase 0 环境检查

- `<USER_HOME>/Projects/paicli-main` 的 `git status --short --branch` 实际返回 exit 128：`fatal: not a git repository (or any of the parent directories): .git`。当前目录是源码副本，无法记录 HEAD、分支及 Git dirty 列表；不自动初始化 Git 或覆盖源码。
- `java -version` 返回 Temurin OpenJDK `25.0.4.1`，arm64（exit 0）；项目编译目标仍是 Java 17。
- `mvn -version` 返回 exit 127：`command not found: mvn`。未找到 Maven，也未发现项目 Maven Wrapper；本轮尚未安装 Maven，离线 quick 测试未执行。
- 即使认证恢复，Phase 0 仍受 Git 元数据缺失和 Maven 不可用影响；不得写成测试通过，不得自动进入 Phase 1。


### 恢复结果

- 已执行 `codex app-server daemon restart`（exit 0）：仅重启空闲后台服务，没有更新安装版本；服务仍为 `0.162.0-alpha.17.2`。
- 重启后同一路径的 `account/read` 返回 `account.type=chatgpt`，不再是 `null`；CLI 登录状态也仍为 ChatGPT。
- 同一服务的 `account/rateLimits/read` 联网读取成功，确认它能使用现有登录完成后端认证。仅检查成功标志，未在报告记录账户信息、额度明细或任何凭证。
- 因此已恢复 Bridge 所连接服务的登录状态；原故障与旧后台服务未加载可用账户一致。没有重新提交 Agent、没有调用模型生成；Responses 推理请求是否成功尚未重测。
- 直接尝试 `mvn -o test -Pquick`，exit 127：`command not found: mvn`。没有测试运行结果，不能将环境失败写成测试失败或通过。
- 下一步需要可追溯的 Git checkout，以及 Maven 和离线依赖缓存；在补齐环境或用户接受限制前，Phase 0 仍未完成、Phase 1 不启动。
- 本轮变更仅为本报告的追加记录及空闲后台服务重启；未修改 PaiCLI / ServerFS 业务源码，未安装软件，未复制或改写认证凭据。

## 9. 后续实测补录：环境已恢复、局部基线通过（2026-10-21）

> 以下为后续开发阶段的新增事实，**不覆盖**上面历史故障记录。

- 本机只有源码副本、无远程仓库：完成同级独立目录备份（922 文件/197 目录、约 17.7 MiB），建立本地 Git 仓库初始提交 `d25ead1`，分支 `main`；未配置远程，未修改原 Java 业务代码。
- Homebrew 安装 Maven 3.10.0，使用已存在的 Temurin Java 21.0.12.1 运行定向测试。完整 Quick 回归此前受 Java 25 / Byte Buddy、一个 Plan 模拟响应断言和 `SessionModeControllerTest` 停滞影响，仍**不能宣称全量通过**。
- 在修正 Maven 缓存锁沙箱权限后，6 类独立核心基线测试共 **115/115 通过**（失败/错误/跳过均为 0），Maven exit 0；无外部模型调用，无 Git 已跟踪文件变动。
- 在通过局部准入后，使用功能分支 `feat/steering-phase1` 完成 Phase 1，7 类总计 **136/136 通过**。详见 `docs/mid-turn-steering-phase1-report.md`。
- 因此，“Git/Maven 完全阻塞、Phase 1 未启动”的旧结论已是**历史状态**；当前 Phase 1 已完成，Phase 2/3 尚未实现。

