# 本机 macOS 测试报告（第 6 轮）

日期：2026-11-25 17:30 · 测试机：macOS（Apple Silicon）· 被测：`feat/steering-phase2b` @ `4fbc466` + 工作区未提交改动（Phase 2 收尾）
环境：JDK 21.0.12.1

## 结论

**两条命令都通过，Phase 2 可以关掉。**

| 命令 | 结果 |
|---|---|
| `mvn clean test -Dtest='AgentLoopTest,LegacyModelClientTest,ReActLoopReplayTest,ModelClientReplayTest,LegacyMessageCodecTest'` | **36 通过 / 0 失败 / 0 错误**，9.4s |
| `mvn test -Pquick` | **1736 通过 / 0 失败 / 0 错误 / 5 跳过** |
| `bash tools/test-failures.sh` | 无输出 ✓ |

五个类的明细：

| 测试类 | 数量 |
|---|---|
| `LegacyMessageCodecTest` | 12 |
| `ReActLoopReplayTest` | 9 |
| `AgentLoopTest` | 7（上轮 4，新增截断工具调用、截断回答、正常停止原因三个） |
| `ModelClientReplayTest` | 6 |
| `LegacyModelClientTest` | 2 |

## 我额外验证了 test-failures.sh 本身

因为这个脚本“输出为空”和“脚本坏了”看起来完全一样，所以我单独验了它：

- 当前 `target/surefire-reports` 有 228 份 XML，直接 `grep '<failure\|<error'` 计数为 **0**，和脚本的空输出一致。
- 另外造了一份含 1 个 failure、1 个 error 的假报告喂给它，能正确输出：

```
com.fake.Demo#fails
com.fake.Demo#errors
```

脚本本身是可靠的。（顺带说明：之前有一次它在我这里“有失败却输出为空”，原因是我先跑了别的 `mvn` 命令把 surefire-reports 覆盖了，不是脚本的问题。）

## Phase 3 开始前的三条输入

既然下一步是「带类型的事件 + `-p "task"` JSON 输出」，有几点会影响设计，先说在前面：

**1. 当前的桥接层发不出“实时工具调用事件”**

`LegacyModelClient.exchange` 的顺序是：思考和正文增量实时发 → **等 `delegate.chat()` 整条返回** → 才批量补发 `ToolCallCompleted`、`UsageReported`、`Completed`。所以前端看到的是正文流完、突然冒出工具调用。

但底层其实已经有增量了：`AbstractOpenAiCompatibleClient` 第 289 行 `mergeToolCallDeltas(toolAccumulators, delta.path("tool_calls"))` 每收到一块就合并，**只是没有回调给 listener**。如果 Phase 3 想让事件流反映真实时序（比如 JSON 里能看出模型先说了一句话、再调用工具），在那一行后面加一个 `onToolCallDelta` 回调就够了，不用改任何供应商适配。如果决定接受“最终态回放”语义，那就明确写进协议文档，别让后面看 JSON 的人误以为是实时顺序。

**2. `-p` 模式必须绕开终端初始化**

这台机器上 JDK 25 跑终端测试会挂死在 `JniNativePty.close`。`-p` 无界面模式如果复用了会创建 JLine 终端的那条路径，在没有 TTY 的 CI 或管道环境里就会出现同类的挂起。建议 `-p` 完全不碰 `Main` 的终端装配，直接用 `Renderer` 的一个纯文本实现。

**3. JSON 输出要和日志分开**

`stdout` 只放 JSON（单行或稳定的行分隔），日志和警告走 `stderr`。现在 `target/` 里那份 jar 启动时会往 stdout 打 `WARNING: A restricted method...`（JDK 的 native access 警告），这类内容混进 JSON 会直接让下游解析失败。退出码也建议定下来：任务完成 0、模型/工具失败非 0、被取消单独一个码。

## 一个小建议

`LegacyModelClientTest` 只有 2 个用例（透传原样返回、`length` → `MaxTokens`），相对它是「新旧接口的唯一桥梁」这个位置偏薄。建议补两个：事件序列（`ToolCallCompleted` 在 `Completed` 之前、`UsageReported` 只在有值时才发）、以及传 `null` consumer 时回落到非流式且 `finishReason` 为 null 的那条分支。

## 复现

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean test -DskipTests=false -Dtest='AgentLoopTest,LegacyModelClientTest,ReActLoopReplayTest,ModelClientReplayTest,LegacyMessageCodecTest'
mvn test -Pquick && bash tools/test-failures.sh
```
