# PaiCLI 执行中动态指令（Mid-turn Steering）与任务控制：设计及实施计划

> 状态：**Phase1、Phase2A 与 Phase2B-1 独立输入桥已验证；Main 尚未接线** · 编写日期：2026-10-20；更新：2026-10-21
> 当前实施仓库：`projects/paicli-agentcli-public-export-20261021-6c9049d020`；早期记录来自原仓库，本批不访问原仓库。
> 范围：前台 CLI 的 ReAct / Plan-and-Execute / Multi-Agent 运行时输入控制，以及必要的上下文、工具、持久化衔接。  
> 变更边界：本文是开发依据，**不代表完整功能已实现**；当前只完成 Phase1 和下述 Phase2A 边界，后续每阶段单独验收。
> 目标定位：以 Codex 的 Steer / Queue 思路作为交互参考，按照 PaiCLI 的 Java、JLine、工具调用架构重新设计，不照搬产品内部实现。

## 0. 一句话目标与设计原则

**允许用户在 Agent 执行中继续输入内容，并可明确选择：当前任务引导（Steer）、下一任务排队（Queue）、请求中断（Interrupt）。在安全检查点把消息加入正确的上下文，完整保留工具调用及结果、审批与任务状态，不重复执行有副作用的工具。**

原则：
1. **正确性 > 实时性**：默认在本轮工具调用结果已完整入历史、下一次请求 LLM 前注入 Steer；不承诺打断正在运行的 Shell 或已发出的 LLM 请求。
2. **一位输入所有者**：任何时刻只有一个组件从 JLine / Terminal 读取字节；审批、计划审阅和普通聊天都经同一输入路由，禁止另起一个与 `readLine` 竞争的 raw-reader。
3. **消息状态显式**：接收、持久化、交付、进入模型请求、处理结束分别可见；显示“已接收”不等于模型已执行。
4. **同一轮对话只有 Agent 工作线程能修改 History**；输入线程只能投递不可变消息，不能直接修改 conversationHistory。
5. **工具协议完整**：发给模型的全部 tool_call ID 最终必须有对应结果（正常、错误、取消或“未执行”）；Steer 不允许插在缺少 tool_result 的中间状态。
6. **权限来源可追溯**：只有经可信前端提交的真实用户文本能作为 `TurnToolPolicy` 的权限来源；工具输出、网页、MCP、规划器产物不能借 Steering 提升权限。
7. **按阶段上线并设置开关**：先完成 ReAct，再 Plan，最后 Team；每阶段保证旧模式行为不退化且可以一键关闭。

## 1. 已核查的源码基线（2026-10-20 静态阅读）

| 模块 | 代码入口 | 已有能力与缺口 |
|---|---|---|
| 终端交互 | `cli/Main.java` → `runWithCancelSupport`、`readEscCancel` | 前台任务有独立 Runner 线程和 ESC 取消轮询；当前轮询在非 ESC 时读取并丢弃其他字节，无法可靠在执行期间输入新消息。 |
| 输入互斥 | `util/TerminalInputLock`、`Main.createPlanReviewHandler` | 审批和计划审阅已有终端输入互斥概念，但还未形成统一的持续输入路由。 |
| ReAct | `agent/Agent.java` → `runInternal` | 循环 LLM→tool calls→tool results→下一轮，适合接入安全检查点；尚无独立 Steering 队列与 Turn 交付协议。 |
| DAG | `agent/PlanExecuteAgent.java`、`plan/Planner.java`、`plan/ExecutionPlan.java` | DAG 校验、依赖并行、失败重规划已存在；计划执行期间收到新要求如何更新未执行节点，尚需协议。 |
| Team | `agent/AgentOrchestrator.java`、`agent/SubAgent.java` | Planner/Worker/Reviewer 协作存在；跨 Worker 新约束的版本传播与确认未纳入本次基线。 |
| 工具 | `tool/ToolRegistry.java`、`tool/TurnToolPolicy.java` | Tool Registry 支持并发；工具执行前做权限与来源控制。Steer 不得绕过前置工具审批。 |
| 会话 | `history/ConversationLedger.java` | 已有追加式 JSONL、`appendMessage` / `appendEvent`；可追踪输入事件，但不等于具有持久、事务性的消息队列。 |
| 取消 | `runtime/CancellationContext.java` / `CancellationToken.java` | 已有协作式取消；`CURRENT` 是全局引用，要核查后台任务与并行 Runner 的隔离。 |
| 后台任务 | `runtime/task/DurableTaskManager.java` | 重启时会把 RUNNING 状态改回 ENQUEUED，属于**重跑风险**，不是执行点断点恢复。不可直接复用为 exactly-once 工具执行。 |
| 上下文 | `memory/AutoCompactionManager.java`、`agent/Agent.java` | 已有工具输出清理和摘要压缩。需保证待处理新约束不会被压缩丢失。 |

**实施前检查**：确认当前 HEAD、未提交改动、`pom.xml` 的 Java 17 编译目标、`mvn test -Pquick` 基线；记录结果。未经批准不格式化、不全库重构、不自动提交。

## 2. 用户交互语义（产品契约）

| 动作 | 执行中输入 | 契约 |
|---|---|---|
| **STEER** | 默认 Enter 提交普通消息，或显式 `/steer 文本` | 尝试送往**当前 Turn**；在下一个安全边界进入模型请求。不会自动回滚已完成的文件修改。 |
| **QUEUE** | `/queue 文本` | 排在**当前 Turn 完成之后**作为新 Turn 执行；不得偷偷合并到当前 Prompt。 |
| **INTERRUPT** | Esc 或 `/cancel` | 请求取消活动 Turn；停止启动新的危险操作，对已启动工具尽力取消；不承诺撤销已发生的副作用。 |
| **STATUS** | `/inputs` 或状态栏 | 显示活动 Turn、待交付 Steer 数、待执行 Queue 数、最新消息状态。 |

**键位约束**：已有 `Tab` 自动补全、`Shift+Tab` 模式切换，第一版**禁止抢占 Tab** 作为 Queue 快捷键；需要经过 Mac/Linux/JLine 的真实终端测试后才考虑附加快捷键。

**审批优先**：当 UI 正显示 HITL 审批或计划审阅时，输入属于该提示框，不得意外视为 Steering。界面必须指示当前输入模式，并提供明确返回/取消方式。

**边界**：
- 运行中消息只在当前 Run 活跃、`expectedTurnId` 匹配时接受 Steer。过期/不存在的 Turn 返回 `STALE_TURN`，提示用户重新排队，不静默投到其他任务。
- **Queue 默认 FIFO**；当前任务被 Interrupt 时，排队任务保留但暂停自动启动，显示待确认列表，避免意外继续执行。
- 用户可以连续发送多个 Steer；按 `sequence` 记录和注入，不按消息到达的线程调度顺序排序。
- 不做“实时更改当前 LLM 推理中的 Prompt”；如果模型 API 支持原生 mid-turn steer，应作为后续可选 Provider Adapter，另行验证其 accepted 与真正生效的事件语义。

## 3. 目标架构

```text
                         ┌──────────────────────────┐
                         │  JLine InputCoordinator  │
                         │  唯一 Terminal Reader    │
                         └─────────────┬────────────┘
                           submit / steer / queue / esc
                                       │
                         ┌─────────────▼────────────┐
                         │  TurnInputManager        │
                         │  Turn 状态 + 有界消息队列 │
                         │  顺序号 / 去重 / 路由     │
                         └───────┬────────┬─────────┘
                      STEER      │        │  QUEUE
                                 ▼        ▼
                    ┌──────────────┐   FollowUpQueue
                    │ Active Turn  │          │
                    │ Steer Inbox  │          │ Next Turn
                    └──────┬───────┘          │
                           ▼                  │
                   Safe Point Drainer         │
                   (Agent worker owner)       │
                           ▼                  ▼
                    Tool Results → History → LLM
                           │
                 ConversationLedger 事件记录
```

推荐新增：
- `runtime/input/DeliveryMode.java`：`STEER`、`ENQUEUE`、`INTERRUPT`。
- `runtime/input/TurnState.java`：`IDLE/STARTING/RUNNING/WAITING_APPROVAL/FINALIZING/COMPLETED/CANCELLED/FAILED`。
- `runtime/input/RuntimeInput.java`：`messageId/sessionId/expectedTurnId/sequence/source/content/mode/receivedAt`，不可变。
- `runtime/input/TurnInputManager.java`：线程安全入队、原子状态转换、消息去重、Turn 结束竞态处理。
- `runtime/input/TurnInputDelivery.java`：将已接收消息封装成仅供 Agent 工作线程使用的批次。
- `cli/InputCoordinator.java`：唯一的 JLine 输入所有者，将聊天、审批、计划审阅和 Esc 事件路由到不同模式。
- `runtime/input/TurnEventSink.java`：复用 `ConversationLedger.appendEvent` 的适配接口，避免核心队列直接依赖 CLI。

建议状态事件（区别“收到”与“生效”）：

```text
RECEIVED → ACCEPTED → DELIVERY_RESERVED → INCLUDED_IN_REQUEST
      └─→ REJECTED / CANCELLED / EXPIRED
```

- `ACCEPTED` 只表示 Harness 已保管消息。
- `INCLUDED_IN_REQUEST` 只表示该消息进入发送给模型的请求，**不表示模型遵循了要求**。
- 消息若因网络失败尚未发出，需要能回退到待交付；如模型请求是否已收到结果不明，记录不确定状态而非随意声称已执行。
- 留痕至少包括 `turnId/messageId/sequence/mode/status/reason/toolCallId`，内容脱敏；避免把隐私内容、密钥重复写入诊断日志。

## 4. 最重要的安全检查点（Safe Point）

### 4.1 ReAct 的注入顺序

1. 完成当前已提交的 LLM 响应记录。
2. 对已发出的所有工具调用处理到完成/失败/取消，并把**每个调用的结果**补齐 History；禁止在中途插入新的用户消息破坏 tool-call 配对。
3. 在下一次调用 LLM 前，从 `TurnInputManager` 按序接收当前 Turn 的 Steer；验证来源与 Turn ID，追加 `LlmClient.Message.user(...)`，写入 Ledger 交付事件。
4. 重新计算相关的安全约束与 Token 预算。**先保护最新未完成用户约束，再执行必要的上下文压缩**；模型输入由执行线程统一构建。
5. 建立 LLM 请求快照，记 `INCLUDED_IN_REQUEST` 和请求关联 ID，调用模型。
6. 若模型准备输出最终答案：**Turn 状态转 FINALIZING 与“是否仍有待交付 Steer”检查必须在同一锁/原子操作中完成**。仍有 Steer 就继续迭代，没有才结束并移交 FollowUpQueue。

示意（非可直接运行代码）：

```java
for (;;) {
    cancelToken.throwIfCancelled();
    // 上一轮的所有 tool_result 已经落盘并写入历史
    List<RuntimeInput> accepted = inputManager.reserveSteers(turnId);
    appendUserMessagesInOrder(accepted);       // 仅 Agent 线程操作 History
    refreshTrustedPolicy(accepted);             // 工具输出不能提升权限
    compactWithoutDroppingNewConstraints();
    LlmResponse response = callModel(snapshotHistoryAndTools());
    markIncludedWhenRequestSubmitted(accepted);
    if (response.hasToolCalls()) {
        executeAndRecordAllToolResults(response.toolCalls());
        continue;
    }
    if (inputManager.finalizeOrContinue(turnId) == CONTINUE) continue;
    return response.finalText();
}
```

**关键风险**：同一个模型响应可能一次包含多个工具调用。P0 不抢占正在执行的整批工具；P1 可在每个**未开始**的有副作用工具派发前设置“Steer 已到达”门禁，暂停后续派发，并为没执行的 tool_call 写规范的 `not_executed` 工具结果，随后回到下一次模型决策。不能直接删掉未执行 tool_call。

### 4.2 执行中改变用户约束

Steer 不是“覆盖前面的所有用户输入”。应保留原任务和后续更改记录；新约束必须在下次执行危险工具前重新应用到 `TurnToolPolicy`。不得仅拼接一段未经来源校验的字符串。

特别需要重新审查 `TurnToolPolicy` 中：
- `submittedUserInput` 与展开后的 `userInput` 来源区分；
- `explicit no web`、URL 可信来源和浏览器权限；
- MCP 工具、Shell / 文件写入的审批；
- 计划执行与 Worker 使用的 `forkWithTrustedUrls` 分支隔离。

**安全保底**：新消息要求停止写文件时，已执行过的写入不能自动回滚；未启动的危险工具必须重新过策略检查，必要时暂停并询问用户。

### 4.3 上下文与 History

