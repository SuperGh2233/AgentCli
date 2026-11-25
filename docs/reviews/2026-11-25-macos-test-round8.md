# 本机 macOS 测试报告（第 8 轮）

日期：2026-11-25 23:30 · 测试机：macOS（Apple Silicon）· 被测：`feat/steering-phase2b` @ `88340e1` + 工作区未提交改动（Phase 3 收尾：`agent/event/` 类型化事件 + 无人值守权限）
环境：JDK 21.0.12.1

## 结论

**全量 1763 个测试零失败，事件类型与文档逐条对应，无人值守的权限是 fail-closed 的，实机验证只读模式确实写不了文件。** 没有发现回归。

| 运行 | 结果 | 耗时 |
|---|---|---|
| `mvn clean test -Pquick`（JDK 21） | **1763 通过 / 0 失败 / 0 错误 / 5 跳过** | 34.1s |
| 实机 `-p ... --output-format stream-json` | 14 行全合法 JSON，无未知类型 | — |
| 实机只读模式写文件 | 文件未创建，双层拦截生效 | — |

对比上一轮：1752 → 1763（+11，其中 `AgentSecurityRegressionTest` 5 个、`UnattendedTaskRunnerTest` 6 个）。

## 事件协议与文档的一致性

`AgentEvent` 是 sealed 接口，9 个子类型和文档表一一对应：

| `AgentEvent` 子类型 | JSON `type` |
|---|---|
| `TurnStarted` | `turn_started` |
| `ReasoningDelta` | `reasoning_delta` |
| `TextDelta` | `text_delta` |
| `ToolCallStarted` | `tool_call_started` |
| `ToolCallFinished` | `tool_call_finished` |
| `ToolCallSkipped` | `tool_call_skipped` |
| `Notice` | `notice` |
| `Usage` | `usage` |
| `TurnFinished` | `turn_finished` |

加上 `result` 共 10 种，和 `docs/specs/headless-mode.md` 的表完全一致。字段名也对得上（`turn_id`、`cached_input_tokens`、`model_requests`、`duration_ms` 等）。

实机跑了一次，14 行事件里类型分布正常，**没有出现 `unknown`**：

```
{'turn_started': 1, 'reasoning_delta': 9, 'text_delta': 1, 'usage': 1, 'turn_finished': 1, 'result': 1}
```

## 无人值守权限：确认是 fail-closed

这部分是这轮最需要盯的，因为无界面模式没人能点“允许”。

**工具白名单**

| 分类 | 工具 |
|---|---|
| 只读（永远放行） | `read_file`、`list_dir`、`glob_files`、`grep_code`、`search_code`、`web_search`、`web_fetch`、`load_skill` |
| 可写（只在 `edit` 模式放行） | `write_file`、`edit_file`、`create_project` |
| 命令（只在逐字白名单命中时放行） | `execute_command` |
| **其余一律拒绝** | `browser_connect`、`browser_disconnect`、`browser_status`、`save_memory`、`save_skill_reference`、`revert_turn` |

内置工具 18 个，8 + 3 + 1 = 12 个有归属，剩下 6 个落在最后那条“不认识就拒绝”，没有漏网的。方向也对——宁可少给能力（比如 `save_memory` 在无人值守下被拒，是保守而不是漏洞）。

**命令白名单是精确字符串比较**

```java
if (!command.isEmpty() && command.equals(allowed)) return null;
```

`ls` 放进白名单，`ls; rm -rf /`、`ls $(...)`、`ls\nrm ...` 都不会等于 `ls`，进不去。这一条我特意核了，前缀匹配是这类白名单最常见的漏洞。

**双层防护**

`getToolDefinitions()` 先过滤掉不允许的工具（模型看不到），`executeToolOutput()` 再按同一份规则查一次（硬猜也调不动）。两个入口都覆盖了。

## 实机验证只读模式

用 `-p "在项目根目录创建文件 headless-probe-xyz.txt..."` 跑了一次：

- `headless-probe-xyz.txt` **没有被创建**。
- 模型自己尝试调 `create_project`，被拒：`[TOOL_NOT_ADVERTISED] 该工具未向本轮模型开放`。
- 模型随后 glob 确认目录里没有这个文件，并说明它这一轮只拿到只读工具。

也就是说第一层（不暴露）和第二层（执行时拒绝）都真的生效了，不是只在单元测试里成立。

## 一个建议

`AgentEventJson` 的序列化是 if-else 链，最后有个兜底：

```java
} else {
    node.put("type", "unknown");
}
```

`AgentEvent` 是 sealed 的，但这里用 if-else 而不是 switch 模式匹配，所以**将来加第 10 种事件时编译器不会报错，运行时会静默输出 `{"type":"unknown"}`**，下游拿到一个没人认识的事件，而且很难发现。

项目编译目标是 Java 17（switch 模式匹配还是 preview，用不了），所以建议把这个兜底改成抛异常：

```java
} else {
    throw new IllegalStateException("未处理的事件类型: " + e.getClass().getName());
}
```

这样加新事件时既有测试会立刻红，而不是悄悄退化成 `unknown`。

## 一个需要你确认的安全点

只读白名单里包含 `web_fetch`，也就是无人值守模式下模型可以抓取任意 URL。`AgentSecurityRegressionTest.urlFoundInsideToolResultCannotBeFetched` 覆盖了“工具结果里出现的网址不能被抓”这个场景，说明有来源限制。但我没在 unattended 这条路径上实测过 URL 白名单是否同样生效（这需要构造一个外部地址，会真的发请求，我没擅自做）。建议你确认一下：`-p` 模式下 `web_fetch` 走的还是不是 `TurnToolPolicy` 那套来源检查。

## 未覆盖

- 退出码 `3`（超预算）和 `130`（取消）仍未触发。
- `tool_call_skipped` / `notice` 事件仍未在实机出现过（这次调用没触发截断和压缩）。
- `--permission-mode edit` 和 `--allow-command` 只做了代码审查，没有实机跑。

## 复现

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean test -Pquick          # 1763 tests, 0 failures, 34s
mvn package -DskipTests
java -jar target/paicli-1.0-SNAPSHOT.jar -p "只回答两个字母：OK" --output-format stream-json
java -jar target/paicli-1.0-SNAPSHOT.jar -p "在项目根目录创建文件 headless-probe-xyz.txt，内容写 hello" --output-format json
```
