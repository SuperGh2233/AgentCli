# 本机 macOS 测试报告（第 10 轮）

日期：2026-11-26 10:55 · 测试机：macOS（Apple Silicon）· 被测：`feat/steering-phase2b` @ `b870a46` + 工作区未提交改动（harness 场景套件 + README/`.env.example` 文档）
环境：JDK 21.0.12.1

## 结论

**全量 1774 个测试零失败，失败清单为空。** 这轮新增了 harness 场景套件（11 个场景），设计得不错；它定格的那个「已知问题」我确认是真的，而且影响比注释里写的还大一点。另外我上轮建议「别把 harness 放进 `-Pquick`」，实测这个建议是错的，下面说明。

| 运行 | 结果 | 耗时 |
|---|---|---|
| `mvn clean test -Pquick`（JDK 21） | **1774 通过 / 0 失败 / 0 错误 / 5 跳过** | 37.4s |
| `bash tools/test-failures.sh` | 无输出 ✓ | — |

对比第 9 轮：1763 → 1774（+11，正好是 harness 的 11 个场景）。

这轮的改动范围：源码没动，新增 `HarnessScenarioSuiteTest` + `HeadlessRunnerTest` 调整，以及 `README.md`、`.env.example` 的文档补充（补了无界面模式章节和 `PAICLI_TASK_PERMISSION_MODE`）。文档提到的环境变量在 `Main.java:1302` 确实被读取，默认 `read-only`，说明是补说明不是造新行为。

## harness 场景套件审查

做法是对的：每个场景走**真实的 `HeadlessRunner.run`**（不是 mock 局部），用 `ScriptedLlmClient` 当假模型，断言 status / 退出码 / 模型请求数 / 工具调用统计 / 提示数，最后 `@AfterAll` 写 `target/harness-metrics/scenarios.jsonl`。

11 个场景的实际输出：

| 场景 | exit | status | calls | ok | fail | skip |
|---|---|---|---|---|---|---|
| `direct_answer` | 0 | completed | 0 | 0 | 0 | 0 |
| `read_then_answer` | 0 | completed | 1 | 1 | 0 | 0 |
| `parallel_reads` | 0 | completed | 3 | 3 | 0 | 0 |
| `recover_from_edit_failure` | 0 | completed | 2 | 1 | 1 | 0 |
| `invalid_arguments_corrected` | 0 | completed | 2 | 1 | 1 | 0 |
| `truncated_tool_call_retried` | 0 | completed | 1 | 1 | 0 | **1** |
| `repeated_failure_reminder` | 0 | completed | 3 | 0 | 3 | 0 |
| `policy_denied_in_read_only` | 0 | completed | 1 | 0 | 1 | 0 |
| `budget_partial_result` | **3** | partial | 1 | 1 | 0 | 0 |
| `provider_failure` | **1** | failed | 0 | 0 | 0 | 0 |
| `known_issue_read_failure_counted_as_success` | 0 | completed | 1 | **1** | 0 | 0 |

覆盖到了截断（`skipped=1`）、预算（退出码 3）、供应商失败（退出码 1）、策略拒绝、失败恢复、提示计数。`success_rate` 在一次都没执行时是 `null` 而不是 0，这个细节处理得对。

`tools` 字段（`calls` / `succeeded` / `failed` / `skipped` / `success_rate` / `notices`）已经同步进 `docs/specs/headless-mode.md`，并标注了「2026-11-26 新增」和口径说明。文档跟得上代码，这点很难得。

## 那个「已知问题」确认属实，影响比注释写的大

注释里说：`read_file` 读不到文件时返回 `读取文件失败: …`，这个前缀不在 `ToolRegistry.looksLikeFailureText` 的名单里，于是被算成成功，修好前把 `succeeded/failed` 定格成 1/0。

我核了代码，属实：

- `ToolRegistry.java:369` 和 `:525` 返回 `"读取文件失败: …"`。
- `looksLikeFailureText` 的白名单是 `🛡️`、`❌`、`[HITL]`、`[AUTO]`、`用户取消了`、`工具执行失败`、`工具执行超时`、`未知工具`、`MCP 工具返回错误` —— **没有 `读取文件失败`**，也没有 `load_skill 读取文件失败`（`:905`）。
- `classifyOutput` 因此把它判成 `successful=true`。

**影响不止是成功率虚高。** `RunawayGuard.observe` 第 80 行是 `if (!result.successful())` 才把这次算进 `errorCounts`。所以读失败不进入「错误维度」的重复计数，`repeated_failure_reminder` 那套「同一个错误连续三次就换思路」的机制**对 `read_file` 是失效的**。

不过影响被限制住了：同一个方法里还有 `actionCounts`（只看方法名+参数的重复，不看成败），所以「反复读同一个文件」仍然会被动作维度的阈值兜住，不会无限循环。准确的结论是：**错误维度漏了，动作维度还在**。

修复建议：把 `读取文件失败` 和 `load_skill 读取文件失败` 加进前缀名单能让数字变准，但更好的做法是让这两个返回直接构造 `ToolOutput.failure(...)` —— 靠文本前缀猜成败，工具措辞一改就又漏了，而 `edit_file` 之所以表现正常，正是因为它带了 `failureCode`。注释里说「修复需要确认不影响评测口径」是对的，建议先补一个 `read_file` 连读三次失败、断言 `notices=1` 的场景，把「应该是什么样」钉下来，再改。

## 修正我上轮的建议

上一轮我建议「harness 场景别塞进 `-Pquick`，单独开 profile」。实测这个建议不成立：11 个场景全是假模型、毫秒级，`-Pquick` 从 34.1 秒变成 37.4 秒，只多了 3 秒。**放在 `-Pquick` 里是对的**——每 commit 都能看到数字，比单独 profile 更符合「每次提交都有 harness 数」的初衷。这条我收回。

## 对照上轮清单，还缺的三个

- **事件配对完整性**：`turn_started` 与 `turn_finished` 一一对应、每个 `tool_call_started` 都有 `finished` 或 `skipped`。现在靠场景断言统计数间接覆盖，没有直接断言配对。
- **退出码 130（取消）**：已有 0/1/3，缺取消。
- **陈旧 class 残留检查**：`clean` 后断言 `target/classes` 里没有已被删除的类。这条属于构建层，放 CI 或一个独立小脚本更合适，不必放进 harness 场景。

## 复现

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean test -Pquick && bash tools/test-failures.sh
cat target/harness-metrics/scenarios.jsonl
```