- `Agent.java` 的 `conversationHistory` 为单线程写；InputCoordinator 不得直接 append。
- `ConversationLedger` 记录 `input_received/input_accepted/input_delivered/input_included/turn_completed`，并关联 `turnId`。
- 最新 Steer 在被模型纳入请求前**不可只保留摘要并丢弃原文**。
- 历史压缩时保留未完成约束、当前 DAG 目标、已执行副作用与待验证项；用户原话与自动摘要分开存。
- 用户消息在模型请求之前到达，也不能修改已经发出的网络请求；Provider 若提供 API 原生 steering 属于另一种实现，单独适配。

## 5. Plan 与 Team 的增量支持策略

### 5.1 Plan-and-Execute（第二阶段）

- **安全边界**：完整的节点/并行批次结束后，下一批节点启动前。
- Steer 中的新限制先记录到计划的 `constraintRevision`，判断是否影响未执行节点；确实改变目标才调用 `Planner.revisePlan`。
- 已成功完成的节点不能静默当成未执行重跑；必要时在计划中新增调整节点。失败节点、跳过节点保留。
- 规划器的修改结果仍要验证任务 ID、依赖存在、无环；影响范围较大或带破坏性变更时要求计划重新审阅。
- Queue 只能等整个 Plan 结束后执行；不可误变成某个 DAG 的下一节点。
- 不能仅由字符串“补充要求”作为重规划依据，需要保留结构化事件和现存计划状态。

### 5.2 Multi-Agent（第三阶段）

- 主 Orchestrator 是用户输入的唯一接收者，不让每个 Worker 各自消费完整消息队列。
- 将新约束附上单调递增 `constraintRevision`，在**下一 Worker 分配点**随任务一同传递；已在执行的 Worker 默认完成当前受控步骤后报告，不承诺立即抢占。
- 同一约束广播给需要的 Worker，按版本和 `messageId` 去重，确保并行任务不重复处理。
- 写工作区的 Worker 不能因为不同 DAG 节点“无依赖”就同时写同一文件；保留现有锁、审批和快照策略。
- 默认先不做细粒度子 Agent Steering 和远程并行 Session 切换。

## 6. 终端重构策略（优先于 Agent Loop 接线）

当前 `runWithCancelSupport` 在任务执行时直接轮询 Terminal `NonBlockingReader`，非 ESC 输入会被清空；单纯增加生产者线程会与 `lineReader.readLine` / HITL / Plan review 抢读取权。

**推荐**：
1. 增加 `InputCoordinator`：同一时刻由唯一输入任务处理所有终端按键和编辑态，明确 `IDLE_INPUT`、`RUNNING_INPUT`、`APPROVAL_INPUT`、`PLAN_REVIEW_INPUT`。
2. 运行时输入编辑与输出分离：Runner 继续写 `Renderer.stream()`，输入只通过 JLine 事件通道；状态栏标明“运行中，可发送 steer”。
3. `Enter` 在 `RUNNING_INPUT` 提交 Steer；`/queue 文本` 提交 Queue；`Esc` 提交取消事件；Tab 保持补全。
4. 与 Renderer 协调重绘、括号粘贴、中文输入法、方向键和长文本；不得将 Esc 前缀的方向键误判取消。
5. HITL / Plan 审阅暂时抢占输入**模式而不是底层 Reader**，可暂停普通消息输入并清晰提示，不发生两线程抢键盘。

兼容性：首先覆盖 macOS Terminal / iTerm2，Linux 终端与非交互式 stdin 作为后续测试；Terminal 不支持交互时退化为原有执行方式，不启用 Steering。

## 7. 阶段路线、文件范围与验收门槛

### Phase 0：冻结基线（只读审计）
- 记录 Git HEAD / 工作区变更 / Java 版本 / 主要 CLI 命令和当前测试结果。
- 建立 12–20 条确定性场景用例，不访问付费模型。
- **通过门槛**：现有 CLI 输入、ESC、HITL、Plan 审阅的回归基线有记录。

### Phase 1：TurnInputManager 核心（不碰 TUI）
- 新增 `runtime/input/*` 类型、状态机、消息上限、去重、Turn ID 校验、FIFO 和原子 finalize。
- P0 使用内存有界队列：建议每 Turn Steer 上限 20 条、Queue 上限 50 条、每条 UTF-8 最大 16 KiB（均配置化）；超限显式拒绝，不静默丢消息。
- 配置开关 `paicli.steering.enabled`，默认 **false**。
- **验收**：200 次以上随机并发入队/关闭竞态测试；无串 Turn、无丢失、无重复交付、相同 ID 不重复。

### Phase 2：JLine 输入通道改造
- 在 `cli/Main.java` 和输入/Renderer 相关组件建立唯一输入所有者，迁移取消检测。
- 保留旧 ESC、Tab / Shift+Tab、HITL、Plan 审阅行为；实现运行中 Enter、`/steer`、`/queue`、`/inputs`。
- **验收**：真实伪终端（PTY）自动化和 macOS 手工复核：中文输入、粘贴、方向键、渲染、审批期间输入均不丢字、不误触发。

#### Phase 2A 本批边界（2026-10-21）

- 用户已授权开始第二阶段；随后要求尽快完成独立协调组件及最小测试后停止，便于另行建立公开 GitHub 仓库。本批不提交、不配置 remote、不推送。
- 静态核查发现：`InlineApprovalPrompter` 的选项读取使用 `terminal.reader()`，后续原因、参数和范围输入使用独立的 `BufferedReader(System.in)`；`PlainRenderer`、`TerminalHitlHandler` 也单独读取 stdin。`TerminalInputLock` 只提供调用互斥，不能消除多个 Reader 内部的预读。
- 本批只新增 `cli/InputCoordinator.java` 和对应测试。协调器接收未来单一 JLine 读取线程已经解码的行/独立 Esc 事件，负责输入模式、当前任务标识、审批优先和向 Phase1 管理器投递；不读取终端、不新增读取线程、不写 History、不改变 Phase1 状态机。
- 输入读取开始时保存模式与任务标识；模式切换后返回的旧输入必须原样退回供再次确认，不能变成审批批准或投到下一任务。任务结束只报告待处理数量，不清空、不自动执行，也不声称消息已经进入模型。
- 验收：先保存定向失败结果，再运行协调器测试、Phase1 `TurnInputManagerTest` 和 Phase0 六类。使用 JDK21/Maven3.10、离线同 ID settings、隔离 home/tmp、清空 Provider keys；不跑完整 quick、真实模型、AgentBench 或 Docker。
- **未接线 Gate**：Main 仍走旧输入路径，开关默认 false。后续必须把 inline/plain 审批及子提示、计划单键和补充输入统一迁至同一个 JLine 所有者，再验证唤醒、草稿恢复、渲染及 raw mode 清理。通过 FakeTerminal/PTY 自动化和真实 Mac 复核后才能接线；Phase3 的上下文注入另行授权。

本批结果（完成后停止）：

- 仅新增 `src/main/java/com/paicli/cli/InputCoordinator.java`、`src/test/java/com/paicli/cli/InputCoordinatorTest.java`，更新本文。Main、Agent、PlanExecuteAgent、审批/renderer、Phase1、pom 和用户配置均未修改。工作区原有未提交/未跟踪文件保留，未发现相关文件并行编辑。
- 11 条协调器测试覆盖：普通 Enter 与显式命令、中文/多行原文、状态查询、无效命令、审批/计划优先、跨线程审批请求、单一事件处理线程、一次性读取标识、过期任务、结束时保留待交付与队列、Esc 取消请求、默认关闭/非交互降级、交互释放。Esc 与方向键/粘贴的分类只复用既有纯函数，不算真实终端验证。
- 测试环境为 Temurin 21.0.12.1、Maven 3.10.0；仍按项目 Java17 目标编译。`target/run-phase2a-tests.py` 使用环境变量白名单（不继承任何 Provider key）、隔离 home/tmp、`-o` 和原 `target/phase0-central-settings.xml`，本地仓库 ID 不变。
- 先失败：`python3 target/run-phase2a-tests.py InputCoordinatorTest red`，exit 1；原因是新类尚不存在，测试编译失败，**未运行 JUnit**。实现后同类 11/11 通过，exit 0。
- 合并回归命令：`python3 target/run-phase2a-tests.py InputCoordinatorTest,TurnInputManagerTest,CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest,CancellationContextTest,ConversationLedgerTest,TerminalHitlHandlerTest,TerminalInputLockTest,InlineApprovalPrompterTest,MainRunModeTest regression-escalated`。11 类、162 例全部通过，失败/错误/跳过均 0，exit 0。首次沙箱内运行有 7 个 `InlineApprovalPrompterTest` 错误，根因为 Mockito/Byte Buddy 无法通过外部进程附加当前 JVM；同一组经单次批准在沙箱外重跑通过，未改测试或绕过断言。
- 最后将状态提示改为直接显示数量后，在沙箱内运行同命令去掉 `InlineApprovalPrompterTest`、日志名为 `final`：10 类、155/155 通过，exit 0。包括 Phase0 六类 115、当前 Phase1 24、协调器 11、输入锁 2、运行方式 3。inline 审批原代码未修改，先前 7/7 通过。日志分别为 `target/phase2a-red.log`、`phase2a-regression.log`、`phase2a-regression-escalated.log`、`phase2a-final.log`。
- 全仓库 `git diff --check` 因原有 `docs/mid-turn-steering-phase0-audit.md:174` 的末尾空行返回 2；未修改该既有改动。本批文件单独检查通过。
- **实际接线：无。** 新组件不会读取任何字节或执行取消；Esc 只向 Phase1 提交 INTERRUPT，真正调用现有取消令牌仍待 adapter。生产关闭时沿用旧行为；即使显式设为 true，Main 本批也不会启用新输入组件。不新增审批抢读，但既有多 Reader 风险尚未消除。
- **待验**：真实 FakeTerminal/JLine 切换、PTY、Mac Terminal/iTerm2 中文输入法、Tab/Shift+Tab、方向键、多行/括号粘贴、运行输出重绘、审批子提示、计划 raw/行输入、Ctrl+C 和 raw mode 清理。本批没有运行真实模型、AgentBench、Docker、全量或完整 quick；没有进入 Phase3。

#### Phase2B-1：统一输入所有者与审批输入桥接基础（2026-10-21）

本批只在独立公开仓库 `paicli-agentcli-public-export-20261021-6c9049d020` 的
`feat/steering-phase2b` 工作；开始时 HEAD `89888aa`、工作区 clean，未发现本仓库并行写入者或锁。
不访问原仓库，不提交、推送、变基或切分支，不进入 Phase3。

实施前设计与失败用例：

- 新增一个 focused 类型 `cli/TerminalInputGateway`（输入请求桥）：构造线程是唯一 UI 所有者；
  注入一份读取回调，只有 UI 的 `serviceNext` 可以调用。worker 只开启一段交互、逐个提交带 ID 的子请求并等待。
- 复用 `InputCoordinator.enterInteraction/capture/submit`。交互从主选项到拒绝原因、JSON、范围、计划补充全部结束前保持模式，
  中间暂时没有子请求也不能变回普通输入；同时只接受一段交互/一个子请求，冲突明确失败，不隐藏排队。
- `TerminalInputLock` 只包 UI 实际读取；worker 等待期间不持有这把锁或桥的监视器。
  请求状态在桥的同一监视器内更新；读取回调在监视器外执行。超时/中断/关闭可唤醒 worker，迟到响应必须按 ID 丢回 RETRY。
- 只给 `InlineApprovalPrompter` 增加显式注入入口；旧构造器、PlainRenderer、TerminalHitlHandler、Main 保持原路径。
  新路径 EOF、异常、中断、超时不能转换为空行；整段审批返回拒绝。只有真实收到的空行沿用原有 Enter 语义。
- 最小失败测试覆盖：唯一 UI 读者、worker 不读终端、子请求不同 ID、审批期间普通行不进 Steer、过期票据 RETRY、
  超时后迟到响应不能批准新请求、EOF/中断/关闭、交互间隙模式保留、计划输入、关闭/非交互禁用；
  注入路径补测拒绝原因/JSON/范围读取失败均拒绝。
- 验证使用 JDK21/Maven3.10、`-o`、现有 `target/publication-check/settings.xml` 的 repository ID，
  环境变量白名单清除 Provider key，home/tmp 隔离到 target。先运行失败测试，再运行新测试和指定输入/审批核心回归。

**接线 Gate**：本批不为阻塞中的 `readLine` 编写未经验证的唤醒，也不往 Main 添加伪接线。
即使 `paicli.steering.enabled=true`，Main 也不会自动使用新桥。Phase2B-2 必须验证唯一 JLine Reader 的可靠唤醒、
审批优先切换、草稿恢复、Tab/Shift+Tab、中文/方向键/Escape、计划 raw/行输入及 raw mode 清理，再替换运行时旧读取。
本批单测不是 PTY 验证，不代表 Steer 已进入模型。

