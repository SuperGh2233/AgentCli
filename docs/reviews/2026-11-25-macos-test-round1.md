# 本机 macOS 测试报告（第 1 轮）

日期：2026-11-25 · 测试机：macOS（Apple Silicon）· 被测版本：`feat/steering-phase2b` @ `0ebb6fa` + 工作区未提交的 Plan Steering 改动（793 行）
测试环境：JDK 25.0.4.1（Maven 默认）/ JDK 21.0.12.1（对照）· Maven 3.10.0

## 一句话结论

新功能（Plan Steering 安全点）本身 96 个针对性测试全绿；但**在本机 macOS 上 `mvn test -Pquick` 无法一次跑完**：15 个测试因为「目录不能包含符号链接」的检查挂在 macOS 的 `/var` 上而失败，3 个终端相关测试类在 JDK 25 下会挂死（JDK 21 下 26 秒跑完），另有 1 个路径归一化测试失败。这些问题在 Linux CI 上大概率看不到。

## 量化结果

| 运行方式 | 结果 | 耗时 | 备注 |
|---|---|---|---|
| 主代码编译 `mvn clean compile` | 通过 | 17s | 无警告阻塞 |
| 新功能针对性测试（6 个类） | 96 通过 / 0 失败 | 10s | 见下表 |
| `mvn test -Pquick`（JDK 25） | **未跑完，挂死 3 次** | >20min | 卡在 `JniNativePty.close` |
| `mvn test -Pquick`（JDK 21） | 1707 个，16 个 error | 26s | 无挂起 |
| JDK 25 下单独复现 MCP trust 三个类 | 36 个中 13 个 error | 32s | 12 个同一根因 |

针对性测试明细（全部通过）：

| 测试类 | 数量 |
|---|---|
| `PlanSteeringSafePointTest` | 17 |
| `TurnToolPolicyTest` | 39 |
| `ExecutionPlanTest` | 13 |
| `PlanExecuteAgentTest` | 11 |
| `ReActLoopReplayTest` | 9 |
| `PlannerTest` | 7 |

## P0：MCP trust 的符号链接检查在 macOS 上必然失败

**现象**：15 个测试报 `java.io.IOException: MCP trust 目录不能包含符号链接`。

**位置**：`src/main/java/com/paicli/mcp/config/McpTrustStore.java:256-259`

```java
for (Path current = directory; current != null; current = current.getParent()) {
    if (Files.isSymbolicLink(current)) throw new IOException("MCP trust 目录不能包含符号链接");
}
```

**根因**：这段从目标目录一路检查到 `/`，只要祖先链上有任何一级是符号链接就拒绝。而 macOS 上：

```
/var -> private/var
/tmp -> private/tmp
java.io.tmpdir = /var/folders/w8/.../T/
```

所以任何位于 `$TMPDIR` 下的路径（`@TempDir` 全部）、以及用户把 `.paicli` 放在 `/tmp` 或经过符号链接的位置时，MCP trust 直接不可用。这不是测试写法问题，是生产代码的判定过严。

**已验证的修法**：先归一化再检查。实测 `dir.toRealPath()` 之后整条祖先链不再有符号链接：

```
raw      = /var/folders/w8/.../symprobe...
realpath = /private/var/folders/w8/.../symprobe...
realpath chain has symlink = false
```

建议改成：对已存在的祖先用 `toRealPath()` 归一化后再逐级 `isSymbolicLink`；对尚不存在的末段单独校验。这样既保留「不接受用户自己造的软链」的本意，也不会把系统级挂载点当攻击。

**影响**：`McpTrustStoreTest`(4)、`McpServerManagerTrustTest`(10→JDK21 下 10)、`McpServerManagerTest`(2)、`McpConfigLoaderTest`(1)。

## P1：ResourceFailureObserver 的绝对路径归一化在 macOS 上静默失效

**现象**：`ResourceFailureObserverTest.varyingReplacementAndNormalizedKnownPathProduceOneObservation` 报 `NoSuchElementException: No value present`（`orElseThrow`）。

**位置**：`src/main/java/com/paicli/agent/ResourceFailureObserver.java:65-69`

```java
Path safeRoot = root.toRealPath();              // /private/var/...
Path lexical = safeRoot.resolve(raw).normalize(); // 绝对路径时 raw 原样保留：/var/...
if (!lexical.startsWith(safeRoot)) return null;   // /var/... 不以 /private/var/... 开头 → 跳过
```

**根因**：`safeRoot` 已解析成真实路径，但用户传入的绝对路径没解析，两者前缀对不上，于是这次失败被当成「无法识别」直接跳过。相对路径（`a`、`./a`）不受影响，所以只有绝对路径那一步失败。

