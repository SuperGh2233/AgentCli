# 本机 macOS 测试报告（第 5 轮）

日期：2026-11-25 16:40 · 测试机：macOS（Apple Silicon）· 被测：`feat/steering-phase2b` @ `b7e9267` + 工作区未提交改动（含 `AgentStreamRendererTest` 改为直接构造）
环境：JDK 21.0.12.1

## 结论

**带 clean 的全量 1731 个测试零失败零错误，32.0 秒。** 打包出的 jar 在真实伪终端里跑了一遍，思考面板、工具调用、回答三项显示正常，和重构前的基线 jar 逐项对比格式一致。

## 1. 带 clean 的全量回归

```
mvn clean test -Pquick   # JDK 21
Tests run: 1731, Failures: 0, Errors: 0, Skipped: 5
BUILD SUCCESS   Total time: 32.021 s
```

`-Pquick` 本身没有输出失败行，符合预期。

**旧 class 确认已清干净**（这是“假通过”的关键）：

```
$ find target/classes -name 'Agent$StreamRenderer*'
（无输出）
$ ls target/classes/com/paicli/agent/ | grep -i streamrenderer
PlanExecuteAgent$TaskStreamRenderer.class
ReActStreamRenderer.class
SubAgent$SubAgentStreamRenderer.class
```

说明一下我上一轮的情况：那次跑的也是 `mvn clean test`，日志里是 4 个 `ClassNotFound` error，没有假通过。不过 AGENTS.md 新加的那条坑是对的——**只要漏掉 `clean`，`target/` 里残留的 `Agent$StreamRenderer.class` 就会让 `Class.forName` 找到旧类，测试假绿**。这条规则值得留着。

## 2. 真实 jar 验收

用 `.env` 里的 `freellmapi` 配置（模型 `qwen3.8-flash`），在 `/tmp` 的临时目录里建了两个文件，用 expect 驱动真实伪终端，输入「读取 a.txt，然后告诉我里面写了什么」。

三项都正常：

| 检查项 | 实际输出 |
|---|---|
| 思考 | `⠸ Thinking... (esc to cancel, 0s)` 转圈提示 + 逐字流式正文（先中文「用户要求读取 a.txt…」，后英文），结束后收成 `│ 用户要求…` 面板 |
| 工具调用 | `⏵ ReadFile(a.txt) (ctrl+o to expand)` 折叠块 |
| 回答 | `▪ a.txt 的内容：` + `⏵ code (2 行, ctrl+o to expand)` 代码块 + 结论「两行文本，分别是「苹果」和「香蕉」。」 |

启动信息也正常：`Hao CLI v16.1.0`、`Model qwen3.8-flash (freellmapi)`、`MCP 1/1 · 33 tools · 2/2 skills · ReAct`。

## 3. 和重构前逐项对比

用 `git worktree` 检出重构前的 `e8da513` 另打了一个 jar，同样的输入跑一遍：

| 项 | 重构前（`e8da513`） | 重构后（本次） | 判断 |
|---|---|---|---|
| 启动横幅 | `v16.1.0` / `MCP 1/1 · 33 tools` | 相同 | 一致 |
| 思考面板 | `Thinking...` + 逐字流式，结束收成 `│` 面板 | 相同 | 一致 |
| 工具调用 | `⏵ glob_files × 1 · {"pattern": "**/a.txt"} (ctrl+o to expand)`、`⏵ ReadFile(a.txt)` | `⏵ ReadFile(a.txt) (ctrl+o to expand)` | 格式一致 |
| 回答 | `▪ a.txt 里只有两行文字：` + `⏵ code (2 行, ctrl+o to expand)` | `▪ a.txt 的内容：` + `⏵ code (2 行, ctrl+o to expand)` | 格式一致 |

唯一的差别是本次模型没先 `glob_files` 就直接读了文件，所以少了那一行工具摘要——这是模型自己的选择，不是显示层的问题：`⏵ <工具>(<参数>) (ctrl+o to expand)` 和 `⏵ <工具> × N · <参数摘要>` 两种格式在基线里都出现过，本次输出与其中一种完全吻合。

## 4. 顺带确认

- `AGENTS.md` 28967 字节，确认低于 30000 上限；`CLAUDE.md` 565、`PAI.md` 737 字节，都正常。
- 临时 worktree 和 `/tmp` 测试目录都已删除，主工作区没有被改动。

## 5. 一个观察（不是问题）

`target/classes` 里除了新的 `ReActStreamRenderer`，还有 `PlanExecuteAgent$TaskStreamRenderer` 和 `SubAgent$SubAgentStreamRenderer` 两个同样角色的私有内部类。如果第二阶段要把 Plan 和子 Agent 也接到 `AgentLoop`，这两个可以和 `ReActStreamRenderer` 一起收成共用的 `Display` 实现，省掉两套重复的流式逻辑。

## 复现

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean test -Pquick          # 1731 tests, 0 failures, 32s
mvn package -DskipTests
java -jar target/paicli-1.0-SNAPSHOT.jar   # 真实终端里跑
```
