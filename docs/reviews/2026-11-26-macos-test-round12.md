# macOS 测试反馈 · 第 12 轮（2026-11-26 19:05）

对象：`main` = `32ace6e` + 未提交改动（第 11 轮遗留的显示层/事件改动，叠加本轮的 Runtime API 事件转发）。
环境：macOS，JDK 21.0.12（`~/.local/bin/haocli` 已固定 21），Maven 默认 profile 之外的 `-Pquick`。

## 一、结论

| 项 | 结果 |
|---|---|
| `mvn clean test -Pquick` | **1787 通过 / 0 失败 / 0 错误 / 5 跳过** |
| 耗时 | 35.5 秒 |
| 失败清单（`tools/test-failures.sh`） | 空 |
| 与第 11 轮 | 1785 → 1787（+2） |

本轮改动集中在 Runtime API 把 Agent 事件转发进 SSE 流（`RuntimeApiServer` +114 行、`TaskRunner` 增加带 listener 的默认方法、`UnattendedTaskRunner` 透传），另把 `RuntimeApiServer` 里手写的字符串拼 JSON 换成了 Jackson。

## 二、这一轮改对了什么

**1. 手写转义换 Jackson，修掉了一个真 bug**

原实现只有 `\\ \" \r \n` 四个替换，**制表符、`\b`、`\f` 以及 U+0000–U+001F 其余控制字符不转义，拼出来就是非法 JSON**。模型回答里出现 Tab（代码缩进）或粘贴内容带控制字符时，客户端 `json.loads` 会直接失败。现在全部走 `MAPPER.createObjectNode()`，天然合法。测试里也专门断言了 `"第一行\t带制表符"`。

**2. 增量合并的方向正确**

`TurnEventWriter.MERGE_LIMIT = 512`，连续同类增量攒到 512 字符或来了异类事件才落库。思考/正文增量是每 token 一条，直接写 SQLite 会一轮几百次写；合并后条数降到个位。`flush()` 在 `runTurn` 的正常和异常分支都调了，最后一段不会丢——这点容易漏，测到了。

**3. 事件语义与无界面模式一致**

`agent.<type>` 的 `event` 字段就是 stream-json 的一行，字段名没有二次发明。`turn_id` 用 Runtime API 自己的 id，和 `event` 里 Agent 内部的 UUID 分开，文档也点明了「两者不是一回事」。

## 三、实机验证（零 token）

用不存在的模型名启动真实服务，走完整 HTTP 路径（`Main` → `UnattendedTaskRunner.run(prompt, listener)`），避免消耗额度：

```
PAICLI_RUNTIME_API_KEY=... FREELLMAPI_MODEL=not-a-real-model \
java -jar target/paicli-1.0-SNAPSHOT.jar serve --http --port 18080
```

```
POST /v1/threads                → thread_2563b4abc686
POST /v1/threads/{id}/turns     → turn_6f9832a4ae4d2
GET  /v1/threads/{id}/events    → 21 行 SSE / 7 个事件
```

事件序列与 JSON 校验：

```
thread.created
turn.started
agent.turn_started          turn_id=turn_6f9832a4ae4d2  inner_type=turn_started
agent.model_request_started  turn_id=turn_6f9832a4ae4d2  inner_type=model_request_started
agent.turn_finished          turn_id=turn_6f9832a4ae4d2  inner_type=turn_finished
message.delta
turn.completed
```

**7 个事件、0 条非法 JSON**，`agent.*` 的 `turn_id` 与外层一致。说明单元测试里注入假 runner 之外，真实接线也把 listener 传到了内核。

## 四、一个需要你决定的语义问题

失败场景下客户端会看到这个组合：

```
agent.turn_finished.event.status = "failed"
agent.turn_finished.event.answer = "❌ 调用 LLM 失败: API请求失败: 404 ..."
message.delta.content            = 同上那句错误文本
turn.completed                   { "turn_id": "...", "status": "completed" }
```

`turn.completed` 里的 `completed` 只表示「没抛异常」，真正的失败在 `agent.turn_finished.event.status`。文档确实写了这一点，但这意味着：**只订阅 `turn.completed` / `message.delta` 的客户端会把模型失败当成功，而且拿到的回答是一句错误文本。**

现有的 `RuntimeApiServerTest` 没有覆盖这个组合（4 个用例，只测了成功路径的顺序、结构化字段、合并、鉴权）。

建议二选一：

- 让 `runTurn` 在 `agentEvents` 收集到 `turn_finished.status != completed` 时改发 `turn.failed`（语义直白，但改变了「turn.completed 表示未抛异常」的原约定）；
- 或保留现状，给 `turn.completed` 的 `data` 加上 `"agent_status": "completed|partial|failed"`，让单看这个事件的人也能分辨。

补一个「模型失败 → 断言 agent.turn_finished.status=failed 且 turn.completed 带 agent_status」用例就能固定住。

## 五、沿用上轮的提醒

1. `TurnEventWriter.write` 里仍是 `payload.path("type").asText("unknown")`，和 `AgentEventJson` 的 `else { type = "unknown" }` 是同一处隐患：事件已经到 10 种，漏映射会静默变成 `agent.unknown`。建议一起改成抛异常。
2. `RuntimeThreadStore.createThread` 仍在手拼 `{"thread_id":"..."}`。目前只有生成的 id，没有注入风险，但和刚修掉的转义 bug 是同一个坑，顺手换 Jackson 更一致性。
3. `RuntimeApiServerTest` 只有 4 个用例。除了上面说的失败语义，**鉴权拒绝（错误 key / 无 key → 401）**、`thread 不存在 → 404` 也没测到。

## 六、环境备注

- 用 `nohup ... &` 启动的服务会随命令结束被回收，本机验证要用受管理的后台任务；这点和之前的 jar 交互验收一样。
- 服务启动仍会打印 JDK native access 的 4 行 WARNING（SQLite 触发），走 stderr，不影响 SSE。
