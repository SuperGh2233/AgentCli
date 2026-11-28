# 无界面模式（`-p`）规格

状态：第 3 阶段第一步实现（2026-11-25）· 代码：`src/main/java/com/haocli/headless/`、`src/main/java/com/haocli/agent/event/`

无界面模式执行一次任务后退出，给脚本、CI 和别的程序调用。它不弹任何确认，所以权限按白名单收紧；标准输出只放下面约定格式的内容，日志只写文件，诊断信息写标准错误。

## 命令

```bash
java -jar haocli.jar -p "任务" [--output-format text|json|stream-json] [--permission-mode read-only|edit] [--allow-command "精确命令"]...
                     [--max-turns 50] [--max-tokens 2000000] [--timeout 30m]
```

- `-p` / `--print` 后面不写任务或写 `-` 时，从标准输入读到结束
- 不能和 `--continue`、`--session` 等交互参数混用；不认识的参数直接报错（退出码 2）
- 模型选择沿用交互模式的配置（环境变量 → 项目 `.env` → 用户 `.env` → 上次保存的模型 → 默认值）
- 会话照常写进 `~/.haocli/history/raw/`，结果里的 `session_id` 就是那条记录的 ID；记录目录不可用时为 `null`

## 指定服务商（2026-11-28 起）

环境变量 `HAOCLI_PROVIDER=<服务商>`（比如 `magpie`）：只用这个服务商，模型和地址照常取 `<服务商>_MODEL`、`<服务商>_BASE_URL`。找不到它的 Key 时以退出码 2 报错，**不会**改用别的服务商（避免悄悄扣别的账户）。不设时照旧按 `~/.haocli/config.json` 的默认服务商和自动回退顺序选；magpie 不在自动回退里。评测适配器用它把容器固定到 magpie。

## 上限（2026-11-26 起）

无人值守没有“无限执行”。`-p`、Runtime API、后台任务每个任务默认最多 50 次模型请求、输入加输出 200 万 Token、30 分钟：

| 上限 | 默认 | `-p` 参数 | 环境变量（`-p`、Runtime API、后台任务都读） |
|---|---|---|---|
| 模型请求次数 | 50 | `--max-turns` | `HAOCLI_UNATTENDED_MAX_TURNS` |
| 总 Token | 2000000 | `--max-tokens` | `HAOCLI_UNATTENDED_MAX_TOKENS` |
| 时间 | 30m | `--timeout`（纯数字按秒，可带 s / m / h） | `HAOCLI_UNATTENDED_TIMEOUT` |

- 优先级：参数 > 环境变量 > 默认值；取值不合法直接报错（`-p` 退出码 2），不悄悄用默认值
- 到达任一上限：停止调用工具，再发一次不带工具的请求让模型整理已完成的部分，状态 `partial`、退出码 3。所以实际请求数最多是上限加 1
- 时间不是硬墙钟：在每次模型请求之前检查，正在进行的那次请求或那批工具不会被打断。工具自己有超时（单条命令默认 60 秒、一批工具 90 秒），所以最坏情况是上限再加一次模型请求和一批工具的时间
- 只约束 ReAct；`-p`、Runtime API、后台任务目前都只走 ReAct。Plan / Team 迁进内核后（路线丙阶段）自动覆盖
- 系统属性 `haocli.react.token.budget` / `haocli.react.hard.max.iterations` 仍然有效，和这里取更严的那个
- 交互命令行有人看着，仍然不限

## 权限

| 模式 | 暴露给模型、允许执行的工具 |
|---|---|
| `read-only`（默认） | `read_file`、`list_dir`、`glob_files`、`grep_code`、`search_code`、`web_search`、`web_fetch`、`load_skill` |
| `edit` | 上面这些，加 `write_file`、`edit_file`、`create_project` |

- `execute_command`：只有给了 `--allow-command` 才暴露给模型，执行时命令文本去掉首尾空白后必须和某一条完全相同（`git status && rm -rf x` 不等于 `git status`）
- 其余工具一律不暴露也不执行：`save_memory`、`save_skill_reference`、`revert_turn`、浏览器工具、所有 MCP 工具（无界面模式不启动 MCP server）
- 白名单之外的检查照常生效：PathGuard（路径必须在项目根目录内）、CommandGuard、命令沙箱（`HAOCLI_COMMAND_SANDBOX`）、本轮网址来源规则
- 被拒绝的调用写一条 deny 审计记录，失败结果还给模型

