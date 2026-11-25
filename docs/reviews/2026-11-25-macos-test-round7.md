# 本机 macOS 测试报告（第 7 轮）

日期：2026-11-25 19:35 · 测试机：macOS（Apple Silicon）· 被测：`feat/steering-phase2b` @ `1482469` + 工作区未提交改动（Phase 3：headless `-p`）
环境：JDK 21.0.12.1

## 结论

**两条门禁命令都过，实机检查也通过。`-p` 的三种输出格式、退出码、stdout/stderr 分流，全部和 `docs/specs/headless-mode.md` 写的一致。** Phase 3 可以关。

| 项 | 结果 |
|---|---|
| 针对性 4 类（`HeadlessOptionsTest`、`HeadlessRunnerTest`、`AgentLoopTest`、`ReActLoopReplayTest`） | **32 通过 / 0 失败**，12.5s |
| `mvn test -Pquick` | **1752 通过 / 0 失败 / 0 错误 / 5 跳过** |
| `bash tools/test-failures.sh` | 无输出 ✓ |

四个类明细：`ReActLoopReplayTest` 9、`AgentLoopTest` 10（上轮 7）、`HeadlessRunnerTest` 8、`HeadlessOptionsTest` 5。

## 实机检查（4 次调用，其中 2 次是 404 不花 token）

### `-p` 默认 text 模式

```
$ java -jar target/paicli-1.0-SNAPSHOT.jar -p "In one sentence, what does this project do?"
EXIT=0   5.4s
stdout: 1 行中文回答
stderr: 空
```

stderr 完全为空，注意**连 JDK 那条 `WARNING: A restricted method...sqlite` 都没有**——对比交互模式启动时必打这条，说明 headless 走了不碰 SQLite / 不碰 JLine 的精简路径。这正是上一轮担心的两个点，实测没有发生。

### `--output-format stream-json`

```
EXIT=0   8.8s   151 行，全部是合法 JSON
```

逐行 `json.loads` 校验：**非法行 0**。事件分布：

| type | 条数 |
|---|---|
| `text_delta` | 113 |
| `reasoning_delta` | 31 |
| `usage` | 2 |
| `turn_started` / `tool_call_started` / `tool_call_finished` / `turn_finished` / `result` | 各 1 |

实测顺序：`turn_started` → `reasoning_delta`×31 → `usage` → `tool_call_started` → `tool_call_finished` → `reasoning_delta`×11 → `text_delta`×113 → `usage` → `turn_finished` → `result`。

两点确认：`turn_started` 在首行、`result` 在末行、`tool_call_started` 在 `tool_call_finished` 之前。**工具调用事件已经是执行前就发出的真流式，不再是请求结束后批量补发**——上一轮报告里提的那个桥接层限制，这轮已经解决。

### `--output-format json`

```
EXIT=0   1.9s   1 行，合法 JSON，顶层键 11 个
```

同时验证了从标准输入读任务（`-p` 后面不带值，用管道喂任务），工作正常。

## 退出码与分流契约（逐条对照文档）

| 场景 | 文档 | 实测 | 一致 |
|---|---|---|---|
| 正常完成 | 0 | 0 | ✓ |
| 调用模型失败（用一个不存在的模型名触发 404） | 1 | `json` 模式：`is_error:true`、`status:"failed"`、错误原文在 `result` 里、**仍是一行合法 JSON**；`text` 模式：stdout **0 字节**、错误进 stderr | ✓ |
| 参数错误（`--output-format bogus`） | 2 | 2，stdout 空，错误进 stderr | ✓ |
| 任务为空（`-p` + 空 stdin） | 2 | 2，提示「任务为空」 | ✓ |
| 部分完成 | 3 | 未触发（需要压预算） | 未验证 |
| 取消 | 130 | 未触发 | 未验证 |

失败时 JSON 仍然可解析这一点很关键——下游脚本不用为错误路径写特殊解析分支。

## 副作用检查

`-p` 跑完在项目里没有留下会话文件（`.paicli` 里还是 11-02 的旧 `tool-outputs`），符合无界面模式应当幂等的预期。

## 两个小建议（都不是阻塞项）

**1. `-p` 后面跟以 `--` 开头的任务会被当成选项**

```
$ java -jar ... -p "--help 这个参数是做什么的"
❌ 无界面模式不认识的参数: --help 这个参数是做什么的     (exit 2)
```

解析逻辑是 `!args[i+1].startsWith("--")` 才当作任务，所以任务本身以 `--` 开头就落不到 prompt 上。好处是 fail-fast 不静默，但错误信息说“不认识的参数”会让用户以为自己写错了选项。建议支持 `--` 分隔符（`-p -- "--help ..."`），或者在用法里补一句「任务以 `--` 开头时用 `-p -` 从标准输入传」。

**2. 裸 `-p` 会静默等标准输入**

`java -jar ... -p` 后面什么都不带时，实现是从 stdin 读任务。在管道里没问题，但在交互终端误敲回车后会看起来像卡死（我这边等了 120 秒才被超时杀掉）。建议往 stderr 打一行「正在从标准输入读取任务…（Ctrl+D 结束）」，不影响 stdout 的纯净。

## 未覆盖的部分

- 退出码 `3`（超预算/停滞）和 `130`（取消）：前者需要把预算压到很低才触发，后者需要在运行中发信号，都属于手工干预场景，建议由你按需验一次。
- `tool_call_skipped`（`output_truncated`）和 `notice` 事件：这次的调用没触发，`AgentLoopTest` 里有截断用例覆盖逻辑，但事件层的输出还没有实测。
- `--permission-mode read-only|edit` 和 `--allow-command` 的行为没测（本轮两次调用都是只读操作，没走到审批分支）。

## 复现

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean test -DskipTests=false -Dtest='HeadlessOptionsTest,HeadlessRunnerTest,AgentLoopTest,ReActLoopReplayTest'
mvn test -Pquick && bash tools/test-failures.sh

mvn clean package -DskipTests
java -jar target/paicli-1.0-SNAPSHOT.jar -p "In one sentence, what does this project do?"
java -jar target/paicli-1.0-SNAPSHOT.jar -p "List the packages under src/main/java/com/paicli" --output-format stream-json

# 失败路径（零 token）
FREELLMAPI_MODEL=definitely-not-a-real-model java -jar target/paicli-1.0-SNAPSHOT.jar -p "hi" --output-format json
```
