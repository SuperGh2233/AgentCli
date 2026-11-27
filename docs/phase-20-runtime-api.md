# 第 20 期：异步后台任务 + Runtime API

> 当前状态：MVP 已落地。第 20 期补齐无头与后台执行入口；第 21 期 图片输入 已独立完成，不依赖本期 API。

## 已交付

### 后台任务

- `DurableTaskManager`：SQLite 持久化任务队列
- 默认数据库：`~/.haocli/tasks/tasks.db`
- 生命周期：
  - `enqueued`
  - `running`
  - `completed`
  - `failed`
  - `canceled`
- Worker Pool：默认 2 个后台 worker，可用 `HAOCLI_TASK_WORKERS` 或 `-Dhaocli.task.workers` 覆盖
- 进程启动时把上次残留的 `running` 任务恢复为 `enqueued`
- CLI 命令：
  - `/task` 或 `/task list [N]`
  - `/task add <任务内容>`
  - `/task cancel <task_id>`
  - `/task log <task_id>`

### Runtime API

实现位于 `src/main/java/com/haocli/runtime/api/`，使用 JDK 内置 `HttpServer`，不引入 Spring / Javalin。

启动：

```bash
HAOCLI_RUNTIME_API_KEY=your_local_api_key \
java -jar target/haocli-1.0-SNAPSHOT.jar serve --http --port 8080
```

安全策略：

- 仅监听 `127.0.0.1`
- 必须配置 `HAOCLI_RUNTIME_API_KEY` 或 `-Dhaocli.runtime.api.key`
- 请求头支持：
  - `Authorization: Bearer <key>`
  - `X-Hao CLI-API-Key: <key>`

端点：

- `POST /v1/threads`：创建 thread
- `POST /v1/threads/{id}/turns`：提交一轮 Agent 输入，异步执行
- `GET /v1/threads/{id}/events`：以 SSE 格式回放事件

事件类型：

- `thread.created`
- `turn.started`
- `message.delta`
- `turn.completed`
- `turn.failed`
- `agent.<type>`（2026-11-26 起）：Agent 执行过程中的每个事件，`<type>` 和无界面模式 stream-json 的 `type` 一样（`turn_started`、`model_request_started`、`reasoning_delta`、`text_delta`、`tool_call_started`、`tool_call_finished`、`tool_call_skipped`、`notice`、`usage`、`turn_finished`），字段说明见 `docs/specs/headless-mode.md`

`agent.*` 事件的 `data` 是 `{"turn_id":"turn_…","event":{…}}`：`turn_id` 是 `POST /turns` 返回的 id，`event` 和 stream-json 的一行完全相同（`event` 里的 `turn_started` / `turn_finished` 还有 Agent 内部自己的 `turn_id`，两者不是一回事）。连续的思考 / 正文增量会先合并（最多 512 个字符一条）再写入，所以条数比 token 少。以后可能加新的事件类型，客户端遇到不认识的事件名直接跳过。

结束事件按模型那一轮的执行状态发（2026-11-26 起，`docs/adr/ADR-0002-result-semantics.md`）：

| 执行状态 | 结束事件 |
|---|---|
| `completed` | `message.delta`（整段回答）+ `turn.completed`，`data` 带 `"agent_status":"completed"` |
| `partial`（到达上限或停滞后整理出的部分结果） | `message.delta` + `turn.completed`，带 `"agent_status":"partial"` |
| `failed` / `cancelled` | 只发 `turn.failed`，`error` 是失败原因，带 `agent_status`；不发 `message.delta` |

之前模型调用失败时也发 `message.delta`（内容是错误文本）和 `turn.completed`，只看外层事件的客户端会把失败当成功（第 12 轮测试报告发现）。客户端判断“这一轮结束了”要同时监听 `turn.completed` 和 `turn.failed`。

每个任务默认最多 50 次模型请求、200 万 Token、30 分钟，用环境变量 `HAOCLI_UNATTENDED_MAX_TURNS`、`HAOCLI_UNATTENDED_MAX_TOKENS`、`HAOCLI_UNATTENDED_TIMEOUT` 调整（细则见 `docs/specs/headless-mode.md` 的“上限”）。

所有事件的 `data` 都由 Jackson 生成，回答里有制表符等控制字符时也是合法 JSON（之前手写转义会写出不合法的 JSON）。

## 当前边界

- Runtime API MVP 是事件回放式 SSE，不做长连接持续阻塞推送
- 后台任务 runner 使用 headless ReAct Agent，不复用交互式 TUI 的 HITL 输入
- 后台任务取消通过线程中断 + 状态标记实现；正在进行的远端 LLM HTTP 调用能否立即停止取决于底层 client 边界
- Runtime API 当前不模拟完整 OpenAI Assistants API schema，只保留兼容方向的 threads / turns / events 主路径

## 验证

```bash
mvn test -Dtest=DurableTaskManagerTest,RuntimeApiServerTest,CliCommandParserTest
```

建议回归：

```bash
mvn test -Pquick
mvn test
mvn -q clean package -DskipTests
HAOCLI_RUNTIME_API_KEY=test java -jar target/haocli-1.0-SNAPSHOT.jar serve --http --port 0
```