## 退出码

| 码 | 含义 |
|---|---|
| 0 | 完成 |
| 1 | 调用模型失败，或执行中出现意外错误 |
| 2 | 参数错误、任务为空、没有可用的 API Key |
| 3 | 到达上限（见“上限”）或判定停滞，只整理出部分结果 |
| 130 | 已取消 |

## 输出格式

### `text`（默认）

完成或部分完成时，标准输出只有最终回答（部分完成时是带“⚠️ 部分完成”标题的整理结果）。失败和取消时标准输出为空，说明写到标准错误。

### `json`

结束时输出一行 JSON：

```json
{"type":"result","status":"completed","is_error":false,"result":"最终回答","turn_id":"…","session_id":"…",
 "provider":"deepseek","model":"deepseek-flash","model_requests":3,"duration_ms":5120,
 "usage":{"input_tokens":12000,"output_tokens":800,"cached_input_tokens":9000},
 "tools":{"calls":4,"succeeded":3,"failed":1,"skipped":0,"success_rate":0.75},"notices":1,"reason":null,
 "context":{"offloads":1,"trim_runs":0,"trimmed":0,"fold_runs":0,"folded":0,"clearing_runs":0,"cleared":0,
            "summaries":0,"emergency_runs":0,"tokens_saved":0},
 "harness_disabled":[],"context_budget_tokens":0}
```

- `status`：`completed` / `partial` / `cancelled` / `failed`；`is_error` 在 `failed`、`cancelled` 时为 true
- `result`：完成时是模型的原始回答（不含思考过程和终端提示）；部分完成时是整理结果；失败和取消时是说明
- `tools`：本轮工具统计（2026-11-26 新增）。`calls` 是真正交给执行的调用数（含被策略拒绝的），`skipped` 是没执行的（参数被截断），`success_rate` = succeeded /（succeeded + failed），一次都没执行时为 `null`；`notices` 是提示条数。口径和场景回归 `HarnessScenarioSuiteTest` 相同
- `reason`（2026-11-28 新增）：机器可读的结束原因，`completed`、`cancelled` 时为 `null`。失败时是 `llm_<分类>`：`llm_transient`（网络抖动、超时、429、5xx、TLS 握手被对端断开）、`llm_billing`（402 余额或额度不足）、`llm_authentication`（401/403）、`llm_invalid_request`、`llm_invalid_response`、`llm_cancelled_or_timeout`、`llm_unknown`；部分完成时是 `token_budget_exceeded` / `hard_iteration_limit` / `time_limit_exceeded` / `stagnation_detected` / `context_overflow`。评测据此区分基础设施故障、预算耗尽和 Agent 自身问题
- `context`（2026-11-28 新增）：上下文处理策略各层本轮的触发情况。`offloads` 是第 1 层大结果落盘次数（按落盘摘要第一行计）；`*_runs` 是第 2–4 层（裁剪、折叠、清理）各自触发的次数，`trimmed` / `folded` / `cleared` 是处理的条数；`summaries` 是第 5 层摘要次数；`emergency_runs` 是模型报上下文超长后紧急压缩的次数；`tokens_saved` 是各次压缩前后估算 Token 之差的累计（按 `TokenBudget` 估算，不是供应商计数）。摘要调用本身消耗的 Token 暂不统计
- 2026-11-28 起 `-p` 的标准输出和标准错误固定用 UTF-8，不跟随系统区域设置（容器里 POSIX 区域会把中文变成 `?`）
- 2026-11-26 起 `read_file` 读不到文件（“读取文件失败: …”）算失败，以前被误算成成功
- 执行中途出现意外错误时只有 `type`、`status`、`is_error`、`result`、`session_id`、`provider`、`model` 这几个字段

### `stream-json`

每个事件一行 JSON（JSONL），最后一行是和 `json` 模式相同的 `result` 对象。事件种类：

