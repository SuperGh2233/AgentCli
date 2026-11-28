# HaoCli 架构收敛路线

状态：2026-11-28 完成 P0 内核统一、最小 SDK、上下文装配和工具契约迁移；会话记忆摘要、正式评测未完成。本文件记录这次冲刺的结果和下一步；原有主线计划 [PLAN-20261126-runtime-roadmap](PLAN-20261126-runtime-roadmap.md) 仍然有效，本文件的完成项对应它的“丙 统一执行”和“甲-7 进程内入口”。

架构说明：[运行时架构](../../architecture/HAO-RUNTIME-ARCHITECTURE.md) · [上下文工程](../../architecture/CONTEXT-ENGINEERING.md)

## 1. 这次完成的（都有提交和测试）

| 项 | 提交 | 证据 |
|---|---|---|
| 内核不再 new 具体模块；模块由 `DefaultTurnHooks` 组装后注入；模块出错隔离；工具结果改写不能破坏配对；`Turn.toolsEnabled` | `dc73943` | `UnifiedKernelTest`（6 个） |
| Plan 任务节点、Team 子 Agent 改为调用 `AgentLoop`，删除各自的循环；评测观察事件、失败异常路径、会话记录来源标记保持不变 | `dc73943` | Plan 121 个、Team 62 个测试全过；`-Pbenchmark` 失败名单和 `38bdb3b` 一致 |
| 最小 `HaoRuntime` SDK（模型、工具、上下文、权限、上限、事件、取消、结构化结果）+ 独立示例 | `d41f4cf` | `HaoRuntimeExampleTest`（3 个） |
| `ContextBlock` 的 trusted / pinned 由代码执行；系统提示词按预算装配；Skill 索引和 HAO.md 目录按任务相关性排序 | `0dbc9d9` | `ContextAssemblyBudgetTest`（4 个） |
| 上下文质量确定性评估（关键信息保留、配对、节省 Token） | 本次文档提交 | `CompactionQualityTest`（2 个）：32,875 → 16,211 Token |
| 工具契约第二批：write_file、create_project、search_code、save_memory、revert_turn 改为自己报告状态（`ToolRegistry.StatusTool`）。修掉的问题：这些工具的失败文字不在旧前缀名单里，以前全被算成成功；revert_turn 改用快照服务自己的 `success` 标志 | 本次提交 | `SelfReportingToolsTest`（4 个）；quick 1854 / 0；评测包失败名单不变 |
| 工具契约第三批：edit_file、web_fetch、浏览器 3 个工具、save_skill_reference 改为自己报告状态；web_fetch 被网络策略拒绝记为 DENIED（以前是 FAILED），未初始化的浏览器连接器明确记为失败 | 本次提交 | `SelfReportingToolsTest` 第 5 个用例；quick 1855 / 0；安全 + HITL 199 / 0；评测包失败名单不变 |
| 工具契约收尾：load_skill（读附属文件时沿用 read_file 的成败判定）、execute_command、web_search、MCP 占位改为 `StatusTool`；删除 `LegacyTool` / `ToolExecutor` 和 MCP 结果的前缀重新分类 | 本次提交 | 工具 / MCP / HITL / 场景 333 / 0；`mvn clean` 后 quick 1855 / 0；安全 310 / 0；TUI 150 / 0；评测包失败名单不变 |
| 评测发现的缺陷：单次模型请求 600 秒总时长上限会掐断仍在输出的长回复（regex-chess 经 magpie 稳定复现）。改为默认不限总时长，卡死仍由 300 秒读取间隔发现，`-Dhaocli.llm.call.timeout.seconds=N` 可设硬上限 | 本次提交 | `LlmCallTimeoutTest`；LLM 测试 114 / 0；效果待 magpie 重跑 regex-chess 验证 |
| magpie 冒烟（当前代码） | — | `haocli-0dbc9d9-dirty-smoke-20261128-104814` 1/1，`-dirty` 只因两个未提交的评测文档 |

## 2. 没完成的（按优先级）

1. ~~工具契约迁移~~（2026-11-28 完成）：18 个内置工具和 MCP 工具全部自己报告状态，`LegacyTool` 和 `ToolExecutor` 已删除，调度层不再特判 execute_command / web_search，也不再对 MCP 结果按文字重新分类（客户端按协议 `isError` 报告）。`looksLikeFailureText` 只留给一条兼容路径：子类重写旧的 `String executeTool(...)` 接口时只有文字可用（部分测试和旧扩展）。剩下的工作是把 `StatusTool` 里的工具逐个搬成独立类，属于整理，不再影响状态的正确性
2. **会话记忆摘要**（SessionMemoryCompactor）：更正——异步提前准备、增量更新、过期结果保护、按对话隔离和失败回退**早已实现**，之前的判断没读代码；2026-11-28 补了 5 个确定性并发测试（`SessionMemoryConcurrencyTest`）。还缺的是真实模型的开关对照数据，所以仍默认关闭
3. **正式评测**：当前代码的 ctx-on / ctx-off 对照没跑（30 题 × 3 次约 1 亿输入 Token，需要先确认 WorkBuddy 额度）；只跑了冒烟
4. **SDK 扩展**：自定义模块已完成（`HaoRuntime.Hook` + 只读 `View`，`148440a`）；还没有多轮会话
5. **安全统一检查链**：已有证据（2026-11-28，`ExecutionEntryPolicyTest`）表明 ReAct、Plan 任务节点、Team 子 Agent、SDK、`-p` 五个入口都经过同一套确定性检查——假模型读项目外密钥和执行黑名单命令在每个入口都被拒绝、调用和结果配对、密钥不进模型请求。拒绝原因已统一（2026-11-28）：每个拒绝结果带 `FailureCode.DENY_*` 说明是哪一层（路径、命令、上限、本轮策略、无人值守白名单、权限规则、自动审查、人工、网络），进事件和 `-p` JSON 的 `reason`，拦截行为不变（`DenialReasonTest`）。还没做的是把分散在 `ToolRegistry`、`HitlToolRegistry`、`HeadlessToolRegistry` 的检查真正合成一条链（有了统一原因，合并前后可以逐条核对），以及交互默认 `auto` 沙箱（ADR-0003）
6. **拆 Main**（3500 行）、**Maven 模块拆分**：沿用原主线计划的顺序；依赖方向测试已完成（`ArchitectureBoundaryTest`，`148440a`）

## 3. 下一阶段建议

1. 工具契约迁移第二批（只读的 search_code、load_skill），每批跑 `HarnessScenarioSuiteTest` 和评测包失败名单对比
2. 在 magpie 上做小规模可复现对照：先 5 题 × 1 次的 ctx-on / ctx-off，确认 `context` 统计和失败分类可用，再决定是否跑 30 题 × 3 次
3. `TurnHook.Context` 改成只暴露稳定能力（消息只读视图、追加、通知、事件），然后在 SDK 开放 `hooks(...)`
4. 会话记忆摘要先写确定性并发测试（过期摘要不能覆盖新状态），再实现增量更新
