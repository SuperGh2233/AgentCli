# 本机 macOS 测试报告（第 4 轮）

日期：2026-11-25 15:25 · 测试机：macOS（Apple Silicon）· 被测：`feat/steering-phase2b` @ `e8da513` + 工作区未提交改动（抽 ReAct 内核）
环境：JDK 21.0.12.1

## 结论

**1731 个测试，4 个失败，全部同一个根因：重构把 `Agent` 的内部类改成了顶层类，但有测试用字符串反射加载旧类名，编译器发现不了。** 改成直接构造后 4 个全部通过（我临时验证过，已还原）。生产逻辑本身没有失败。

| 运行 | 结果 | 耗时 |
|---|---|---|
| `mvn clean test -Pquick`（JDK 21） | 1731 个，**4 个 error**，0 个 assertion failure | 33.8s |
| 改掉 4 处反射后单独跑 `AgentStreamRendererTest` | 4 通过 / 0 失败 | 0.03s |

## 唯一的失败：反射引用了已经搬走的内部类

```
AgentStreamRendererTest.inlineThinkingPanelReceivesReasoningInsteadOfTranscriptHeading:89
  » ClassNotFound com.paicli.agent.Agent$StreamRenderer
（另外 3 个 same cause）
```

`src/test/java/com/paicli/agent/AgentStreamRendererTest.java:108,115`：

```java
Class<?> rendererClass = Class.forName("com.paicli.agent.Agent$StreamRenderer");
Constructor<?> constructor = rendererClass.getDeclaredConstructor();
constructor.setAccessible(true);
return (LlmClient.StreamListener) constructor.newInstance();
```

重构后 `Agent$StreamRenderer` 变成了顶层类 `ReActStreamRenderer`，而且可见性从 private 内部类变成包级，测试和它同包，**根本不需要反射**。

**已验证的改法**（把第 105-120 行那两个方法整体替换）：

```java
private LlmClient.StreamListener newStreamRenderer() {
    return new ReActStreamRenderer();
}

private LlmClient.StreamListener newStreamRenderer(Renderer renderer) {
    return new ReActStreamRenderer(renderer);
}
```

`ReActStreamRenderer` 实现的 `AgentLoop.Display` 继承了 `LlmClient.StreamListener`，所以返回类型不用改。我临时改完跑了一次，4 个用例全过，随后已把文件还原（工作区里这个测试文件仍然是你原来的样子）。

顺带：全仓库只有这一处反射引用旧内部类名，`grep 'Agent\$StreamRenderer'` 只有这两行。

## 重构概况与等价性抽查

| 文件 | 行数 |
|---|---|
| `Agent.java` | 1505 → **644**（改动 932 增 / 1017 删） |
| `AgentLoop.java`（新，内核循环） | 477 |
| `ReActStreamRenderer.java`（新，流式显示） | 305 |
| `ConversationContext.java`（新，历史与 checkpoint） | 155 |
| `ToolResultSummaries.java`（新） | 107 |
| `RequestContextLogger.java`（新） | 99 |
| `AgentLoopTest.java`（新） | 207 |

抽查了几条 AGENTS.md 点名的规则，搬家是忠实的：

- **工具仍然走 `TurnToolPolicy.execute` → `ToolRegistry.executeTools`**，没有绕开（`AgentLoop.executeToolCalls` 里那行 `turn.toolPolicy().execute(...)`）。
- **`view_checkpoint` 覆盖了自动压缩、手动压缩、恢复会话**三处（`ConversationContext:126/138`、`Agent:255`）。
- **`pruneHistoricalImagePayloads` 与重构前逐字一致**（我对比了 `git show HEAD` 的版本）。
- `Agent.runInternal` 现在只做「算策略 → 注入记忆 → 追加用户消息 → 组装 Turn → 委托」，符合拆分意图。

## 两个需要你判断的点

1. **既有缺陷（不是这次引入的）**：`pruneHistoricalImagePayloads` 直接改写 `conversationHistory`（把历史消息里的图片 base64 剥掉），但没有写 `view_checkpoint`。这和 AGENTS.md 里「直接改 `conversationHistory`（压缩、清理工具结果、恢复会话）之后必须写 `view_checkpoint`」冲突。重构前后都是这样，所以不是回归，但它会让 `/resume` 还原出的对话带上模型当时没看到的图片。建议把这一步并进 `ConversationContext` 统一记 checkpoint。

2. **新内核的单元测试偏薄**：`AgentLoopTest` 只有 4 个用例（收尾钩子顺序、provider 失败、首次请求前取消、迭代上限）。压缩触发、LSP 诊断注入、图片工具消息、steering 安全点与工具执行的先后顺序，目前只有重构前的集成测试在兜底。搬家最容易在这几处丢细节，建议给内核补 4-6 个直接用例，比靠 `Agent` 端到端更快定位问题。

## 复现

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean test -Pquick                                   # 1731 tests, 4 errors
mvn test -DskipTests=false -Dtest='AgentStreamRendererTest'   # 单独复现 4 个
```