实际结果（Phase2B-1 完成后停止）：

- 新增 `src/main/java/com/paicli/cli/TerminalInputGateway.java`（只新增一个顶层生产类型），以及
  `src/test/java/com/paicli/cli/TerminalInputGatewayTest.java`、
  `src/test/java/com/paicli/render/inline/InlineApprovalGatewayTest.java`。
  修改 `InlineApprovalPrompter.java`、本文和 `README.md`。未改 Main、Agent、InputCoordinator、Phase1、
  PlainRenderer、TerminalHitlHandler、InlineRenderer、工具审批策略、pom 或用户配置。
- 数据语义：每个子请求有独立 UUID、输入类型和提示；一段 Session 对应一次审批或计划交互。
  UI 读取前捕获模式票据和请求 ID，完成时同时核对 Session、请求 ID 和票据，过期输入原样返回 RETRY，
  不自动重新提交。没有公开的任意 ID 完成入口。真实空行与 EOF 分开；失败通过带原因的异常传给 worker。
  输入只在进程内等待，不写 History、不调用模型、不执行取消或队列任务。
- 锁语义：桥的同一监视器保护 Session、子请求和响应；worker 的限时等待释放监视器。
  只有构造时的 UI 所有者可调用读取回调；`TerminalInputLock.tryAcquire/release` 只包住 UI 读取，
  读取期间不持桥监视器，因此关闭/超时/中断能解除 worker 等待；同线程重入读取被拒绝。
  交互间隙保留审批模式，冲突的新交互返回 BUSY，不抢走当前交互。
- inline 新构造器不创建 `BufferedReader(System.in)`、不访问 `terminal.reader()`；主选项、拒绝原因、
  JSON 和 MCP 范围全经同一桥。EOF、读取异常、中断、超时都返回拒绝，包括原先会把子提示 EOF 当作空行的场景。
  真实空行保留原 Enter/保留参数/工具范围语义；敏感操作禁止全部放行及非法 JSON 重提示沿用原规则。
  旧构造器仍默认使用原路径；本批没有修复默认旧路径的 EOF 子提示和多 Reader 风险。
- 19 条新测试（Gateway 11、inline 桥 8）覆盖不同 ID、普通行与中文原文、审批间隙、请求冲突、
  计划/补充输入、唯一 UI、等待中断、EOF/错误/关闭/超时、超时后迟到 y/Enter、终端锁、关闭/非交互降级。
  与旧 InputCoordinator 的测试共同验证模式切换后旧票据 RETRY；没有把纯函数的按键分类称作 PTY 验证。
- 环境：Temurin 21.0.12.1、Maven 3.10.0，按 pom 的 Java17 目标编译。
  `target/run-phase2b1-tests.py` 只传 PATH/JAVA_HOME/TMPDIR/LANG/MAVEN_OPTS，完全不继承 Provider key；
  Maven 与测试 JVM 的 `user.home`、`java.io.tmpdir` 均在 `target/phase2b1/`，Surefire XML 已核对。
  使用 `-o -s target/publication-check/settings.xml -Dmaven.repo.local=/Users/a123/.m2/repository -DskipTests=false`；
  settings 的 repository ID 为既有 `phase0-central-only`。未运行真实模型、AgentBench、Docker、联网、全量或完整 quick。
- 先失败命令：`python3 target/run-phase2b1-tests.py TerminalInputGatewayTest red`，exit 1，
  沙箱禁止创建既有 Maven 缓存 `.locks` 文件，未进入 JUnit。
  单次批准后同配置运行 `python3 target/run-phase2b1-tests.py TerminalInputGatewayTest red-escalated`，exit 1，
  原因为新类尚不存在、测试编译失败，**未运行 JUnit**；日志分别在 `target/phase2b1/red.log`、`red-escalated.log`。
- 实现后命令：`python3 target/run-phase2b1-tests.py TerminalInputGatewayTest,InlineApprovalGatewayTest green`，
  exit 0，18/18 通过。随后将预先中断测试改成真实等待中断，并新增冲突测试。
- 核心回归命令（在已批准的沙箱外运行，沿用同一隔离环境，无 Mockito attach 环境失败）：

  ```bash
  python3 target/run-phase2b1-tests.py TerminalInputGatewayTest,InlineApprovalGatewayTest,InputCoordinatorTest,TurnInputManagerTest,CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest,MainRunModeTest,SessionModeControllerTest,TerminalInputLockTest,InlineApprovalPrompterTest,InlineRendererTest,PlainRendererTest,TerminalHitlHandlerTest,RendererHitlHandlerTest,SwitchableHitlHandlerTest,ApprovalResultTest,ApprovalPolicyTest,HitlDefaultConfirmationTest,HitlToolRegistryTest,CancellationContextTest,ConversationLedgerTest regression
  ```

  exit 0，22 类 **285/285 通过**，失败/错误/跳过均 0；日志 `target/phase2b1/regression.log`。
  最后把内部请求匹配写成显式 UUID 比较后，执行
  `python3 target/run-phase2b1-tests.py TerminalInputGatewayTest,InlineApprovalGatewayTest,InputCoordinatorTest,TurnInputManagerTest final`，
  exit 0，4 类 **54/54 通过**，失败/错误/跳过均 0；日志 `target/phase2b1/final.log`。
  此次验证在用户要求结束前已启动；收取结果后不再追加测试。
- 最终 Git 检查：仍为 `feat/steering-phase2b`、HEAD `89888aa`，只包含上述 6 个预期文件的修改/新增。
  `git diff --check` 首次发现本文新增行末空格，已修正；最终 exit 0。Git 自身在只读沙箱打印的
  `/tmp/xcrun_db` 缓存警告不影响 status/diff 结果；未为此扩大权限或重做进程审计。
- **真实接线：Main 没有接线。** 本批只有独立 broker 加 inline 审批注入入口与双线程假读取器测试。
  Main、InlineRenderer、PlainRenderer、TerminalHitlHandler、计划审阅 handler 仍走旧路径。
  默认开关仍为 false，显式改 true 也不会改变 Main 行为；Steer 尚未进入模型。
- **Phase2B-2 Gate**：先为同一份 JLine Reader 实现可验证的唤醒/暂停与模式交接。
  桥超时可以结束 worker 等待，但不能停止阻塞中的读取回调，不能据此启动另一个 Reader。
  接入 InlineRenderer/计划 handler 时必须移除 worker 外层的 `TerminalInputLock.exclusive`，
  否则 worker 持锁等待 UI、UI 等锁会卡住；锁改由唯一 UI 持有。
  迁移 plain/HITL 和计划主选项及全部子提示，确认实际只读一份 Reader 后再启用运行时路径。
  完成 FakeTerminal/JLine 与 PTY、macOS 中文/草稿恢复/Tab/Shift+Tab/方向键/Escape/粘贴、
  计划 raw/行输入、Ctrl+C、输出重绘和 raw mode 清理验证；本批均未宣称通过。
  Phase3 仍需另行授权。没有提交、推送、变基、切分支或启动子 Agent。

#### Phase2B-2：自动化终端可行性验证（2026-10-23，验证结束，GATE_FAIL）

- 本批保留 Phase2B-1 的六文件改动；开始时另存原文和 SHA-256 到 `target/phase2b2/baseline/`。
- 先增加可独立执行的 JLine PTY proof（终端验证程序）和离线 JUnit，再实现一个仅限安全交接点使用的
  `cli/JLineInputAdapter`：注入现有 LineReader，不创建第二个 Reader；限制构造线程读取和禁止重入；
  EOF 显式返回 null，Ctrl+C 不能当成空行批准。所有审批子提示仍通过现有请求桥。
- 确定性场景：运行中逐字节 UTF-8、方向键/括号粘贴/独立 Esc、Tab/Shift+Tab、阻塞读取时 worker 请求审批、
  安全交接点的审批与草稿、任务自行结束、EOF/Ctrl+C 的终端属性恢复、计划单键/补充输入及行输入回退。
- 唤醒测试必须在无新增用户提交的情况下观察结果。审批超时只算 worker 解除等待，不能算 UI 已唤醒。
  如仍阻塞，验证程序报告 Gate 未通过，再由测试端发送明确的 Ctrl+C/EOF 清理；不模拟 Enter，不增加 raw 读取线程，
  不用定时强杀当作成功。Python 超时杀进程只作失败后的测试清理，必须记录失败。
- Main 不预先接线；只有唯一读取者、异步唤醒及完整草稿/按键保留都证明安全才重新评估。
  本环境不能做真实 Mac 人工体验，必须标未验；Phase3 不在授权范围内。
- 使用 JDK21、Maven 离线模式、Provider 环境白名单、独立构建目录/home/tmp；不改 pom 或 Java17 编译目标。
  回退只需移除本批新增的 adapter、测试和运行脚本，Phase2B-1 与 Main 不受影响。

2026-10-23 续验：限定 JUnit 已通过 14/14；原 PTY 驱动在 JVM 退出后检查从端属性，macOS 返回 ENOTTY，
不能据此宣称 PTY 通过。只修 proof 的退出握手和 `target/phase2b2` 驱动，在 JVM 仍存活时检查属性；
审批请求发出后不再输入任何字节，超时仅用于记录无唤醒，随后 Ctrl+C 只作清理。生产 adapter 与 Main 不改。

**续验已结束，结论 `GATE_FAIL`，不接 Main，不进入 Phase3（2026-10-23）。**

- 开始时为 `feat/steering-phase2b`、HEAD `89888aa0df70bca8b16cab5441d307c759d4dc55`。
  一次进程核对及当前 agent 列表未发现相关并行写入者；没有启动子 Agent，没有访问或改动原 `paicli-main`。
  保留全部已有未提交文件，包括 `tools/run-jline-input-proof.py`；旧 `proof.log` 没有改写，仍不能算 PTY 验证。
- 本次仅修改 `src/test/java/com/paicli/cli/JLineInputProof.java` 和本文；
  新驱动为 `target/phase2b2/run-validation.py`，从既有 runner 复制后最小调整，未改原脚本或 pom。
  proof 增加退出前的文件握手、实际 JLine 的所有者/重入检查、Tab/ShiftTab 和安全点审批/seed 草稿场景；
  阻塞审批场景去掉请求后的第二次控制键。adapter 与其三个单测原样保留，生产执行链路没有变化。
- 先用既有 runner 在新目录 `target/phase2b2/validation-20261023/` 执行：
  `JLineInputAdapterTest,TerminalInputGatewayTest`，**14/14、JUnit exit 0**；
  UTF-8 proof 输出 PASS 后，驱动在 JVM 已退出时检查 PTY 从端属性，报 ENOTTY，整体 exit 1。
  这项退出后属性检查为 `ENV_BLOCKED`，不是 UTF-8 场景端到端通过，也未运行到审批场景。
- 调整验证辅助程序后执行唯一一次完整续验命令：
  `python3 target/phase2b2/run-validation.py --tests JLineInputAdapterTest,TerminalInputGatewayTest --label junit-final`。
  JUnit 再次 **14/14、失败/错误/跳过均 0、Maven exit 0**，日志 `junit-final.log`；
  两轮共执行 28 条测试，仍是 14 条不同测试，没有新增 JUnit 用例。
  使用既有 JDK21/Maven3.10、`-o`、`phase0-central-only` 仓库 ID，环境变量白名单清除全部 Provider keys；
  Surefire XML 确认测试 JVM 为 Java 21.0.12.1，home/tmp 均在该新目录。
  每次验证设 170 秒上限，本次没有触发总超时；未运行 quick、真实 LLM、AgentBench、Docker、GitHub 或联网。
- 完整续验真实创建 macOS PTY，JLine 显示 `PosixSysTerminal`。共尝试 **11 个场景**，
  **端到端通过 0/11、runner exit 1**。其中 10 个场景到达 proof 的 PASS 标记，随后 OS 属性比较失败，
  驱动执行失败清理，不能把这些 PASS 标记或清理后的退出当成完整通过。
  `keys` 则在 `arrows/paste changed` 断言失败，未进入独立 Esc 部分。
  原始结果在 `pty-results.json` 和 `pty-*-verified.log`；JSON 的 `forced_cleanup` 是失败分支的保守标记，
  不代表每个失败场景都实际发送过 kill，已自行退出的 keys 不需要 kill。

