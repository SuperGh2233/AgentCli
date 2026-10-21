# PaiCLI 执行中动态指令（Mid-turn Steering）与任务控制：设计及实施计划

> 状态：**Phase1 已实现；Phase2A 独立协调组件已验证，终端尚未接线** · 编写日期：2026-10-20；更新：2026-10-21
> 适用仓库：`projects/paicli-main`  
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

### Phase 3：接入 ReAct（首个可交付版本）
- 在 `agent/Agent.java` 引入安全点注入、消息 Ledger 事件、Atomic Finalize。
- 完整处理 tool_result 后再注入，不改变当前 LLM 已请求的工具协议。
- **验收**：使用 FakeLLM + BlockingTool 模拟 LLM 进行中、只读工具执行、写工具执行、工具错误、最终响应前插入等场景；确认 Steer 在**下一次**请求出现，Queue 只在下一 Turn，Esc 可正确停机。
- 出错时可通过配置关闭新功能恢复原行为。

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
