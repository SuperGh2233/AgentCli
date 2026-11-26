# ADR-0002 结果语义：工具状态自己报告，执行状态和目标状态分开

状态：已接受（2026-11-26，用户确认） · 实施：`docs/plans/active/PLAN-20261126-runtime-roadmap.md` 的 P0、甲阶段、目标循环阶段

## 背景

- 多数内置工具返回纯文字，`ToolRegistry.looksLikeFailureText` 按文字前缀猜成败。第 11 轮发现“读取文件失败”没在前缀名单里，失败的 `read_file` 被算成成功
- 第 12 轮发现：模型调用失败时，Runtime API 内层 `agent.turn_finished.status=failed`，外层却发 `message.delta`（内容是错误文本）和 `turn.completed`。只看外层事件的客户端会把失败当成功（`docs/reviews/2026-11-26-macos-test-round12.md`）
- “一轮执行结束”和“任务目标达成”是两回事，现在没有地方表达后者

## 决定

### 1. 工具状态由工具自己声明

每个工具返回结构化结果，状态取值：`SUCCESS`、`FAILED`、`DENIED`（被策略或用户拒绝）、`TIMEOUT`、`CANCELLED`、`SKIPPED`（没执行，比如回复被截断）、`UNKNOWN`（执行了但结果不确定，比如命令超时后不知道副作用有没有发生），另带失败码和是否可重试。MCP 工具按协议的 `isError` 映射。

`looksLikeFailureText` 只给还没迁移的工具兜底，18 个内置工具全部迁完后删除。之后不允许再按文字判断成败。

### 2. 执行状态（一轮跑得怎样）

| 状态 | 含义 | `-p` 退出码 |
|---|---|---|
| `completed` | 正常结束 | 0 |
| `partial` | 超预算或停滞后，关掉工具整理出的部分结果 | 3 |
| `failed` | 模型调用失败等，没有可用结果 | 1 |
| `cancelled` | 用户或调用方取消 | 130 |

Runtime API 外层事件对应：

| 执行状态 | 外层事件 |
|---|---|
| `completed` | `turn.completed`，`data` 带 `"agent_status":"completed"` |
| `partial` | `turn.completed`，带 `"agent_status":"partial"` |
| `failed` / `cancelled` | `turn.failed`，`error` 填原因，带 `agent_status`；不发 `message.delta` |

### 3. 目标状态（任务做没做成）

只有给了验收命令（`--verify`）时才有意义，随目标循环阶段加入：`unverified`（没给验收命令）、`passed`、`failed`、`blocked`（预算用完或验收命令不允许执行）。验收靠确定性命令的退出码，不用模型当裁判。

执行状态和目标状态是两个独立字段：`completed` + `failed` 表示“跑完了，但测试没过”。

## 放弃的方案

| 方案 | 为什么不选 |
|---|---|
| 继续扩充文字前缀名单 | 每个新工具、每句新错误文案都可能漏，漏了就是静默的错误统计；评测和重复错误提醒都依赖这个状态 |
| Runtime API 只给 `turn.completed` 追加 `agent_status` 字段（原路线文档的方案） | 老客户端不认识新字段，照样把失败当成功；`turn.failed` 是现有协议里本来就有的事件，老客户端已经会处理 |
| 现在就加 `goal_status` | 没有验证器之前它永远是 `unverified`，是没有信息量的字段 |
| 用模型当验证裁判 | 结果不可复现、花 Token，是评测里最容易被质疑的部分 |

## 后果

- 好处：CLI、`-p` JSON、Runtime API 对失败、部分完成、取消的表达一致；评测和运行时统计不再受文案影响
- 代价：Runtime API 失败时的行为变了：以前是 `message.delta` + `turn.completed`，现在是 `turn.failed`。只依赖 `turn.completed` 判断“结束了”的客户端需要同时监听 `turn.failed`（这本来就是协议的一部分）。在 `docs/phase-20-runtime-api.md` 里写明