| 真实 PTY 场景 | 可确证的结果与限制 |
|---|---|
| 唯一读取所有者 | 非所有者调用被拒绝；UI 内重入被拒绝；同一 LineReader 只进入一次读取，输出 `SINGLE_READER`。这是独立 adapter 的局部证明，不能说明旧生产路径已经只有一个 Reader。 |
| 阻塞中 worker 请求审批，无 Enter | **失败，`GATE_FAIL`**。主线程已在 readLine，先输入草稿和检查控制键；`APPROVAL_PENDING` 后驱动不再发任何字节，worker 等待 250ms 后报告 `GATE_NO_WAKE`。UI 没有进入审批，读取回调也没有启动。此后 Ctrl+C 只清理旧读取，异常中的中文草稿完整，审批没有成为 Steer。超时仅证明 worker 解除等待，绝不算唤醒通过。 |
| 安全点审批及草稿 | 前一次读取已经结束时，worker 审批 `y` 经桥交付，之后显式 seed 草稿并追加“继续”成功；**不是异步暂停/草稿恢复证明**。 |
| 中文与 UTF-8 | 逐字节发送中文和 emoji 的命令，内容与接收数量断言通过；未测试 macOS 中文输入法组合态。 |
| 方向键、Esc 前缀、括号粘贴 | 左右方向序列留下 `[D`/`[C`，混合粘贴内容不符合预期，断言失败；不能分别宣称方向键或粘贴通过。 |
| 独立 Esc | 因前一个按键断言失败未执行，**未验**。 |
| Tab / ShiftTab | proof 自定义 widget 能区分两个序列，保留中文草稿；未测试 Main 的实际补全或模式切换。 |
| Ctrl+C / EOF | 局部断言通过：Ctrl+C 抛取消异常并保留草稿，EOF 返回 null，均没有当成空行批准。 |
| 计划按键 / 补充行 | proof 自定义 keymap 的上方向键、Ctrl+O、I 和中文补充局部断言通过；行输入场景通过。未迁移或验证 Main 的 raw/普通行回退处理。 |
| 任务结束 | proof 的检查点分支观察到 `RUN_NO_WAKE`；未模拟真实任务结束后的完整状态交接，不能宣称任务结束唤醒已验。 |
| raw mode 清理 | 10 个场景中 JLine 属性相对 TerminalBuilder 完成后的快照相等；OS termios 与创建终端之前的快照不相等，原因未定位，随后失败清理。**完整属性恢复未通过**，不能排除终端初始化差异，也不能据此断言生产存在属性泄漏。 |
| FakeTerminal / 人工 Mac 体验 | 本次未运行 DumbTerminal fake 分支；三个 adapter 单测用的是 LineReader 代理，不能称作 FakeTerminal。Terminal/iTerm2 人工输入、输入法和输出重绘均未验。 |

**范围和下一步：** 当前 adapter 没有异步唤醒实现，真实 PTY 的负面证据与源码一致；
本次并未证明所有 JLine 方案都不可行。Phase2B-3 应先作为独立终端机制验证任务，选择专用 UI event loop
或 JLine widget/keymap 的可验证事件唤醒方案，仍只保留一个 Reader；验证无输入时进入审批、草稿/光标保留、
迟到响应隔离、方向键/Esc/粘贴和完整 OS 属性恢复。只有这些通过后，才另行授权 Main 及所有审批/计划子提示接线，
并移除 worker 外层的终端读取锁。现在继续保留兼容旧路径和关闭的 steering 开关，不绕过 HITL。

最终核对：本次基线记录的 851 个文件中，除 proof 和本文外其余 **849 个 SHA-256 完全相同**；
新增内容只在允许的 target 目录。`git diff --check` exit 0；没有 reset、clean、checkout、rebase、commit 或 push。
按用户要求收敛，不再重跑相似 PTY，也不再扩大权限或继续接线。

#### Phase2B-3：事件唤醒原型尝试（2026-10-23，已中止，未验收）

- 继续沿用 `feat/steering-phase2b`、公开 `main` 基线及既有 Phase2B-1/2B-2 未提交改动。此轮没有修改 `Main.java`、`Agent.java` 或生产工具/审批逻辑。
- 本轮调查了本地 JLine 的 `BindingReader`、`NonBlockingReader`、`LineReaderImpl` 等接口，拟试验“单一物理终端读取者 + 内部事件队列”的测试原型。**但并未成功完成可复核的原型和 PTY 测试**，不得将机制视为可用。
- 实验 Agent 提交了一条准备向外部终端会话写入较长验证脚本的审批请求。该命令内容超出已核查的安全执行范围，因此请求被取消，Agent 状态为 `cancelled`。没有批准该脚本写入，未验证其行为，也不得声称存在通过结果。
- 已证实的测试结果仍以 **Phase2B-2 JUnit 14/14 通过、真实 PTY 无 Enter 审批唤醒失败（`GATE_FAIL`）** 为准。本轮不新增成功率和唤醒通过声明。可能存在原型准备产生的临时内容，后续应在写操作前核对 `git status` 与文件差异，不要自动清理或覆盖。
- **下一次开发任务**：将事件唤醒原型写成项目仓库中清晰、可审查的小文件和独立 PTY 驱动，使用常规命令启动，不向状态不明的外部终端会话注入大段脚本；只允许一个组件读取物理终端。单独验证“无 Enter 唤醒、输入草稿及光标保留、审批优先、中文/方向键/Esc/粘贴、Ctrl+C/EOF、raw mode 完整恢复”。先运行小范围测试并保存完整失败日志，再决定是否接入 `Main`。
- **当前 Gate：FAIL**。继续保持 Steering 默认关闭，原 CLI 旧行为不变；不能跳过输入所有权验证去启动 Phase3 或声明 Codex 式运行中交互已完成。

#### Phase2B-3a：可审查的独立唤醒原型（2026-10-23，文件已写入，尚未运行）

- 在公开导出仓库中仅新增两个文件：`src/test/java/com/paicli/cli/JLineWakeupProof.java`、`tools/run-jline-wakeup-proof.py`。未修改 `Main.java`、`Agent.java`、`JLineInputAdapter.java`、`TerminalInputGateway.java`、`pom.xml`、生产审批及工具逻辑。
- `JLineWakeupProof` 是独立试验，不属于应用启动路径。继承 JLine `LineReaderImpl`，重用其 `BindingReader` 识别输入，只有 UI 构造线程能访问 JLine；worker 仅设置原子唤醒标志。内部事件在约 30ms 的限时 peek 周期后由 UI 线程观察，并使用专门的异常退出当前 `readLine`，把草稿和光标保存下来。然后 UI 线程才可处理审批请求，完成后以原文本及光标重新进入 JLine。
- PTY 驱动放在仓库中的普通可审查脚本里，独立使用 `target/phase2b3`，不继承 Provider API key 环境变量；先离线编译并运行现有 14 项定向 JUnit，再只启动这一个独立 proof。审批发出后、观察到 `WAKE_WITHOUT_ENTER` 之前不能写任何终端输入。检查审批不被当成 Steer、草稿与光标恢复、JLine 属性与 **JVM 仍存活时** 的 OS PTY termios，并将失败日志保留在 `target/phase2b3`。失败时强制停止只属于驱动的清理，不算通过。
- Mac 本机验证命令（需具备执行命令的已授权环境）：`python3 tools/run-jline-wakeup-proof.py`。只做编译可加 `--compile-only`。不要将脚本内容粘贴进未知的外部终端会话；普通命令运行即可。
- **结果尚未验证**：本轮使用文件接口创建原型和驱动，未运行 Maven、真实 PTY、Mac 人工输入或 `git status`；编译是否通过、事件是否实际唤醒、运行后终端属性是否恢复仍是未知。不能用设计推导代替执行结果。此原型还没有完整覆盖 Tab、Shift+Tab、方向键、独立 Esc、粘贴及 Mac 输入法。
- **Gate 仍为 FAIL**。先跑最小编译/PTY 并修复实际失败，再扩展按键回归；通过全部阶段条件前不接 `Main`、不进入 Phase3、不提交或推送。

#### Phase2B-3a：2026-10-24 一次真实验证及后续时序修正（修正待验证）

- 上次通过明确的一次性权限审批，Mac Codex 仅运行了 `python3 tools/run-jline-wakeup-proof.py` 一次，输出目录 `target/phase2b3/run-1792837258712217000/`。离线 Maven exit 0，JUnit 14/14；真实 PTY 证明了无 Enter 唤醒、审批隔离、草稿与光标恢复、JLine 对自身快照属性恢复。不过整体脚本 exit 1，系统级 `OS PTY termios not restored to pre-child snapshot`，不能称为 Gate 通过。
- 检查原始 `pty-wakeup.log` 和当时的两段代码后，发现一个明确的验证时序问题：旧的 `PROOF:PASS` 在 Java 的 `try (Terminal ...)` **内部**打印，而 Python 收到它立即执行 `termios.tcgetattr(slave)`，当时仍未执行 `Terminal.close()`。因此先前 OS 差异可能只是关闭前正常状态；尚无证据表明关闭后仍泄漏。
- 本次仅修改两处独立测试文件：`src/test/java/com/paicli/cli/JLineWakeupProof.java` 在退出 try-with-resources 后打印 `PROOF:TERMINAL_CLOSED`，在 JVM 保持存活期间等待父进程的属性核对及文件 ack，最后才发 `PROOF:PASS`；`tools/run-jline-wakeup-proof.py` 等待此关闭标记，记录所有未恢复的 `iflag/oflag/cflag/lflag/ispeed/ospeed/cc` 前后差异，然后确认/拒绝 ack。这是消除错误的验证时机，并非更改生产代码或放宽断言。
- 随后已提交一次限定单次执行的 Mac Codex 复验请求，但 Codex Provider 在执行命令之前返回 **usage limit**，任务 `agt_a5b3b172102c1bc54927d949` 失败。没有运行新版脚本、没有新版 `result.json`，也没有新的 JUnit/PTY 成绩；本次不再重复启动 Provider。
- 下一项必须先执行 `python3 tools/run-jline-wakeup-proof.py` 获取新的日志，严格核对 `TERMINAL_CLOSED` 后的系统属性和完整退出码。如果通过，之后再独立增加真实 PTY 的方向键、Esc、Tab/ShiftTab、多行粘贴、Ctrl+C/EOF 及 macOS 中文输入法体验的测试；不能因这组局部 proof 就直接接 `Main` 或进入 Phase3。
- **当前 Gate：FAIL（新的修正尚未实测）**。保持 Steering 默认关闭，保留所有用户已有未提交变更，不改业务代码，不 commit/push。

#### Phase2B-3a：终端关闭握手加固（2026-10-24，静态修改完成，未运行）

- 在最新验证代码中发现一个新的可靠性风险：`Terminal.close()` 之后，子进程仍使用终端标准输出发送 `PROOF:TERMINAL_CLOSED`，可能因输出通道关闭而造成假超时。
- 仅修改两份独立测试文件：`JLineWakeupProof.java` 在 Java `try (Terminal ...)` 已结束后，向本次测试专属的 `target/phase2b3/run-*/exit-ack.ready` 写入 `TERMINAL_CLOSED` 文件标记；`run-jline-wakeup-proof.py` 在有限时间内等待该文件并确认 JVM 尚未退出，然后再读取 OS PTY termios、记录 `iflag/oflag/cflag/lflag/ispeed/ospeed/cc` 的所有差异，通过时写 `exit-ack`、要求子进程正常退出且退出码为 0。失败清理绝不算验证通过。
- 这个握手不依赖终端关闭后的 stdout，也不跳过任何系统属性比较。原有 `run-1792837258712217000` 原始失败日志保留；未修改 Main、Agent、HITL 或生产运行路径。
- Mac 插件目前唯一授权的本机 Agent 运行时是 Codex；上一轮复验在真正执行前遭 Provider usage limit 拒绝。为避免重复无效调用，本轮没有再次启动 Codex，也没有产生新版 JUnit / PTY 验证结果。只允许恢复额度后单次运行 `python3 tools/run-jline-wakeup-proof.py`，检查新建 `run-*/result.json`。
- **Gate 仍为 FAIL / 新握手待复验**。确认关闭后的系统属性恢复、无 Enter 唤醒与正常退出都通过后，才推进完整键盘测试（方向键、Esc、Tab/Shift+Tab、括号粘贴、Ctrl+C/EOF）和 macOS 中文输入法人工体验；不提前进入 Phase3。

#### Phase2B-3a：10 月 24 日真实回归结果与下一阻塞（此前记录）