| type | 字段 | 说明 |
|---|---|---|
| `turn_started` | `turn_id` | 一轮开始 |
| `model_request_started` | （无） | 即将发起一次模型请求，含超预算后的收尾请求（2026-11-26 新增） |
| `reasoning_delta` | `text` | 思考内容增量 |
| `text_delta` | `text` | 回答正文增量 |
| `usage` | `input_tokens`、`output_tokens`、`cached_input_tokens`、`reported` | 每次模型请求一条；`reported=false` 表示供应商没给完整用量，数字不可信 |
| `tool_call_started` | `id`、`name`、`arguments` | 模型请求调用工具（参数原文） |
| `tool_call_finished` | `id`、`name`、`success`、`status`、`output` | 工具执行结束；`output` 是还给模型之前的原始结果。`status`（2026-11-27 起）是细分状态：`success` / `failed` / `denied`（被策略、权限、确认或白名单拒绝）/ `timeout`（只读工具超时）/ `cancelled` / `skipped`（没执行）/ `unknown`（执行了但结果不确定，比如命令超时被强制终止，先核实再重试）；`success` 等于 `status == "success"`，见 `docs/adr/ADR-0002-result-semantics.md`。可选 `reason`（2026-11-28 起，只在有原因时出现）：普通失败如 `not_found`、`invalid_argument`、`edit_source_not_found`；拒绝时说明是哪一层确定性检查——`deny_path`（PathGuard）、`deny_command`（命令黑名单 / 沙箱不可用）、`deny_limit`（资源上限）、`deny_turn_policy`（本轮工具策略）、`deny_unattended`（无人值守白名单）、`deny_permission`（权限规则 / MCP 状态变化）、`deny_auto_review`、`deny_human`、`deny_network`、`deny_policy`（其他） |
| `tool_call_skipped` | `id`、`name`、`reason` | 没有执行；目前只有 `output_truncated`（模型输出被长度截断，参数不完整） |
| `notice` | `text` | 提示，比如上下文压缩、重复调用提醒、预算触发 |
| `context_compacted` | `trigger`、`trimmed`、`folded`、`cleared_tool_results`、`summarized`、`before_tokens`、`after_tokens` | 上下文处理第 2–5 层改动了对话（2026-11-28 新增）；`trigger` 是 `automatic`（请求前按阈值）或 `emergency`（模型报上下文超长）；前后 Token 是估算值 |
| `turn_finished` | `turn_id`、`status`、`answer`、`model_requests`、`duration_ms`、`input_tokens`、`output_tokens`、`cached_input_tokens`，可选 `reason` | 一轮结束，Token 是本轮累计；`reason` 只在失败和部分完成时出现，取值同 `result.reason` |

顺序：`turn_started` → 每次模型请求（一条 `model_request_started`，若干 `reasoning_delta` / `text_delta`，然后一条 `usage`）→ 模型要调工具时每个调用一条 `tool_call_started`，整批执行完后每个调用一条 `tool_call_finished`（截断时改为 `tool_call_skipped`）→ 过程中可能穿插 `notice` → `turn_finished` → `result`。

事件文字不含终端颜色代码。字段名是对外约定，改动要同步这份文档并保留旧字段至少一个版本；新增事件种类不算破坏约定，解析方遇到不认识的 `type` 应该跳过。

交互终端也只靠这串事件显示（`agent/TerminalEventRenderer`，2026-11-26）。进程内事件比 JSON 多几个只给终端用的字段：提示的带样式原文和“已拼进回答”标记、工具调用的“本轮策略是否放行”和“是不是这批最后一个”，它们不写进 JSON。

## 还没做到的

- `tool_call_started` 和 `tool_call_finished` 按整批发：并行执行的只读工具没有各自的开始和结束时间
- 没有审批事件（无界面模式不审批；交互终端的审批框仍由 HITL 直接弹出，不经过事件）
- Runtime API（`serve --http`）从 2026-11-26 起把同一套事件写进事件流，事件名是 `agent.<type>`，`data` 里的 `event` 就是这里的一行 JSON（外面包一层 Runtime API 的 `turn_id`，详见 `docs/phase-20-runtime-api.md`）；后台任务还不输出事件。它们的工具白名单已经和本模式一致（2026-11-26，`UnattendedTaskRunner`）：`serve` 支持 `--permission-mode`、`--allow-command`，后台任务用 `HAOCLI_TASK_PERMISSION_MODE`
- 不支持 `--resume` 接着上次的无界面会话继续
