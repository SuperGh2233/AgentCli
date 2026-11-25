# 本机 macOS 测试报告（第 9 轮）+ 下一项的场景建议

日期：2026-11-25 23:58 · 测试机：macOS（Apple Silicon）· 被测：`feat/steering-phase2b` @ `0191af1` + 工作区未提交改动
环境：JDK 21.0.12.1

## 一、这轮验证结论

你说的状态我复核过，一致。

| 运行 | 结果 |
|---|---|
| 针对性 5 类（`UnattendedTaskRunnerTest`、`RuntimeApiServerTest`、`HeadlessRunnerTest`、`HeadlessOptionsTest`、`AgentSecurityRegressionTest`） | **26 通过 / 0 失败** |
| `mvn test -Pquick` | **1763 通过 / 0 失败 / 0 错误 / 5 跳过** |
| `bash tools/test-failures.sh` | 无输出 ✓ |

26 比你说的 24 多两个，是因为我把 `AgentSecurityRegressionTest`（5 个）和 `RuntimeApiServerTest`（2 个）也算进去了。

补充一点：全量数字和第 8 轮完全一样（都是 1763），说明 Runtime API 只读和流式回答这两处改动没有新增测试类，是靠既有用例覆盖的。这不算问题，但如果这两条是这轮的主要交付，建议给「只读默认值」和「流式回传」各补一个直接用例——现在它们只被间接覆盖，将来改坏了不一定有人发现。

## 二、对三个待决事项的意见

### 1. Commits：建议现在就拆，越晚越贵

我这几轮最实际的困难就是没有版本锚点：现在工作区有 985 增 / 1049 删、十几个新文件，横跨 phase 0 到 phase 3。第 4 轮那 4 个失败，如果当时你正好在改同一批文件，我没法判断失败属于哪个版本。

拆分建议按 phase，但**每个 commit 必须能独立编译并通过 `-Pquick`**，否则 `git bisect` 会失效：

| commit | 大致范围 |
|---|---|
| 1 | Plan Steering 安全点（`PlanSteeringSafePoint`、`Planner`/`ExecutionPlan` 修订、`TurnToolPolicy.tightenWithUserInput`） |
| 2 | MCP trust 符号链接修复 + `ResourceFailureObserver` 换根 |
| 3 | `llm/core`（`ModelClient`、`ChatMessage`、`LegacyMessageCodec`、`finish_reason` 回调） |
| 4 | ReAct 内核抽取（`AgentLoop`、`ReActStreamRenderer`、`ConversationContext`、`ToolResultSummaries`、`RequestContextLogger`） |
| 5 | headless `-p` + `agent/event` + 无人值守权限 + Runtime API 只读 |

第 2 个和第 1 个其实可以合并（都是安全修复），但分开更利于回溯。文件清单你那边列，我可以在你拆完之后逐个 commit 跑一遍验证——这是我能帮上的。

### 2. Harness regression scenarios：值得做，场景清单见第三节

方向我完全同意，成本也说得过去（假模型、不花 token）。但有两个前提建议：

- **不要塞进 `-Pquick`**。现在 quick 已经 1763 个、34 秒，再叠 harness 场景会拖慢日常回归。建议单独一个 profile（`-Pmetrics` 之类），只在看数字或 CI 的单独一步跑。
- **把「失败名单」也固化下来**，不只是通过率。我这 8 轮最有用的信息是“这一轮比上一轮多了哪几个失败”，而不是“这一轮有几个失败”。`tools/test-failures.sh` 已经在做这件事，harness 场景可以复用它。

### 3. Token budget：建议**暂不批准**

理由是我这几轮用真实模型做的验收（每次 1-3 次调用）已经覆盖了真正会变的表面：显示格式、事件流、退出码、安全拦截。再花的钱主要会用在“多模型对比”上，那属于评测范畴，而评测是明确暂停的。

更划算的顺序：先做 harness regression 拿到假模型的基线数字，那时你就知道“真实模型和假模型的差距在哪一段”——再决定要不要花 token 补那一小段对照。现在批预算，大概率会花在已经验证过的地方。

## 三、建议固化的回归场景（按我实际踩到的排优先级）

### P0：这轮已经暴露过的两类问题，必须固化成场景

**1. 移类/改名后的反射引用**

第 4 轮的真失败就是：`Agent$StreamRenderer` 搬成顶层 `ReActStreamRenderer`，测试用 `Class.forName` 反射旧名，**编译期完全看不出来**，只有跑测试才报 `ClassNotFound`；而如果 `target/` 里残留旧 class，甚至会假绿（AGENTS.md 已经加了这条坑）。

场景建议：一次 `clean` 后跑全量，断言 `target/classes` 里没有与被删类同名的残留文件。这条成本极低，能挡住一整类问题。

**2. 路径符号链接（macOS 特有）**

第 1 轮 15 个失败、第 2 轮修复，根因都是 `/var` → `private/var`。这类问题 Linux CI 永远看不到。

场景建议做成表驱动的路径矩阵，每条一行用例：

| 输入 | 期望 |
|---|---|
| 项目根在 `/var/folders/...`（`@TempDir`） | 允许 |
| 用户自建目录符号链接 | 拒绝 |
| `..` 穿越到项目外 | 拒绝 |
| 项目内软链指向外部文件 | 拒绝 |
| 绝对路径带原始前缀（`/var/...`）+ 真实根（`/private/var/...`） | 视为同一文件 |

前四条已有用例（`McpTrustStoreTest`、`AgentSecurityRegressionTest`），第五条是 `ResourceFailureObserver` 那轮的，建议统一到一张表里，避免以后新增一处路径判断又漏掉。

### P1：harness 数字本身的质量指标

你要的“工具调用数、成功率、截断数”之外，建议再加三个，它们能发现“跑通了但语义错了”的情况：

| 指标 | 为什么值得有 |
|---|---|
| **事件配对完整性** | 每个 `turn_started` 必须有对应的 `turn_finished`；每个 `tool_call_started` 必须有 `finished` 或 `skipped`。第 8 轮我是靠肉眼数 14 行事件确认的，这条应该自动断言 |
| **JSON 纯净性** | `--output-format json/stream-json` 的 stdout 每一行都能被解析，且没有 `unknown` 类型。第 7 轮我就是逐行 `json.loads` 验证的 |
| **退出码矩阵** | 0/1/2 已实测；3（超预算）和 130（取消）假模型下可以用预算/信号构造，比真实模型便宜得多 |

### P2：可以等

- 只读模式的权限矩阵（哪些工具被拒）——已有 `AgentSecurityRegressionTest` 覆盖，做成表驱动收益中等。
- `web_fetch` 的 URL 来源检查在 unattended 路径下是否生效——这条我上轮提过还没确认，但它属于安全审查，不是回归指标，建议单独确认一次而不是放进 harness 场景。

## 复现

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean test -DskipTests=false -Dtest='UnattendedTaskRunnerTest,RuntimeApiServerTest,HeadlessRunnerTest,HeadlessOptionsTest,AgentSecurityRegressionTest'
mvn test -Pquick && bash tools/test-failures.sh
```