- 本日多轮真实 Mac PTY 测试均保留在 `target/phase2b3/run-*/`；**最新结果**为 `target/phase2b3/run-1792849940847342000/result.json`、同目录的 `maven.log`、`javac.log`、`pty-wakeup.log`。未覆盖老 run。Maven -o exit 0、JUnit **14/14 通过**、显式 javac exit 0。注意公开导出仓库的源码 mtime 可能比缓存类早，单靠 Maven `Nothing to compile` 会误用旧字节码，因此测试脚本现在必须强制编译独立 Java proof。
- 已证实的独立 PTY 结果：`WAKE_FLAG_SET`、不按 Enter 的 `WAKE_WITHOUT_ENTER`、审批隔离（不入 Steer）、中文草稿及光标恢复、JLine 自身属性恢复、`Terminal.close()` 完成后 `exit-ack.ready` 文件握手，均通过。关键轮询实现是 **UI 线程**独占 `BindingReader.readBinding(...,false)`，无输入时 `LockSupport.parkNanos(20ms)`，后台 worker 只设置原子 wake flag，不碰 Terminal。
- OS `tcgetattr` 的所有原始差异仅为 macOS `lflag` **1483 → 536872395**（异或 `0x20000000`，即 `PENDIN` 内核待重新显示输入的临时状态）。最新脚本同时保留 `os_attributes_exact=false`、完整原始差异和 `os_volatile_flags`，对除 PENDIN 外所有终端配置位严格比较，得到 `os_attributes=true`、`os_attribute_differences={}`；这是**有效配置恢复**，不能冒充所有状态位完全一致。
- **仍未通过的最后一项**：虽然子进程已经输出 `PROOF:PASS`，且 `exit-ack.ready` / `exit-ack` 都存在，测试驱动等待独立 Java JVM 在 5 秒内正常退出发生 `TimeoutExpired`；Mac 沙箱拒绝该驱动 `os.killpg`，报 `PermissionError: Operation not permitted`，随后的 6 秒 wait 也超时。整体脚本真实退出码仍为 **1**。只读外部 `ps` 无法确认该轮是否存在残留进程。不可将此轮报告为全部 PASS。
- 最新测试驱动已加固：在比较后无论配置是否一致都先写 ack；即使清理信号和 wait 失败也保留真实 PTY 断言和日志，不覆盖首个故障。生产 `Main.java`、`Agent.java`、`JLineInputAdapter.java` 等仍未接入/修改。
- **接下来先调查独立 JVM 的正常退出超时**，区分 Codex 沙箱进程等待/信号限制与 JLine exec terminal provider 的非 daemon I/O 线程。应在下一轮小范围实验中收集关闭后的存活线程或完整进程退出诊断；不宜简单 `System.exit(0)` 掩盖泄漏或把 `PROOF:PASS` 等同于 JVM 正常结束。再运行一轮确切 JDK/JLine 的独立 PTY 验证并确认无残留，最后补方向键、Esc、Tab/Shift+Tab、括号粘贴、Ctrl+C/EOF 及 Mac 输入法。
- **当前整体 Gate：FAIL**，虽然无 Enter 输入唤醒和有效属性恢复均有真实原型证据。不要提前接入 Main 或 Phase3；Steering 默认关闭，现有用户未提交文件保留，不 commit/push。

#### Phase2B-3a：10 月 24 日进一步追因与负对照（最新）

- 为了区分 JLine 泄漏与 macOS 沙箱进程问题，已仅在 `src/test/java/com/paicli/cli/JLineWakeupProof.java` 独立测试类增加线程诊断：唤醒事件超过 650ms 未被消费时由 daemon watchdog 保存 UI 线程栈到 `exit-ack.wake-diagnostic`，`main` 返回前保存非 daemon 线程到 `exit-ack.threads`，JVM 开始关闭后 850ms 保存**所有活跃线程（包括 daemon）**到 `exit-ack.shutdown-threads`；`tools/run-jline-wakeup-proof.py` 会在失败时保留这些日志。没有使用 `System.exit(0)` 绕过退出审计。
- 有实测：`target/phase2b3/run-1792851160288870000/`、`target/phase2b3/run-1792851352568460000/`、`target/phase2b3/run-1792851534986586000/`。三轮离线 Maven exit 0、JUnit 14/14、显式 javac exit 0；无 Enter 唤醒、审批隔离、草稿和光标恢复、JLine attrs 以及排除 PENDIN 临时位后的 OS 有效属性恢复通过，但独立 JVM 在 `PROOF:PASS` 后 `wait(timeout=5)` 仍超时，runner exit 1。
- `run-1792851352568460000/exit-ack.shutdown-threads` 显示 JVM 已开始关闭，`DestroyJavaVM` 在 `ApplicationShutdownHooks.runHooks()` 的 `Thread.join()` 等待；`main` 返回前只有自己这一条非 daemon。之后 `run-1792851534986586000/exit-ack.shutdown-threads` 在关闭 850ms 后观察到 `DestroyJavaVM` 仍在等待以及观察用 hook、JVM 系统 daemon 线程，但**未见可直接归罪于 JLine 的活跃 Java hook 线程**。仍需区分 Java 退出阶段和沙箱进程管控，不可断言 JLine 泄漏。
- 新增完全独立的 **负对照**：`tools/run-pty-java-exit-baseline.py`（不加载 JLine/PaiCLI，不运行 Maven/模型），直接将 JDK21 `java -version` 放进与原 proof 相同的 `pty.openpty + setsid + TIOCSCTTY` 环境。实测 `target/phase2b3/exit-baseline-1792851717274799000/result.json`：Java 已打印完整版本信息，但等待正常退出 8 秒超时；对该新建子进程 `terminate()` 后再等 2 秒依然超时。这证明退出异常**不要求 JLine 或 PaiCLI 参与**，原因可能在 Mac 沙箱、PTY/session 管理或 JVM 与其组合，尚不能区分三者。绝不能再把所有超时简单归为 JLine 资源泄漏。
- 最新一次 Codex 任务后续遇 Provider `usage limit`；负对照在 Provider 错误前已生成真实 `result.json`，因此测试结果以上述文件为准。沙箱退出/清理限制可能留下测试子进程，当前**未确认任何具体 PID 仍存活**；切勿根据旧日志中的 PID 直接发送杀进程信号。
- **下一步优先做本机普通 Terminal（非 Codex 沙箱）对照**：现在有单命令入口 `python3 tools/run-phase2b3-local-check.py`，它先运行旧的 `run-pty-java-exit-baseline.py`，严格确认 `java -version` 在控制终端 PTY 下得到实际 **exit 0 且无超时**，仅此前提成立才运行 `run-jline-wakeup-proof.py`。二者原始日志不覆盖，外层生成 `target/phase2b3/local-check-*/summary.json` 以及两阶段 stdout/stderr。基线失败时状态为 `BLOCKED_BY_JAVA_PTY_EXIT`、自动停止，不能因为打印了版本信息就放行；PTY proof 完整通过时才标记 `PROTOTYPE_PASS`，依旧不代表主程序集成。应使用普通 Terminal 运行，不在 Codex 沙箱内反复试跑。
- **目前 Phase2B-3a 整体 Gate：FAIL / 测试环境阻塞 + 唤醒稳定性待验证**。保留全部用户未提交改动，正式 Steering 仍默认关闭，不接 `Main.java`、不进入 Phase 3、不 commit/push。待完整 Gate 通过后再按矩阵补方向键、Esc、Tab/Shift+Tab、括号粘贴、Ctrl+C/EOF 与 macOS 中文输入法手工体验。

#### Phase2B-3a：2026-10-25 本机一键验收入口（已写代码、等待普通 Terminal 实测）

- 新增 `tools/run-phase2b3-local-check.py`：用用户 Mac 的 Python3 在工作区生成新的 `target/phase2b3/local-check-*/summary.json`，先独立运行最小 `java -version` 控制终端 PTY 负对照，核对**真实进程退出码**和超时标志；如果基线失败，禁止启动第二阶段。仅基线正常时，运行现成的离线 Maven 指定 JUnit14、显式 javac、独立 JLine4 PTY proof，检查完整闭环及无异常清理；输出稳定机器可读的 `BLOCKED_BY_JAVA_PTY_EXIT` / `PROTOTYPE_FAIL` / `PROTOTYPE_PASS`。
- 入口不会启动真实 PaiCLI 主程序、模型、联网或 Docker；不修改生产代码、提交历史或现有测试日志。原有脚本针对同一终端会话可能存在偶发唤醒超时、退出进程无法清理等风险，**不能重试挑选成功**。若外层或内层失败，依据最新 `summary.json`、`result.json` 与 PTY 原始输出排查。
- Mac ServerFS 目前只暴露 Codex Agent 命令执行，没有与用户普通 Terminal 等价的命令执行接口。因此入口由 MCP **直接创建并核实存在**，但尚未在非 Codex 沙箱的普通终端执行，尚无新复验结果；请不要把“新建入口”当成 Gate PASS。
- 后续只在基础环境退出与原型稳定性 Gate 成功后补方向键、Esc、Tab/Shift+Tab、粘贴、Ctrl+C/EOF、中文输入法等测试。正式 Steering 默认关闭，禁止提前接 `Main.java`、推进 Phase3 或 commit/push。

#### Phase2B-3a：2026-10-25 macOS CTTY 根因对照与首次完整原型 PASS（最新）

- 通过 Mac ServerFS/Codex 在**同一受限环境**内单次执行新工具 `tools/run-pty-java-exit-matrix.py`，日志 `target/phase2b3/exit-matrix-1792923407941765000/result.json`。五种独立 `java -version` 退出对照：普通文件 IO 191ms/exit0、文件IO+setsid 40ms/exit0、PTY 无 setsid 63ms/exit0、PTY+setsid 58ms/exit0；唯有 **PTY+setsid+TIOCSCTTY** 在6秒后超时，随后对该直接子进程 kill 并等待2秒仍无法回收。由此定位为该控制终端设置**及其与当前执行环境的组合**触发，不能归罪于 JLine 或 PaiCLI。没有确认旧 PID 仍存活，勿按日志 PID 盲目清理。
- 只修改独立 Python 测试驱动 `tools/run-jline-wakeup-proof.py`：PTY 子进程改用 `start_new_session=True`，**不再**执行 `TIOCSCTTY`，测试仍在真实 PTY 上进行，保留所有 JLine 草稿、审批、系统 termios 及 Java 正常退出断言；失败清理改为只对当次新建 JVM 的 PID 依次尝试 terminate/kill，不再向进程组发送信号。
- 单次执行产出 `target/phase2b3/run-1792923593200598000/result.json`、`maven.log` 和 PTY 日志：离线 Maven exit0、JUnit **14/14**、最新 javac exit0，真实 PTY `status=PASS`，无 Enter 唤醒、审批隔离、中文草稿和光标恢复、JLine 属性、`Terminal.close` 文件握手、OS 有效属性恢复**全部通过**；JVM `child_exit_code=0`、`child_reaped=true`、无异常清理。原始 `PENDIN` 单个位差异如实保留，`os_attributes_exact=false`，排除该内核临时状态位后其余 termios 属性无差异。**这是独立原型第一次完整通过，不代表正式主程序集成或稳定性 Gate。**
- 将 `tools/run-pty-java-exit-baseline.py` 与单命令入口 `tools/run-phase2b3-local-check.py` 的前置检测一致改为 `PTY+setsid`、不强制 `TIOCSCTTY`；有问题的 CTTY 负对照仍完整保存于独立 exit-matrix 工具和原始日志。此修改防止一键入口继续使用已定位的不兼容启动模式而无条件阻止测试。
- 后续 Gate：在一致的非 CTTY 模式下至少另做**一轮独立复验**（不能多跑挑成功），若通过再进入 Phase2B-3b 键盘矩阵（方向键、Esc、Tab/Shift+Tab、粘贴、Ctrl+C/EOF、中文输入法人工体验）。任何一项失败保留日志，修复具体问题而非调整超时或绕过断言。Steering 仍默认关闭，`Main.java`/`Agent.java` 均未接入，不推进 Phase3，不提交/推送。

#### Phase2B-3a / 3b：2026-10-25 第二轮完整验收与键盘测试进展（最新）

