# macOS 测试反馈 · 第 13 轮（2026-11-26 22:15）

对象：分支 `feat/harness-runtime` = `0396dcf`（领先 `main`/`dfe77a9` 三个提交）。
该提交的说明里写着「尚未编译，待 macOS JDK 21 验证」，所以这一轮重点核的是**它到底能不能编译、行为对不对**。
环境：macOS，JDK 21.0.12，`mvn clean test -Pquick`。

## 一、结论：能编译，测试全绿，失败语义已按 ADR-0002 落地

| 项 | 结果 |
|---|---|
| 编译 | **通过**（`mvn clean package` 成功） |
| `mvn clean test -Pquick` | **1797 通过 / 0 失败 / 0 错误 / 5 跳过** |
| 耗时 | 33.3 秒 |
| 失败清单（`tools/test-failures.sh`） | 空 |
| 与第 12 轮 | 1787 → 1797（+10：`RunLimitsTest` 4 个、`AgentBudgetTest` 2 个、`HeadlessOptionsTest` 与 `HeadlessRunnerTest` 各若干） |

三个提交的分布：

```
0396dcf feat(P0): Runtime API reports failed turns as turn.failed; default limits for unattended runs
4fabe82 docs: add runtime roadmap (PLAN-20261126) and ADR-0001..0003
9ff2461 feat: emit-only AgentLoop, terminal and Runtime API consume AgentEvent; fix read_file failure classification
```

## 二、我上轮提的语义问题已经修好（实机确认）

用不存在的模型名起真实服务（零 token），失败轮次现在只发 `turn.failed`：

```
事件数 = 6
  thread.created
  turn.started
  agent.turn_started
  agent.model_request_started
  agent.turn_finished
  turn.failed

turn.failed 顶层字段 = ["agent_status", "error", "turn_id"]
agent_status = failed
有没有 message.delta = False
```

对比第 12 轮同一场景（当时是 `message.delta` 带错误文本 + `turn.completed: completed`），现在客户端**不会再误判**。`agent.turn_finished.status` 和 `turn.failed.agent_status` 一致。

实现上的两个细节值得肯定：

- `runner` 没发事件时（测试里的 lambda）按 `COMPLETED` 处理，不会因为拿不到 `TurnFinished` 就报错；
- 失败时优先用 `finished.answer()` 作为错误文本，为空才回落到返回值，而不是把整段返回值当回答。

## 三、RunLimits：参数校验和优先级都正确

零成本探针（不进模型调用，只看参数分支）：

| 输入 | 退出码 | 提示 |
|---|---|---|
| `--max-turns abc` / `0` / `-1` | 2 | `❌ --max-turns 必须是正整数: abc` |
| `--timeout 0` / `abc` | 2 | `❌ --timeout 必须是正的时长（比如 90、90s、30m、2h）: 0` |
| `PAICLI_UNATTENDED_MAX_TURNS=0` / `abc` | 2 | `❌ PAICLI_UNATTENDED_MAX_TURNS 必须是正整数: 0` |
| 合法值（`PAICLI_UNATTENDED_MAX_TURNS=5`） | 1 | 正常进到模型调用（假模型 → 404，说明上限没挡住执行） |

三点确认：

1. **非法值不静默回落默认值**，报错里带变量名/参数名，exit 2 和参数错误一致。
2. **优先级是命令行 > 环境变量 > 默认值**，且同一个参数写两次会被 `once()` 拒掉。
3. **交互模式确实不限**：`Agent.runLimits` 字段默认 `RunLimits.UNLIMITED`，`DEFAULT_HARD_MAX_ITERATIONS = Integer.MAX_VALUE`，`Math.min(MAX_VALUE, MAX_VALUE)` 仍是无限。只有 `setRunLimits` 才收紧，调用方只有无人值守入口。

默认上限没有扰动既有场景：harness 11 个场景的 `exit_code` / `model_requests` 与上轮完全一致，`budget_partial_result` 仍是 `exit=3 partial req=2`。

## 四、三个提醒

**1. 成功路径的 `agent_status` 只被单测覆盖，这轮没做实机**

失败路径我实测了，成功路径需要真实模型才能走到。`RuntimeApiServerTest` 里有断言（`"agent_status":"completed"` 存在、runner 不发事件时按 completed），但那是 `ScriptedLlmClient`。如果你要一次真实对照，我可以跑一个小任务（几十 token），确认 `turn.completed` 的 `agent_status` 在真流量下也是 `completed`。

**2. Plan / Team 两条路径完全不受 RunLimits 约束**

`RunLimits` 只接到 `AgentLoop.Turn` → `Agent.setRunLimits` 这一条链。`PlanExecuteAgent:800` 仍是 `AgentBudget.fromSystemProperties()`（只读系统属性，无限），`SubAgent` 也不接。

目前这不成问题——`-p`、Runtime API、后台任务都只走 ReAct。但**一旦允许 `-p` 跑 Plan 模式，或者后台任务能起 Team**，那条路径就没有 30 分钟/50 次请求的兜底了，而且不会有任何提示。建议在 `RunLimits` 的注释里写清「只覆盖 ReAct」，或者给 `PlanExecuteAgent` 也加一个可选入参。

**3. 时间上限只在模型请求之前检查**

`RunLimits` 的注释写了「正在进行的单次请求或工具不会被打断」，实测行为一致。这意味着**单个卡住的工具调用可以把 30 分钟拖成任意长**（比如一条 `execute_command` 挂住）。如果这是有意取舍就没问题，但 `PAICLI_UNATTENDED_TIMEOUT` 这个名字会让人以为它是硬墙钟。建议在文档里补一句「不含正在执行的那一次请求/工具」，或者给命令执行单独加超时（`CommandGuard` 那边应该有现成的）。

## 五、测试覆盖的两个空档

- **没有「交互模式不限」的用例**。这条靠字段默认值成立，一旦有人在 `Agent` 构造函数里误设默认值，现有测试不会变红。建议加一条：`new Agent(...)` 后断言 `runLimits` 是 `UNLIMITED`。
- **`--max-turns 1` 到真实 partial 的端到端**没有用例。目前 partial 只在 harness 场景里用脚本模型覆盖（`budget_partial_result`，exit 3），`-p` 参数 → 内核预算 → 退出码这条链路没有连起来测过。

## 六、环境

- 验证用完的 `serve --http` 进程已停（`pkill`，退出码 143 是 SIGTERM，非异常）。
- 本机 `nohup ... &` 起的服务会被回收，起服务要用受管理的后台任务。
- 分支 `feat/harness-runtime` 此前没有远端对应分支，本轮报告提交后一并推上去。
