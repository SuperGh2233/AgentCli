# 无界面模式（`-p`）规格

状态：第 3 阶段第一步实现（2026-11-25）· 代码：`src/main/java/com/paicli/headless/`、`src/main/java/com/paicli/agent/event/`

无界面模式执行一次任务后退出，给脚本、CI 和别的程序调用。它不弹任何确认，所以权限按白名单收紧；标准输出只放下面约定格式的内容，日志只写文件，诊断信息写标准错误。

## 命令

```bash
java -jar paicli.jar -p "任务" [--output-format text|json|stream-json] [--permission-mode read-only|edit] [--allow-command "精确命令"]...
                     [--max-turns 50] [--max-tokens 2000000] [--timeout 30m]
```

- `-p` / `--print` 后面不写任务或写 `-` 时，从标准输入读到结束
- 不能和 `--continue`、`--session` 等交互参数混用；不认识的参数直接报错（退出码 2）
- 模型选择沿用交互模式的配置（环境变量 → 项目 `.env` → 用户 `.env` → 上次保存的模型 → 默认值）
- 会话照常写进 `~/.paicli/history/raw/`，结果里的 `session_id` 就是那条记录的 ID；记录目录不可用时为 `null`

## 上限（2026-11-26 起）

无人值守没有“无限执行”。`-p`、Runtime API、后台任务每个任务默认最多 50 次模型请求、输入加输出 200 万 Token、30 分钟：

| 上限 | 默认 | `-p` 参数 | 环境变量（`-p`、Runtime API、后台任务都读） |
|---|---|---|---|
| 模型请求次数 | 50 | `--max-turns` | `PAICLI_UNATTENDED_MAX_TURNS` |
| 总 Token | 2000000 | `--max-tokens` | `PAICLI_UNATTENDED_MAX_TOKENS` |
| 时间 | 30m | `--timeout`（纯数字按秒，可带 s / m / h） | `PAICLI_UNATTENDED_TIMEOUT` |

- 优先级：参数 > 环境变量 > 默认值；取值不合法直接报错（`-p` 退出码 2），不悄悄用默认值
- 到达任一上限：停止调用工具，再发一次不带工具的请求让模型整理已完成的部分，状态 `partial`、退出码 3。所以实际请求数最多是上限加 1
- 时间在每次模型请求之前检查，正在进行的那次请求或工具不会被打断
- 系统属性 `paicli.react.token.budget` / `paicli.react.hard.max.iterations` 仍然有效，和这里取更严的那个
- 交互命令行有人看着，仍然不限

## 权限

| 模式 | 暴露给模型、允许执行的工具 |
|---|---|
| `read-only`（默认） | `read_file`、`list_dir`、`glob_files`、`grep_code`、`search_code`、`web_search`、`web_fetch`、`load_skill` |
| `edit` | 上面这些，加 `write_file`、`edit_file`、`create_project` |

- `execute_command`：只有给了 `--allow-command` 才暴露给模型，执行时命令文本去掉首尾空白后必须和某一条完全相同（`git status && rm -rf x` 不等于 `git status`）
- 其余工具一律不暴露也不执行：`save_memory`、`save_skill_reference`、`revert_turn`、浏览器工具、所有 MCP 工具（无界面模式不启动 MCP server）
- 白名单之外的检查照常生效：PathGuard（路径必须在项目根目录内）、CommandGuard、命令沙箱（`PAICLI_COMMAND_SANDBOX`）、本轮网址来源规则
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
 "tools":{"calls":4,"succeeded":3,"failed":1,"skipped":0,"success_rate":0.75},"notices":1}
```

- `status`：`completed` / `partial` / `cancelled` / `failed`；`is_error` 在 `failed`、`cancelled` 时为 true
- `result`：完成时是模型的原始回答（不含思考过程和终端提示）；部分完成时是整理结果；失败和取消时是说明
- `tools`：本轮工具统计（2026-11-26 新增）。`calls` 是真正交给执行的调用数（含被策略拒绝的），`skipped` 是没执行的（参数被截断），`success_rate` = succeeded /（succeeded + failed），一次都没执行时为 `null`；`notices` 是提示条数。口径和场景回归 `HarnessScenarioSuiteTest` 相同
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
| `tool_call_finished` | `id`、`name`、`success`、`output` | 工具执行结束；`output` 是还给模型之前的原始结果 |
| `tool_call_skipped` | `id`、`name`、`reason` | 没有执行；目前只有 `output_truncated`（模型输出被长度截断，参数不完整） |
| `notice` | `text` | 提示，比如上下文压缩、重复调用提醒、预算触发 |
| `turn_finished` | `turn_id`、`status`、`answer`、`model_requests`、`duration_ms`、`input_tokens`、`output_tokens`、`cached_input_tokens` | 一轮结束，Token 是本轮累计 |

顺序：`turn_started` → 每次模型请求（一条 `model_request_started`，若干 `reasoning_delta` / `text_delta`，然后一条 `usage`）→ 模型要调工具时每个调用一条 `tool_call_started`，整批执行完后每个调用一条 `tool_call_finished`（截断时改为 `tool_call_skipped`）→ 过程中可能穿插 `notice` → `turn_finished` → `result`。

事件文字不含终端颜色代码。字段名是对外约定，改动要同步这份文档并保留旧字段至少一个版本；新增事件种类不算破坏约定，解析方遇到不认识的 `type` 应该跳过。

交互终端也只靠这串事件显示（`agent/TerminalEventRenderer`，2026-11-26）。进程内事件比 JSON 多几个只给终端用的字段：提示的带样式原文和“已拼进回答”标记、工具调用的“本轮策略是否放行”和“是不是这批最后一个”，它们不写进 JSON。

## 还没做到的

- `tool_call_started` 和 `tool_call_finished` 按整批发：并行执行的只读工具没有各自的开始和结束时间
- 没有审批事件（无界面模式不审批；交互终端的审批框仍由 HITL 直接弹出，不经过事件）
- Runtime API（`serve --http`）从 2026-11-26 起把同一套事件写进事件流，事件名是 `agent.<type>`，`data` 里的 `event` 就是这里的一行 JSON（外面包一层 Runtime API 的 `turn_id`，详见 `docs/phase-20-runtime-api.md`）；后台任务还不输出事件。它们的工具白名单已经和本模式一致（2026-11-26，`UnattendedTaskRunner`）：`serve` 支持 `--permission-mode`、`--allow-command`，后台任务用 `PAICLI_TASK_PERMISSION_MODE`
- 不支持 `--resume` 接着上次的无界面会话继续