- **Phase2B-3a 独立原型连续两轮完整 PASS**，且均严格以真实 JVM exit0、正常回收、无 Enter 唤醒、审批隔离、草稿/光标及有效终端属性恢复为准：`target/phase2b3/run-1792923593200598000/result.json` 和第二轮 `target/phase2b3/local-check-1792923862113094000/summary.json`。第二轮前置 `java -version` 以 PTY+setsid、不强制控制终端自然 exit0，随后 Maven -o JUnit 14/14、显式 javac 0、PTY status PASS、child_exit_code 0、child_reaped true。与早前需 TIOCSCTTY 时的退出卡住形成可审计对照。**这仅通过独立原型阶段，不表示正式程序/Main集成通过。**
- 为推进 Phase2B-3b，新增 `src/test/java/com/paicli/cli/JLineKeyMatrixProof.java` 和 `tools/run-jline-key-matrix-proof.py`，完全独立于原有 WakeupProof。新的类文件编译到每次独有 `target/phase2b3/key-matrix-*/classes/`，不覆盖主原型的共有 build/test-classes；Python 在 PTY+setsid 无 TIOCSCTTY 模式下只驱动单个 JLine4 JVM，执行完成后核对是否正常退出、终端有效属性恢复，保留完整日志。
- 初始键盘矩阵 `target/phase2b3/key-matrix-1792924117660346000/` 编译成功但左方向键失败：驱动发送普通模式 `ESC[D`，而终端输出已进入应用方向键模式 `ESC[?1h`。调整模拟键序列为应用模式 `ESCOD` 后，后续 run 中 **方向键修改光标位置、不误触独立 Esc；独立 Esc；Tab/Shift+Tab；中文多行 bracketed paste** 四项均通过，代表真实 PTY 用户输入链的局部证据。
- `target/phase2b3/key-matrix-1792924361631922000/` 在 Ctrl+C 阶段超时：没有 `KEY:INTERRUPT_PASS`，所以 Ctrl+D、关闭握手、属性恢复阶段**均未执行**，不应标成各自已失败。`run-1792924555794582000/result.json` 的对应键盘日志明确记录 Ctrl+C 前 macOS PTY 为 `ISIG=true`、`ICANON=false`。后续 `run-1792924679815105000/result.json` 中测试驱动在发送 Ctrl+C 前临时清除 `ISIG`，记录 `ctrl_c_isig_at_send=false`，**仍未**得到 JLine 的 `UserInterruptException`，8秒超时，测试子 JVM 只被脚本对其自身 PID terminate 且已回收。这说明失败**不能仅归因于内核吞掉 VINTR**，仍需进一步检查 JLine 进入 raw 模式的时序/按键绑定/无控制终端下的信号行为。该临时清位只用于字节级诊断，不能冒充真实控制终端下的 Ctrl+C 验收。
- **Phase2B-3b 总体 Gate 继续 FAIL（4 项有成功证据，Ctrl+C 待解决，Ctrl+D 未触及，且尚无键盘矩阵收尾属性/退出结果）。** 下一步应做更细的字节接收与 active JLine keymap 诊断，区分 kernel 线路 vs JLine send-break widget；必要时用真正普通 Mac Terminal 实测 Ctrl+C / Ctrl+D 原生信号机制，避免再让 Codex 沙箱 CTTY 退出缺陷污染结论。完整键盘矩阵、中文输入法候选窗口人工体验和无残留退出验证都通过前，不接 Main/Agent、不启动 Phase3、不提交或推送。
- 仍保持原计划：输入控制仅由 UI 单线程操作 JLine，Worker 只设置唤醒标志；生产 Steering 默认关闭。历史全部失败日志保留，不能多跑挑成功、不抹去中间失败。

#### Phase2B-3b：2026-10-25 Ctrl+C 分层诊断、Ctrl+D 验收与原生终端入口（最新）

- 单次独立诊断 `target/phase2b3/key-matrix-1792934297476245000/result.json` 及对应 `pty-keyboard.log` 确认：`KEY:CTRL_C_ORIGINAL_BINDING_null`（所读 MAIN keymap 无默认 0x03 绑定）；诊断专用 widget 明确打印 `KEY:CTRL_C_BYTE_SEEN`；驱动发送 0x03 时 `ICANON=false`、`ISIG=false`。但委托 JLine `LineReader.SEND_BREAK` widget 后只听到终端铃声，没有 `UserInterruptException`；本轮完整键盘矩阵真实 `FAIL`。因此 Ctrl+C 字节**到达 JLine**，问题是动作/真实中断语义，不能再将故障仅归于内核吃掉输入，更不能把诊断绑定本身当成真实中断成功。
- 为避免一个尚未验证的原生 SIGINT 行为阻止其他按键评估，给**独立测试原型** `JLineKeyMatrixProof.java` 和驱动 `run-jline-key-matrix-proof.py` 加入显式 `--defer-ctrl-c` 分支。分支发出 `CTRL_C_DEFERRED`，不向该无控制终端 PTY 模拟 Ctrl+C，保持其为 **未验证**，并独立检查 Ctrl+D / EOF、`Terminal.close`、真实 `termios`、JVM 自然退出和子进程回收；整体必须标记 `PARTIAL_PASS_CTRL_C_UNVERIFIED`、脚本退出码 **2**，不得当作 Gate PASS。默认无参数仍执行严格 Ctrl+C 测试。
- 实测 `target/phase2b3/key-matrix-1792934544308921000/result.json`：离线独立 javac 退出码 0；方向键、独立 Esc、Tab/Shift+Tab、UTF-8 多行粘贴、**Ctrl+D/EndOfFileException**、关闭握手、有效终端配置恢复均通过（只存在 macOS PENDIN 这个已记录的内核临时状态位差异）；Java JVM 自然 `child_exit_code=0` 且 `child_reaped=true`。状态准确为 `PARTIAL_PASS_CTRL_C_UNVERIFIED`，Ctrl+C `false` 且明确 deferred，脚本按约定 exit 2。
- 新增仅用于用户**普通前台 macOS Terminal** 的最小验证类 `src/test/java/com/paicli/cli/JLineNativeCtrlProof.java` 和入口 `tools/run-jline-native-ctrl-check.py`。入口先检查 macOS、stdin/stdout 均为 TTY 且当前进程属于终端前台进程组，离线将该独立 Java 类编译到自己的 `target/phase2b3/native-ctrl-*/classes`，再使用 `os.execv` **直接替换 Python 为 Java**（不由 Python 模拟 PTY、也不强制 TIOCSCTTY），在真实 Terminal 提示用户分别按 Ctrl+C 与空行 Ctrl+D。真实 JLine 捕获 `UserInterruptException` 和 `EndOfFileException`，完成后写 `native-ctrl-*/state.txt` 的 `PASS_NATIVE_CTRL_C_AND_CTRL_D`；若进程被 SIGINT 杀掉或按 Enter 代替则不会写 PASS。它不替代后续中文输入法、窗口状态及用户操作验证。
- **注意**：该新 native 入口是通过 ServerFS MCP 已写入的代码，尚未在用户真正的 Mac Terminal 交互执行；Codex 沙箱不是等价的控制终端，绝不能让它跑该脚本去声称已验证。使用方法：在普通 Terminal 内 `cd /Users/a123/Projects/paicli-agentcli-public-export-20261021-6c9049d020 && python3 tools/run-jline-native-ctrl-check.py`。最后查看新 `target/phase2b3/native-ctrl-*/state.txt` 与编译结果；若 Ctrl+C 意外影响终端输入，先新开终端或在该终端执行 `stty sane`，不要盲杀其他 Java 进程。
- **Phase2B-3b 整体 Gate 仍未通过**：以上键盘剩余项已验证，但真实 Terminal Ctrl+C 和 macOS 中文输入法候选窗口行为待验，正式 Steering 不接入 `Main.java`/`Agent.java`，不进入 Phase3；保存全部旧日志和用户的未提交改动，不 commit/push。

#### Phase2B-3b：2026-10-25 Codex CLI 实际执行与 INT handler 代理验证（最新）

- 用户要求由 Codex CLI 执行。已启动 `agt_3f2d43b5ecd09ebe48582e5f`，在 Mac 的 Codex 沙箱里真实运行**一次** `python3 tools/run-jline-native-ctrl-check.py`：脚本退出码 **2**，原因是 `stdin.isatty=false`、`stdout.isatty=false`、`tcgetpgrp(0)` 返回 ENOTTY；按预检设计**未**启动交互 Java，也未创建 `native-ctrl-*` 产物。这是执行环境不具备前台控制终端，不是 PaiCLI 测试断言失败。不得用假 PTY 冒充原生 Ctrl+C 通过。
- 随后由 Codex 只读检查已缓存 JLine 4.0.0 jar 的真实字节码（任务 `agt_d54e97f6aee298943e450bea`），确认 `LineReader.SEND_BREAK` 实际是 `"abort"`，行为为清空输入、返回 false 并响铃，**不会抛 UserInterruptException**；之前将其作为中断断言属于**测试预期错误**。JLine 真正的 `INT` 信号处理路径会中断当前读行线程并让 `readLine` 转换为 `UserInterruptException`；在无控制终端的测试中，可通过 `terminal.raise(Terminal.Signal.INT)` 测该处理器，但绝不等价于 OS 前台键盘 Ctrl+C 投递。
- 仅在独立 `JLineKeyMatrixProof.java` 里将 Ctrl+C 诊断 widget 修成：`CTRL_C_BYTE_SEEN` 后调用 `terminal.raise(Terminal.Signal.INT)`，读行捕获 `UserInterruptException` 后输出 `INTERRUPT_SIGNAL_PROXY_PASS`；`tools/run-jline-key-matrix-proof.py` 仍需从真实 PTY 发送 0x03 且诊断时关 `ISIG`，但该分支强制输出 `ctrl_c_status=JLINE_INT_HANDLER_PROXY_ONLY`、`ctrl_c=false`（原生未通过），整体状态为 `PARTIAL_PASS_NATIVE_CTRL_C_UNVERIFIED`，脚本有意 exit **2**，不得返回 0。
- Codex 实际运行一次 `python3 tools/run-jline-key-matrix-proof.py`（任务 `agt_d05f91d311736006c9c3f5f7`），日志 `target/phase2b3/key-matrix-1792936480828632000/result.json` + `pty-keyboard.log`：离线 javac exit0；左箭头、Esc、Tab/ShiftTab、中文多行括号粘贴、`CTRL_C_BYTE_SEEN`、`INTERRUPT_SIGNAL_PROXY_PASS`、Ctrl+D EOF、`Terminal.close` 握手、macOS 有效 `termios` 配置恢复**全部通过**；原始 `PENDIN` 临时位差异仍保留。Java `child_exit_code=0` 且 `child_reaped=true`，`failure=null`。**这是 JLine INT handler 代理验证通过，而非真实 Ctrl+C 已通过。**
- **Phase2B-3b 整体 Gate 仍未通过**：真实 macOS 前台控制终端的 Ctrl+C 投递与中文输入法候选窗口交互暂未实测。已自动化部分不需要用户重复跑。保持正式 Steering 关闭，不改 Main/Agent，不推进 Phase3，且不提交或推送 Git。

#### Phase2B-3c：2026-10-26 独立唤醒机制提取、阻塞根因修复及双轮回归（最新）

- **新增独立正式源码** `src/main/java/com/paicli/cli/WakeableLineReader.java`，将上一版仅存在 `JLineWakeupProof` 中的 JLine 唤醒技术机制提取为可复用组件。UI 创建线程是唯一物理读取者；worker 只能调用线程安全的 `requestWake()` 设置 `AtomicBoolean`，绝不从 worker 调用 JLine/Terminal；唤醒以单独的 `Wakeup` 异常传递，而不是 Ctrl+C/EOF；`Draft(text,cursor)` 仅在 UI 线程保存/读取/恢复。**尚未接入 Main/Agent，也没有让 Steering 默认开启**。
- `src/test/java/com/paicli/cli/JLineWakeupProof.java` 已从私有 `EventLineReader` 切换到上述新类，真实 PTY 直接验证新的源码实现；`tools/run-jline-wakeup-proof.py` 显式 fresh javac 同时编译生产类和独立 proof，避免 Maven 旧 class 缓存假通过。项目仍在原仓库/分支，保留全部未提交文件。
- 首次提取后独立运行 `target/phase2b3/run-1793017312789239000/result.json`：Maven JUnit **14/14**、fresh javac 0，但 worker 设置 `WAKE_FLAG_SET` 后 8 秒没有 `WAKE_WITHOUT_ENTER`；PTY **FAIL**，仅对新建 JVM `terminate:reaped`，不算 Java 正常退出。**此失败已保留**，不能把之前旧原型偶然通过视为本类稳定。
- 随后通过 Codex **只读反查本机 JLine4.0.0 jar 字节码**：`BindingReader.readBinding(keys,local,false)` 第三参 false 只允许“本次已经读取至少一个字符”后的空返回；首次 `readCharacter` 会把内部每100ms过期继续循环，不保证外层20ms轮询。根因明确，不再靠超时调大掩盖。旧 proof 曾尝试 `terminal.reader().peek` 并出现阻塞，这次修复改为**同一个 `bindingReader.peekCharacter(20L)`** 先探测输入，再执行完整按键解析；在读取期间沿用 `LineReaderImpl` 的 `lock` 临时释放/`finally` 恢复，保留唯一读者。歧义 ESC 或 UTF-16 代理字符内部等待仍可能有额外延迟，不能声称全路径硬性20ms上限。
- **修复后固定代码做两轮独立回归**，均不额外输入 Enter，保留所有中间失败：`target/phase2b3/run-1793017700213724000/result.json`、`target/phase2b3/run-1793017839745515000/result.json`。两轮均离线 Maven 14/14、显式 javac exit0、PTY status PASS；`wake_without_enter` / `approval_isolated` / `draft_restored` / `jline_attributes` / `os_attributes` / `terminal_close_handshake` 均 true；child_exit_code=0、child_reaped=true、failure=null。macOS 内核 `PENDIN` 原始状态位变化依然保留在 raw attrs，排除该易变位后有效配置无差异。
- 同日 `target/phase2b3/key-matrix-1793016895133622000/result.json` 在无控制终端 PTY 下只向本次新建 Java 子进程的**确切 PID**发送真正 OS SIGINT，验证 JLine 的 `UserInterruptException` / 后续 Ctrl+D / Terminal.close / 退出正常：全部通过；脚本故意 `PARTIAL_PASS_NATIVE_CTRL_C_UNVERIFIED` / exit2，因为这仍不等于真实 Mac 前台键盘 Ctrl+C 触发的信号。
- **本阶段状态**：被提取的唤醒组件已通过两轮独立 macOS PTY 验证，具备下一步做更强失败注入/边界测试的依据；**整体 2B Gate 仍未完整关闭**，真实 Mac 控制终端 Ctrl+C/输入法组合态以及正式审批/计划等所有旧读取路径迁移未验收。继续默认关闭，先补空草稿唤醒、分段 Esc/emoji、竞态及 raw mode 测试，再评估是否得到授权进入 Main/Agent；不接 Phase3、不提交推送、不启动真实模型或 AgentBench。

