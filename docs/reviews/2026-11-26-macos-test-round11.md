# 本机 macOS 测试报告（第 11 轮）

日期：2026-11-26 15:25 · 测试机：macOS（Apple Silicon）· 被测：`main` @ `3604e0f` + 工作区未提交改动（事件协议扩展 + 显示层重构 + read_file 分类修复）
环境：JDK 21.0.12.1

## 结论

**全量 1785 个测试零失败，失败清单为空。** 这轮落地了我前两轮报的两个问题（read_file 失败分类、事件协议），显示层也换成了「内核只发事件、终端消费事件」的结构。实机跑过一遍，思考、工具调用、回答、代码块都正常。

| 运行 | 结果 | 耗时 |
|---|---|---|
| `mvn clean test -Pquick`（JDK 21） | **1785 通过 / 0 失败 / 0 错误 / 5 跳过** | 29.6s |
| `bash tools/test-failures.sh` | 无输出 ✓ | — |
| 实机 `-p ... --output-format stream-json` | 17 行全部合法 JSON，含新事件 | — |
| 实机交互模式（真实伪终端） | 思考 / 工具调用 / 回答 / 代码块均正常 | — |

对比上轮：1777 → 1785（+8）。

## 一、read_file 失败分类：已修，且连带效果确认

`ToolRegistry.looksLikeFailureText` 的白名单加了 `读取文件失败` 一条：

```java
|| value.startsWith("工具执行超时")
+|| value.startsWith("读取文件失败")
|| value.startsWith("未知工具")
```

harness 场景也跟着翻转了，而且改了名字（比原来更清楚）：

| 改前 | 改后 |
|---|---|
| `known_issue_read_failure_counted_as_success`（期望 ok=1 / fail=0） | `read_missing_file_counted_as_failure`（期望 ok=0 / fail=1） |

实测 `target/harness-metrics/scenarios.jsonl` 已经是 `read_missing_file_counted_as_failure -> ok=0 fail=1`，说明基线数字也一起更正了。

连带效果：我上轮指出的「`RunawayGuard` 只数 `!successful()`，所以 read 失败不进错误维度」这条现在不再漏了——它和成功率统计走的是同一个 `successful()` 字段。这处我按代码推导确认，没有单独跑重复读失败的场景（现有 `repeated_failure_reminder` 用的是 `edit_file`）。

## 二、事件协议扩展

事件从 9 种变 10 种，新增 `ModelRequestStarted`：

| 实测事件流（17 行） | 条数 |
|---|---|
| `turn_started` | 1 |
| `model_request_started` | 1 |
| `reasoning_delta` | 11 |
| `text_delta` | 1 |
| `usage` | 1 |
| `turn_finished` | 1 |
| `result` | 1 |

`AgentEventJson` 里还区分了「对外 JSON」和「进程内终端用的字段」：`Notice` 新增 `styledText` / `attachedToAnswer`，`ToolCallStarted` 新增 `allowedByTurnPolicy` / `lastInBatch`，这些**不写进 JSON**。这个边界划得对——对外格式保持稳定，终端需要的样式信息走进程内。

`docs/specs/headless-mode.md` 已同步，包括新的顺序说明（每次模型请求先是 `model_request_started`，然后增量，再 `usage`）。

## 三、显示层重构：分层，不是替换

结构变成三层，我一开始以为旧渲染器成了死代码，核实后是分层：

```
AgentLoop  ──只发 AgentEvent──▶  TerminalEventRenderer  ──▶  ReActStreamRenderer  ──▶  终端
```

`TerminalEventRenderer`（149 行，新）持有 `ReActStreamRenderer`（303 行，仍在用）做实际流式渲染，自己负责把事件翻译成渲染调用。`AgentLoop.Host` 因此从「printLine / showToolCalls / pushStatus 三个回调」瘦成一个 `answerStreamed()`，符合「内核不碰终端」的目标。

`TerminalEventRendererTest` 6 个用例覆盖了批次显示、策略拒绝的调用不显示、notice 样式去重。

## 四、实机验证（真实伪终端）

用与第 5、7 轮相同的脚本跑「读取 a.txt 并说明内容」：

| 检查项 | 实际输出 |
|---|---|
| 思考 | `⠸ Thinking... (esc to cancel, 0s)` + 逐字流式，收成 `│ The file contains: …` |
| 工具调用 | `⏵ 2 组工具调用 / 2 次 (ctrl+o to expand)` |
| 回答 | `▪ a.txt 里只有两行内容：` + `⏵ code (2 行, ctrl+o to expand)` |
| 状态栏 | `idle … in 12.1k out` 正常 |

这里我差点误判：第一眼没看到第 5 轮那种 `⏵ ReadFile(a.txt)`，以为工具调用显示丢了。把日志按 ANSI 清洗后逐行搜才发现是 `⏵ 2 组工具调用 / 2 次` —— **显示方式从「单个工具名」改成了「按批聚合」**，属于改进（避免多工具刷屏），不是回归。

## 五、一条上轮建议没被采纳

`AgentEventJson` 末尾仍是：

```java
} else {
    node.put("type", "unknown");
}
```

`AgentEvent` 是 sealed，但这处 if-else 用兜底吞掉了「新增事件但忘了映射」的情况。现在事件已经加到第 10 个，这种遗漏的概率只会更高。建议改成抛 `IllegalStateException("未处理的事件类型: " + e.getClass())`，让加新事件时测试立刻红。改动只有一行。

## 复现

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean test -Pquick && bash tools/test-failures.sh
mvn package -DskipTests
java -jar target/paicli-1.0-SNAPSHOT.jar -p "只回答两个字母：OK" --output-format stream-json
```
