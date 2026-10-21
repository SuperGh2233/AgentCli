# PaiCLI Mid-turn Steering：Phase 1 实施与验收报告

> 日期：2026-10-21
> 工作分支：`feat/steering-phase1`
> Git 初始基线：`d25ead1ed15a270944cb11de54eb47814e2adc83`
> 阶段结论：**Phase 1（纯 Java 消息队列与 Turn 状态机）已完成并通过定向测试；Phase 2、3 尚未开始。**

## 1. 前置基线

- 在 Mac 上为仅有本地源码的 `paicli-main` 建立完整备份（922 文件/197 子目录，约 17.7 MiB）与本地 Git 初始提交，无远程仓库。
- Homebrew Maven 3.10.0 已安装，当前阶段显式使用 Temurin JDK 21.0.12.1（项目 `pom.xml` 目标 Java 17）。
- Phase 0 核心烟雾测试：`CliCommandParserTest`（57）、`PlanReviewInputParserTest`（4）、`MainInputNormalizationTest`（30）、`CancellationContextTest`（1）、`ConversationLedgerTest`（5）、`TerminalHitlHandlerTest`（18），共 **115/115 通过**。
- 注意：完整 `mvn test -Pquick` 未全部通过。此前受限大范围运行生成 847 个用例，804 通过、1 失败、42 异常，测试在 `SessionModeControllerTest` 停滞并被限时终止；异常主要为 Java 25 与 Byte Buddy 版本兼容，未修改原有业务源码。该记录不等于完整回归通过。

## 2. 本次新增文件（已写入 Mac 本地项目）

```text
src/main/java/com/paicli/runtime/input/
  DeliveryMode.java
  InputAcceptance.java
  InputStatus.java
  RuntimeInput.java
  TurnInputManager.java
  TurnState.java
src/test/java/com/paicli/runtime/input/
  TurnInputManagerTest.java
```

仅新增以上 7 个 Java 文件；原有 `Agent.java`、`Main.java`、`ToolRegistry`、`pom.xml` 和功能模块均未修改。另有测试临时脚本 `target/run-phase1-tests.py` 和日志，属于被忽略的构建产物。无自动提交、推送或远程仓库。

## 3. 实现行为

- `TurnInputManager` 是 **per-session 内存管理器**，使用同步监视器完成消息接受、去重、限额、FIFO、Turn 状态转移和原子 finalize 检查。
- `STEER` 只接受匹配活动 `expectedTurnId` 的输入；`ENQUEUE` 存在独立队列，当前 Turn 完成后每次取出一条开新 Turn；`INTERRUPT` 请求取消并暂停 Queue，完成取消后显式恢复。
- 交付协议：`accept` → `reserveForRequest` → `commit`（已纳入请求）或 `requeue`（**确认请求尚未发出时**）；防止跨管理器提交、旧批次重复使用。
- 默认限额：每 Turn Steer 20 条、Queue 50 条、消息内容 UTF-8 16 KiB，配置项可调整；无效/过期消息显式拒绝而非隐式改投下一轮。
- 功能开关 `paicli.steering.enabled` **默认 false**。没有 JLine 输入路由，没有 Agent Loop 注入，更不支持实际运行中收发用户消息。
- `INCLUDED_IN_REQUEST` 仅表示消息进入已提交的模型请求，不能表述为模型已遵循。
- 存储仅限内存，**未实现进程重启后恢复、Ledger 持久化或独立事件写入**；这属于后续阶段。

## 4. 真实测试结果

Phase 1 新增 `TurnInputManagerTest` **21 例**，包含去重、FIFO、Steer 与 Queue 隔离、字节限额、状态转移、预约/提交/退回、取消队列保留、默认关闭，以及 300 次固定种子并发 finalize/accept 交错。

使用 `target/run-phase1-tests.py` 调用 JDK 21 与 Maven 3.10.0 的离线定向回归（Maven settings 与 `~/.m2` 缓存仓库 ID 一致，清理 provider API keys，home/tmp 隔离至 `target`）。

**最终结果：7 类 / 136 测试；通过 136、失败 0、错误 0、跳过 0；退出码 0，耗时约 7.71s。**

本次成功来自本地静态/模拟测试，未调用真实模型、MCP 服务、Docker 或 AgentBench；不能据此声称实现了实际 CLI Steering。

## 5. 限制与后续阶段门槛

1. `messages` 去重索引与 `usedTurnIds` 在一个管理器生命周期内持续保留，目前无 TTL/内存上限；长寿命会话需在持久化阶段定义垃圾回收和去重窗口。
2. Phase 2 前重点检查 `Main.runWithCancelSupport` / `readEscCancel`、JLine、`TerminalInputLock`、HITL 审批和 Plan 审阅。必须由单一输入所有者读取终端，禁止新增第二个 raw-reader 抢字节。
3. Phase 2 应通过伪终端与 macOS 手动回归验证中文、方向键、粘贴、Esc、Tab、审批模式。Phase 3 之前不得开启生产 Steering。
4. Phase 3 需要把实际 `ToolCallId` 与 `tool_result` 完整配对，新增可信用户消息安全策略；不得在外部工具执行期间直接修改 History。
5. Phase 1 代码尚未提交：使用 `git status` 进一步审核后决定是否形成单独本地提交。**不得自动 push。**

> 开发依据：`docs/mid-turn-steering-implementation-plan.md`。