#### Phase2B-3c：2026-10-26 首字符空输入无 Enter 唤醒边界验证（最新）

- 在**独立 PTY 测试** `JLineWakeupProof.java` 加入空草稿唤醒场景：第一段中文草稿/审批/光标恢复完成后，重新进入一个完全空白的 `readLine`；`CALLBACK_INIT` 确认输入框已初始化，worker 延迟150ms保证 UI 已进入空闲读取，之后 worker 仅设置 `requestWake()` 标志。UI 必须捕获 `Wakeup`，从新 `WakeableLineReader` 取得空字符串且 cursor=0 的草稿，并发出 `EMPTY_WAKE_WITHOUT_ENTER`。Python 驱动新增 `empty_wake_without_enter` 字段，整个阶段**不往 PTY 写任何输入字节**，仍保留原有无 Enter 审批场景。
- Codex 在 Mac 单次执行 `python3 tools/run-jline-wakeup-proof.py`，日志 `target/phase2b3/run-1793018092348971000/result.json` 和 `pty-wakeup.log`：离线 Maven 14/14、显式 javac0、`wake_without_enter=true`、`empty_wake_without_enter=true`、`approval_isolated=true`、`draft_restored=true`、`jline_attributes=true`、`os_attributes=true`、关闭握手成功、`child_exit_code=0`、`child_reaped=true`，`failure=null`、PTY总状态 `PASS`。未调大超时、不补 Enter、不启动第二读取器或真实模型。
- 本轮新增的正式类仍只被独立 proof 使用，未接 `Main.java`、`Agent.java`、渲染/审批旧读取路径；测试用 `peekCharacter(20L)` 仍有 ESC 歧义超时/组合输入等尚需关注的内部等待边界，不意味着真实 iTerm2/Terminal.app 中文输入法候选窗已经验证。整体 Gate 不提前标完整通过；生产开关默认关。无 commit/push/reset/clean。
- **2026-11-02 用户在真实 macOS 项目终端执行 `python3 tools/run-jline-key-matrix-proof.py`**，日志目录 `target/phase2b3/key-matrix-1793549776898118000/`。结果：fresh `javac_exit_code=0`；`split_arrow_wake=true`、`split_arrow_restore=true`、普通箭头、独立 Esc、Tab/ShiftTab、bracketed UTF-8 paste、Ctrl+D EOF、terminal-close handshake 均通过；`failure=null`；子 JVM `child_exit_code=0` 且 `child_reaped=true`。这确认 Phase2B-3c 分段方向键期间唤醒及草稿光标恢复回归通过。
- 本次 `production_wired=false` 是**预期标记**：proof 直接实例化并测试真实生产类 `WakeableLineReader`，但尚未接入 `Main.java` / `Agent.java` 的实际 CLI 运行路径。不能把它解释成测试没有使用生产类，也不能解释成正式产品已接线。
- Ctrl+C 仍明确未通过：`ctrl_c=false`、`ctrl_c_status=JLINE_INT_HANDLER_PROXY_ONLY`。脚本为了把 `0x03` 当作普通字节观察，先清除了终端 `ISIG`，因此 `ctrl_c_isig_at_send=false`；随后只验证了 JLine 的 `terminal.raise(INT)` 代理处理，不是前台控制终端把键盘 Ctrl+C 转成 OS SIGINT。`launch_mode=PTY + setsid WITHOUT TIOCSCTTY` 也不具备真实 controlling-tty 信号语义。整体 `PARTIAL_PASS_NATIVE_CTRL_C_UNVERIFIED` 和脚本退出码 2 是预期的保守结果，不是方向键回归失败。
- `termios_exact=false` 仅因 macOS `lflag` 原始值存在 `PENDIN` 易变位差异（before `1483` / after `536872395`）；`termios_config_differences={}` 且 `termios_config_restored=true`，有效终端配置已恢复。不得把 raw 属性逐位不相等误报为终端配置恢复失败。
- **2026-11-02 用户真实 Mac 前台 Terminal 人工验收通过**：执行 `python3 tools/run-jline-native-ctrl-check.py`，在 `ctrl+c>` 提示直接按 Ctrl+C，出现 `Ctrl+C caught as UserInterruptException.`；接着在空 `ctrl+d>` 提示按 Ctrl+D，出现 `Ctrl+D caught as EndOfFileException.`，最后打印 `PASS_NATIVE_CTRL_C_AND_CTRL_D` 并正常返回 shell。此证据由用户在当前对话提供，尚未单独读取 `native-ctrl-*/state.txt` 文件。本 smoke 使用**普通 JLine Reader**，验证真实控制终端的 Ctrl+C→JLine INT 和 Ctrl+D→EOF，不等于 `WakeableLineReader` 或 Main 的真实前台组合交互已通过。
- **尚未关闭的 Gate**：macOS 中文输入法候选窗口、真实 CLI 运行中审批/计划交接、所有旧 Reader 迁移以及 Main 端到端验证。`paicli.steering.enabled` 仍默认关闭。保留之前 `key-matrix` 脚本独立 PTY 结果中的 `ctrl_c=false` 和 `PARTIAL_PASS`：该脚本自己的覆盖范围并未因另一个人工 smoke 变成 FULL PASS。不 commit/push/reset/clean。

### Phase 3：接入 ReAct（首个可交付版本）
- 在 `agent/Agent.java` 引入安全点注入、消息 Ledger 事件、Atomic Finalize。
- 完整处理 tool_result 后再注入，不改变当前 LLM 已请求的工具协议。
- **验收**：使用 FakeLLM + BlockingTool 模拟 LLM 进行中、只读工具执行、写工具执行、工具错误、最终响应前插入等场景；确认 Steer 在**下一次**请求出现，Queue 只在下一 Turn，Esc 可正确停机。
- 出错时可通过配置关闭新功能恢复原行为。

#### Phase 3A：2026-10-27 ReAct 下一次请求安全点代码（已实现，待测试）

- **项目不再因 Phase2B 人工终端体验测试而停止 Phase3 的隔离开发**，但真正对用户启用 Steering 仍需全部安全验收。此前将真实 Mac Ctrl+C/中文输入法人工 Gate 等同于“Phase3 不准编写代码”，导致进度反复停在2B，本轮已纠正。
- 已实际新增 `src/main/java/com/paicli/agent/ReActSteeringSafePoint.java`，仅处理已进入 RUNNING 的 `TurnInputManager` 保管的用户 `STEER` 消息：下一次模型请求前 `reserveForRequest`，将原文追加到**本次调用的消息快照**，绝不修改已经发送的请求。尚未开启下一 Turn 的 `ENQUEUE` 不会被 reserve，也不混入当前请求。预留和状态由现有管理器同步保护。
- 已实际最小修改 `src/main/java/com/paicli/agent/Agent.java`，新增**包级可见**的显式 `configureSteeringSafePoint` 入口，主程序 `Main.java` 未接入。已有的 ReAct tool_result 完整追加在下一轮循环之前；新逻辑只在下一次 `llmClient.chat` 前构造临时历史快照。成功收到调用返回后才 `commit` 并向内存历史与 Ledger 追加 `runtime_steer_included` 的用户消息；若调用抛 `IOException` 或 `RuntimeException`，按结果可能已送达的保守原则 `markDeliveryUnknown`，不把未确定内容写入正式对话历史，不自动 requeue / 重发。
- `TurnToolPolicy` 仍以该 Turn 原始用户提交内容决定工具和 URL 授权，不因为新增 Steer 中出现 URL 就自动扩权。没有改已请求工具的 tool_call/tool_result 顺序，没有改渲染/审批旧输入者、Agent 主启动路径或默认关闭开关。
- 新增 `src/test/java/com/paicli/agent/AgentSteeringSafePointTest.java`（JUnit FakeLLM，目标覆盖：第1次模型请求中接收Steer后只在第2次且完整工具结果后出现；Queue 不混入当前 Turn；第2次模型异常按 DELIVERY_UNCERTAIN 且不留影子消息；默认 Agent 无新入口维持原行为）。
- **2026-11-02 Phase3A 离线验收通过**：`AgentSteeringSafePointTest` 5/5，`AgentBudgetFinalizationTest` 1/1，`git diff --check` 通过。首次 Maven 命令因沙箱无法读取本机 Maven 锁文件而停止；随后只批准本机缓存读写、使用 `-o` 离线模式完成测试，没有网络访问、真实 Provider、Docker、AgentBench 或全量测试。
- **Phase3 已实际开始开发，Phase3A 已完成定向验收，但尚未接入 Main；Phase2B 的原生键盘/IME Gate 仍未完整通过。**
- Phase3A 补充了 `steerCannotGrantWebToolsOrUrlAccess` 回归：原始 Turn 明确禁止联网时，即使后续 Steer 文本带 URL 并要求 `web_fetch`，两次模型请求的工具清单都不出现联网工具。仅修改 `AgentSteeringSafePointTest.java`；没有发现生产实现错误，`Main.java` 和终端输入/审批代码未改，其他既有 dirty/untracked 文件未被本任务触碰。
- **2026-11-02 Phase3B-1 已完成并形成 GitHub 快照**：显式启用测试路径的 Agent 自然收尾时复用 `TurnInputManager.finalizeOrContinue/completeTurn`；Steer 先到则继续下一次请求，结束先到则拒绝迟到 Steer，取消优先；Queue 不混入当前请求。先经 FakeLLM 8/8、预算回归 1/1 验证，再与 Hao CLI 品牌修改、PTY proof 一起运行离线组合回归 67/67。已提交并推送到用户仓库 `SuperGh2233/AgentCli` 的 `feat/steering-phase2b` 分支，快照为 `1fe8a9dabe21735db7378f23de6ca2dff1b83828`（33 个文件）；Main 未接线。
- **2026-11-02 Phase3B-2 已完成独立代码修改与限定回归，尚未提交推送**：在 `TurnInputManager` 中新增同锁同步的每工具调用 `claimToolStart`，与 `accept(INTERRUPT)` 原子裁决并拒绝重放调用 ID。显式配置 Steering 的 Agent 经 `TurnToolPolicy` 把逐调用 Gate 传给 `ToolRegistry`；串行与并行工具在实际执行器入口前领取资格，HITL 先完成审批，然后在执行前检查。取消后尚未启动的调用返回带原调用 ID 的失败结果，已领取资格的工具保留真实结果及 tool_call/tool_result 顺序，原有未配置 Steering 的 Agent 路径保持不变。新增 `AgentSteeringToolCancelTest`、`ToolRegistryToolStartGateTest` 和管理器状态测试；先记录 3 个失败复现，修复后 **117/117 离线定向回归通过**，`git diff --check` 通过，日志存于 `target/phase3b2-red-assertions.log` 和 `target/phase3b2-final.log`。本轮仅涉及 5 个生产 Java 文件与 3 个测试文件，不改 Main/HITL 生产代码，无真实模型、网络或 Docker。
- **剩余 Gate**：已验证“审批期间取消 → 后续即使批准也不执行”，但取消暂不主动关闭阻塞中的 HITL 审批等待；4 项 macOS 沙箱测试、可能依赖真实搜索服务的旧测试和性能压测未执行。后续需要完善审批输入归属、Steer/ENQUEUE/Y/N 路由与真实中文输入法及 Main 端到端验证，才能考虑正式开启 Steering。Phase3B-2 的本地改动保留，不自行提交或推送。

#### Phase3B-3A：2026-11-02 统一 ReAct 运行输入与 HITL 审批（代码已接线，离线定向回归通过；原生终端验收待完成）

