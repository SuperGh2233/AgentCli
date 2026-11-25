# 本机 macOS 测试报告（第 3 轮）

日期：2026-11-25 12:30 · 测试机：macOS（Apple Silicon）· 被测：`feat/steering-phase2b` @ `7c172d4` + 工作区未提交改动（Phase 1 第一层：`llm/core/`）
环境：JDK 21.0.12.1

## 结论

**全量 1727 个测试零失败零错误，33.4 秒。** 新增的 `llm/core/` 一层没有破坏旧路径，新旧消息互转我另做了独立验证，字段保真。新接口目前还没有生产使用方，属于“只加不改”的铺垫。

| 运行 | 结果 | 耗时 |
|---|---|---|
| `mvn clean test -Pquick`（JDK 21） | **1727 通过 / 0 失败 / 0 错误 / 5 跳过** | 33.4s |
| 独立的往返幂等探针（临时写、跑完删除） | 5 通过 / 0 失败 | 0.04s |

对比第 2 轮：1709 → 1727（+18，来自新增的 `LegacyMessageCodecTest` 等）。

## 这批改动做了什么

- 新增 `src/main/java/com/paicli/llm/core/` 十个类，834 行（含测试）：`ChatMessage`、`ContentBlock`、`ModelClient`、`ModelResponse`、`StreamEvent`、`StopReason`、`TokenUsage`、`ToolSpec`、`LegacyMessageCodec`、`LegacyModelClient`。
- 旧路径只动了 12 行：`LlmClient.ChatListener` 加了 `onFinishReason` 回调，`LlmClient.Message` 加了 `textOf`；`AbstractOpenAiCompatibleClient` 记录 `finish_reason` 并在成功返回前回调；`DeepSeekClient` 的包装 listener 把回调转发下去。

这个切法是对的：新接口不参与生产请求，旧路径行为不变，所以 1727 个既有测试仍然有效。

## 独立验证：新旧消息往返保真

对方的 `LegacyMessageCodecTest` 已经覆盖了典型对话、块顺序、空 reasoning、图片、多段文本、未知角色等。我另外补了五个组合做「旧→新→旧→新」二次转换，验证幂等：

| 组合 | 结果 |
|---|---|
| assistant 同时带 reasoning + 多行正文 + emoji + 两个 toolCall | 保真，arguments 原文不变 |
| tool_result 的 content 是空字符串 | 空串**没有**被强制变成 null，toolCallId 保留 |
| 三个 Text 段 | 仍是三段，没被合并 |
| 文本 + base64 图片（image/jpeg） | base64 与 mimeType 都保留 |
| 只有 toolCall、没有正文 | content 保持 null，工具调用完整 |

这五个探针跑完已删除，没有留在仓库里。

## finish_reason 的重试契约

文档写的是「只在成功结束时回调一次，中途重试的尝试不会回调」。代码里这个顺序是对的：`onFinishReason` 放在「API 返回空内容」等所有可能抛出的检查**之后**、`return` 之前。所以一次失败的尝试会抛异常、不会回调；调用方重试是一次新的 `chat()`，成功时才回调一次。契约成立。

## 需要提醒的两点

1. **新接口零接线**：`ModelClient` / `ChatMessage` 在 `llm/core/` 之外没有任何生产引用，`Agent`、`PlanExecuteAgent`、`AgentOrchestrator` 仍然直接用 `LlmClient`。所以现在全绿只证明「旧路径没坏」，不证明新接口在真实流量下可用。接线建议和计划 4.1 一起做，否则三套循环要各接一遍。
2. **`LegacyMessageCodec.toLegacy` 有一处依赖类型假设**：`content = ((ContentBlock.Text) visible.get(0)).text()` 之所以安全，是因为 `visible` 里只可能放 Text 和 Image，且只要有 Image 就一定走多段分支。将来 `ContentBlock` 加新类型（比如音频）时这里会直接 `ClassCastException`，建议改成 `instanceof` 判断。

## 复现

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean test -Pquick          # 1727 tests, 0 failures, ~34s
```