**影响**：仅导致重复失败计数偏少（安全检测被削弱），不会误报。修法同样是先对 `lexical` 做 `toRealPath()` 再比 `startsWith`，或者两边都用归一化后的路径比较。

## P2：JDK 25 下终端相关测试挂死；Mockito 在 JDK 25 下多报一个错

**挂死现象**：`mvn test -Pquick` 在 JDK 25 下卡住超过 20 分钟，日志停在某个终端测试类不再增长。三个类命中同一处：

| 卡住的测试类 | 线程栈位置 |
|---|---|
| `SteeringRunLoopTest.hitlChoiceAndRejectionReasonUseTheSameUiReader` | `JniNativePty.close` → `FileDescriptor.close0`（native） |
| `SessionModeControllerTest` | 同上（日志停止增长，`jstack` attach 握手失败） |
| `TerminalInputGatewayWakeupTest` | 同上 |

单独跑这三个类都秒过（`SteeringRunLoopTest` 整类 4.3 秒），只有全量、同 JVM 复用跑时才挂。已排除 `.type("dumb")` 的影响：探针显示加不加 `type("dumb")` 建出来的都是 `ExternalTerminal`，底层都是 JNI PTY。

**对照结论**：同一份代码用 JDK 21 跑 `mvn test -Pquick`，1707 个测试 **26 秒完成、零挂起**。所以这是 JDK 25 + JLine 4.0.0（`jdk11` classifier）的组合问题，不是这批业务改动的锅。

**Mockito**：JDK 25 下 `McpElicitationCapabilitiesTest.plainSupportsBothAndDefaultRendererStaysEmpty` 额外失败：

```
org.mockito.exceptions.base.MockitoException: Cannot call abstract real method on java object!
```

该用例对接口 `Renderer` 用了 `mock(Renderer.class, CALLS_REAL_METHODS)`，JDK 21 下通过、JDK 25 下失败。当前 Mockito 5.11.0 的 ByteBuddy 对 JDK 25 支持不足，升级到 5.14+ 或改写该断言即可。

**建议**：给 CI 和日常验证固定 JDK 21（pom 的 `maven.compiler.release` 是 17）；要用 JDK 25 时先升级 JLine / Mockito，或给 surefire 配 `forkedProcessTimeoutInSeconds` 让挂死变成失败而不是永远卡住。

## Plan Steering 新功能审查备注（非缺陷，接线时注意）

1. **仍未接线**：`configurePlanSteeringSafePoint` 在全仓库只有定义，没有任何生产调用方（`Main`、`SteeringRunLoop` 都没接）。与 `PLAN-20261124-harness-upgrade.md` 里「有代码但没接进 Main」的描述一致，不算遗漏，但测试报告里不应写成「已可用」。
2. **取消判定依赖 RUNNING 单态**：`planCancelled()` 里 `!planSteeringSafePoint.isRunning()`，而 `isRunning()` 只认 `TurnState.RUNNING`。当前 `waitForApproval()` 没有任何生产调用方，所以还安全；一旦接线 HITL 审批（Turn 会进入 `WAITING_APPROVAL`），计划会被误判为取消。接线时要么让 `isRunning()` 接受 `WAITING_APPROVAL`，要么在审批前后显式处理安全点。
3. **`reserve()` 会抛 `IllegalStateException`**：队列里出现非 STEER 输入时抛出，`processPlanSteering` 没有捕获，最终由 `runInternal` 的 `catch (Exception)` 变成「❌ 执行失败」。目前只有「Queue 初始输入未先消费」这一条路径能触发（测试已覆盖），但建议在安全点里显式处理并给出更准确的提示，避免整轮以通用错误结束。
4. **修订会重排整个执行顺序**：`revisePendingTasks` 用修订后的 DAG 重新生成 `executionOrder`（包含已完成节点）。依赖关系没变时可执行集不变，但顺序可能与原计划不同，`summarize` 展示的顺序会跟着变。如果这不是预期，建议只重排 PENDING 部分。

## 复现命令

```bash
# 推荐：JDK 21 跑完整回归
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn test -Pquick

# JDK 25 下复现 MCP trust 失败
mvn test -DskipTests=false -Dtest='McpTrustStoreTest,McpServerManagerTrustTest,McpServerManagerTest,McpConfigLoaderTest'

# 复现路径归一化失败
mvn test -DskipTests=false -Dtest='ResourceFailureObserverTest'

# 复现终端挂死（全量时才会出现，单独跑不挂）
mvn test -Pquick   # 观察日志停在 SteeringRunLoopTest / SessionModeControllerTest / TerminalInputGatewayWakeupTest
```