- 新增 `cli/SteeringRunLoop.java`：仅在 `paicli.steering.enabled=true`、交互式 inline Renderer、真实控制台和 `WakeableLineReader` 同时满足时使用。UI 主线程是唯一 JLine 读取者；ReAct worker 通过 `TerminalInputGateway` 请求审批输入，不能直接读取 Terminal/stdin。默认关闭、Plan/Team、Lanterna 和非交互路径仍走原逻辑。
- `Main` 先创建实际 Renderer，只有最终选中 inline 且为真实交互控制台时才创建 `WakeableLineReader`，并复用原有 `PaiCliHistory`、`PaiCliCompleter` 和 `PaiCliHighlighter`；Plain/Lanterna/非交互回退不改变原 Reader。Steering 运行期间统一路由普通文本、`/steer`、`/queue`、`/inputs`、`/cancel`；运行输入模式下独立 Esc 触发取消，审批模式下 Esc 只清空当前子提示，Ctrl+C 才取消整轮。审批选项及拒绝原因、修改 JSON、批准范围都由同一 JLine Reader 读取。普通输入和审批输入提交后都会清除编辑态回显；已接收的 Steer/Queue 会以脱敏后的用户消息形式写入可见对话记录。审批期间普通输入不会进入当前模型请求；输入模式在 Enter 前变化，或 Turn 已在 Enter 前完成原子收尾时，都会返回 RETRY 并把原文恢复为草稿，必须再次确认。
- `InlineRenderer` 只在这类 ReAct Turn 运行期间绑定 `TerminalInputGateway`；worker 的 HITL 提示通过请求桥等待，默认路径仍保留原实现。`JLineInputAdapter` 将 Ctrl+C 转成独立的取消失败状态，审批按拒绝处理并由运行循环取消整轮；EOF 不会伪装成空 Enter。审批内部唤醒会保留子提示草稿，运行中被审批打断的草稿和光标会带回下一次普通提示。审批超时若发生在 UI 正读行期间，超时原因会随 Dispatch 返回；为防止迟到的 y/n 混入 Steer，下一行会被明确隔离，用户需重新输入。
- `Agent.configureSteeringSafePoint` 现在由 `Main` 显式按 Turn 安装，并在执行线程确认停止后清除，避免后续 Plan/Team 或旧路径复用已结束的安全点。预算收尾请求期间如果又收到 Steer，会继续以无工具请求处理，直到输入窗口原子关闭；中间收尾响应会先写入历史，并重置流式渲染状态，再发起下一次无工具请求，避免上下文或终端输出串接。
- `/queue` 会进入 `TurnInputManager` 的下一 Turn 队列，并且不会混入当前模型请求；本阶段**不自动启动排队任务**，只显示队列数量并明确告知用户。队列内容仍仅保存在当前进程内存，不承诺跨进程恢复。
- 新增 `SteeringRunLoopTest`，覆盖普通文本作为 Steer、Queue 与当前 Turn 隔离及可见回显、HITL 选择和拒绝原因复用同一 UI Reader、审批唤醒后恢复运行草稿、独立 Esc 与 `/cancel` 停止 worker 并暂停队列。`InputCoordinatorTest` 覆盖 Turn 在 Enter 前收尾时将输入保留为草稿。`AgentSteeringBudgetTest` 新增预算收尾请求期间收到 Steer 的回归，确认下一次无工具请求保留上一条助手收尾结果。`JLineInputAdapterTest` 和 `TerminalInputGatewayWakeupTest` 同步更新 Ctrl+C 语义断言。
- **2026-11-02 Phase3B-3A 离线定向回归通过**：通过 `tools/run-phase3b3-tests.py` 使用 JDK 21、Maven `-o` 离线模式和白名单子进程环境执行；398 个主源码、347 个测试源码编译通过，Surefire 12 suites / 94 tests，failures=0、errors=0、skipped=0，`BUILD SUCCESS`。报告：`target/phase3b3/run-1793598140874235000/summary.json`；日志：`target/phase3b3/run-1793598140874235000/maven.log`。独立构建目录位于 `target/phase3b3/run-*`，不覆盖 `target/classes` / `target/test-classes`；临时 POM 放在仓库根目录并在 Maven 结束后删除，若同名文件已存在则拒绝覆盖。该回归未运行 Main、未调用真实 Provider/API、未访问网络、未使用 Docker，也未执行 Git 写操作。
- **2026-11-02 原生终端验收包已准备**：使用 `mvn -o -s target/publication-check/settings.xml -DskipTests package` 离线构建成功（`BUILD SUCCESS`），生成 `target/paicli-1.0-SNAPSHOT.jar`（35,702,713 bytes，约 34.05 MiB）。必须保留 `-s target/publication-check/settings.xml`，否则本地缓存中的 `maven-resources-plugin:3.5.0` 因 repository ID 不匹配无法离线解析；不要加 `clean`，以保留 Phase3B-3A 测试日志。真实终端验收命令：`java -Dpaicli.steering.enabled=true -jar target/paicli-1.0-SNAPSHOT.jar`；该命令尚未执行，须由用户在真实 macOS Terminal 交互运行。
- **2026-11-02 回归问题修复记录**：用户首次在 Mac Terminal 运行时，Maven 报 `SteeringRunLoop.java` 中 JLine `readLine` 的 `Character` / `MaskingCallback` 重载歧义；显式转换为 `(MaskingCallback) null` 后发现另一个遗漏的 `InterruptedException` 处理，均已修复。首次进入 JUnit 后 94 项中 5 项报错，定位为测试夹具：PipedInputStream 写入线程提前退出（`Write end dead`）、换行转义错误，以及审批输入早于 JLine 子提示接管。修正测试线程生命周期、真实换行和按提示发送输入后，最终 94/94 通过。此前 ServerFS 直接执行曾因工作区权限在 Maven 启动前遇到 `PermissionError`；后续由授权的 Codex workspace Agent 在同一工作区成功完成离线验收。
- **当前 Gate：Phase3B-3A 代码接线及离线回归已验收；原生交互验收仍未完成**。仍需在真实 macOS Terminal 中用 `paicli.steering.enabled=true` 做受控端到端测试，覆盖 WakeableLineReader + Main 的组合、Ctrl+C/Ctrl+D、中文输入法候选窗、审批/Steer 输入归属及取消后的 worker 停止；普通 JLine 的单独按键证明不能替代这些组合测试。完成前 Steering 默认开关继续保持 `false`，不 commit/push/reset/clean。

### Phase 4：Plan-and-Execute
- 增加计划约束修订和批次安全检查点，不静默重跑已完成节点。
- **验收**：DAG A、B 并行、C 依赖 A/B 时，Steer 调整后续要求只影响未启动步骤；旧任务状态、审批、失败重规划保持正确。

### Phase 5：Multi-Agent / 任务持久化（增强）
- 主控传播约束版本到 Worker，防重复处理；可靠校验工具写入冲突。
- 使用 SQLite 创建专门的 `runtime_input_messages` 表持久化未交付消息和状态，不把 JSONL `ConversationLedger` 当事务队列。
- 崩溃恢复后将含副作用的未完成工具标记 `UNKNOWN`，先对账再继续；不能直接复用 `DurableTaskManager.recoverRunningTasks` 的 RUNNING→ENQUEUED 来执行写操作。
- **验收**：杀进程/重启，待处理消息保留、已执行工具不会被自动重放，任务状态可解释。

### Phase 6：评测与体验优化
- 真实模型评测才比较新增 Steering 前后多轮任务成功率、输入延迟、工具调用次数、上下文 Token。
- 添加可观测指标：`steer_received_to_included_ms`、pending queue depth、Stale Turn 次数、Interrupt 到停止时间、协议不完整次数（期望 0）。
- 侧边提问（`/btw`）、原生模型 Steering、跨渠道同步及“即时中断当前工具”留到独立版本；**本轮不做**。

## 8. 必须覆盖的测试矩阵

| ID | 场景 | 预期 |
|---|---|---|
| T01 | LLM 请求中收到 Steer | 不修改已发请求；在下一个合法模型请求中出现且只出现一次 |
| T02 | 工具批次中收到 Steer | 已开始工具按当前取消策略收尾，tool_call / tool_result 配对完整 |
| T03 | 回复将结束时同时收到 Steer | 原子判断：要么进入活动 Turn、要么明确拒绝为 stale；不丢消息 |
| T04 | 当前任务运行时 Queue 两条 | FIFO，当前任务结束后顺序执行，不混入当前 Prompt |
| T05 | HITL 正在等待 Y/N | 用户输入被审批界面接管，不被误当作 Steer |
| T06 | Esc 与新消息并发 | Interrupt 优先停止新危险工具；Queue 保留但暂停执行 |
| T07 | 向下传入包含 URL 的 Steer | 只有可信用户原文能授予 URL 权限，MCP/网页文本不能 |
| T08 | 模型返回并行 3 个工具调用 | 每个 call_id 都有对应结果；不能因 Steer 漏一个 |
| T09 | 两条相同消息 ID 重发 | 去重，状态可查询 |
| T10 | 消息在摘要压缩之前到达 | 最新约束原文保留到送入模型 |
| T11 | 改变 Plan 尚未执行的节点 | 已完成节点保留，不静默重跑、DAG 无环 |
| T12 | Team 两个 Worker 并行 | 约束版本正确、无重复广播、跨 Worker 无权限升级 |
| T13 | 中文输入/方向键/多行粘贴 | 不误判 ESC，不丢键、不破坏渲染 |
| T14 | 服务不可用 / LLM 超时 | Pending 消息状态明确，可重试，不声称“已执行” |
| T15 | 运行期工具产生副作用后中断 | 显示已完成副作用与回滚选项，不自动假装撤销 |
| T16 | 重启时存在消息/未完成写操作 | 后续持久化阶段对账后恢复，写操作不能盲重放 |

测试优先使用现有 JUnit 5、FakeLLM、可阻塞工具桩。必须有“先失败、后修复”的回归测试；真实 Provider 测试受预算和显式授权约束。

## 9. 研发协作规约（后续所有阶段执行）

每个 Phase 使用同一个工作方式：
1. 先阅读本文件和对应源码，输出变更文件清单、风险、预计影响的测试。
2. 只做当前 Phase，不擅自追加功能或重构非相关模块。
3. 先写最小失败用例，再实施最小修改，跑定向测试和 `mvn test -Pquick`（测试命令与环境如有差异先说明）。
4. 运行完成记录：代码变更、测试命令与真实结果、失败原因、剩余风险、下一阶段 Gate。
5. 不改历史评测数据，不声称未经验证的性能提升。
6. 不自动提交/推送、不删除用户文件、不启动 Codex CLI Agent，除非用户单独授权。
7. 严格遵循 repo 内 `AGENTS.md` 的编码约定，同时以本文件为 Steering 特性验收依据。

## 10. 明确不做的事情

- 不修改已经发送出去的 LLM API 请求文本；不使用 Prompt 拼接模拟同步取消。
- 不让 Steer 绕过工具权限审批、MCP 信任或沙箱隔离。
- 不并行读取同一个 Terminal 的原始字节流。
- 不把“收到消息”显示成“任务已按新指令修改成功”。
- 不要求第一版解决重启后任意位置继续执行、Team Worker 级抢占、原生 API Steering、侧边问答。
- 不在没有完整证据前宣传与 Codex / Claude Code 具有完全一致的中断语义。

## 11. 开始开发时使用的任务指令（模板）

> 请读取 `docs/mid-turn-steering-implementation-plan.md` 及当前 `AGENTS.md`，只执行 **Phase 0**：冻结基线、核实相关类与测试、建立最小确定性场景清单。此阶段不修改业务代码。输出当前 HEAD、工作区状态、实际架构差异、测试命令及下一阶段准入意见。没有我的明确授权，不自动进入 Phase 1。

阶段 0 完成且确认后，再按照同样的“先验收、后进入下阶段”机制逐步推进。

## 12. 参考依据（仅作设计参考）

- Codex 开源实现：`codex-rs/core/src/session/input_queue.rs`： https://github.com/openai/codex/blob/main/codex-rs/core/src/session/input_queue.rs
- Codex 开源实现：`codex-rs/core/src/session/turn.rs`： https://github.com/openai/codex/blob/main/codex-rs/core/src/session/turn.rs
- OpenAI Codex Queue / Steer 使用说明： https://developers.openai.com/blog/mastering-codex-remote-for-engineering
- OpenAI API Steering 语义（仅针对支持的 Responses WebSocket API；PaiCLI 本方案不依赖该服务端能力）： https://developers.openai.com/api/docs/guides/deployment-checklist

> 以上来源为设计参考；本方案的线程模型、默认按键、状态机、持久化结构和实现阶段均属于 **PaiCLI 的拟议设计**，不是声称 Codex / Claude Code 内部与之逐项相同。
