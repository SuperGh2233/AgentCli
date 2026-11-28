# 开发文档入口

| 文件 | 状态 | 用途 |
|---|---|---|
| [运行时架构](architecture/HAO-RUNTIME-ARCHITECTURE.md) | active | 最小内核 AgentLoop、模块组装、Plan / Team / SDK 统一执行、职责边界 |
| [上下文工程](architecture/CONTEXT-ENGINEERING.md) | active | 统一上下文生命周期、五层压缩、渐进式提示词加载、ContextBlock 元数据 |
| [架构收敛路线](plans/active/HAO-ARCHITECTURE-ROADMAP.md) | active | 2026-11-28 冲刺完成项、未完成项和下一步 |
| [完整说明（原 README）](guide.md) | active | 配置、全部命令和工具、各期功能与演进历程；首页 README 只放概览 |
| [后续路线：可嵌入的 Agent 运行时](plans/active/PLAN-20261126-runtime-roadmap.md) | active | 当前主线：P0 → 契约 → 统一执行 → 目标循环 → 安全 → 拆 Main → 上下文 → 拆模块 → 收尾 |
| [ADR-0001 架构边界](adr/ADR-0001-architecture-boundaries.md) | accepted | 4 个模块、只做显式注册、包名不改 |
| [ADR-0002 结果语义](adr/ADR-0002-result-semantics.md) | accepted | 工具状态自己报告；执行状态和目标状态分开；Runtime API 失败改发 `turn.failed` |
| [ADR-0003 命令沙箱](adr/ADR-0003-sandbox-escalation.md) | accepted | 交互默认 auto，沙箱外重跑只能由人批准，无人值守永不升级 |
| [Harness 升级计划（第 0–3 阶段）](plans/active/PLAN-20261124-harness-upgrade.md) | completed | 内核 `AgentLoop`、事件协议、无界面模式的实施和验收历史；后续顺序已被新路线取代 |
| [阶段基线：`38bdb3b` ctx-on](evals/baselines/2026-11-27-38bdb3b-ctx-on.md) | active | 2026-11-27 Terminal-Bench 那一轮的代码版本、架构快照、37 次有效结果和 53 次补跑计划 |
| [无界面模式约定](specs/headless-mode.md) | active | `-p` 参数、事件 JSON 格式、退出码 |
| [HITL 显示修复](plans/active/PLAN-20261103-hitl-display.md) | completed | 审批显示编码、完整命令与七类 / 97 项离线回归记录 |
| [Side-Git 恢复安全](plans/active/PLAN-20261103-sidegit-restore-safety.md) | completed | 最终修复后 18 项隔离离线定向回归通过；含已记录的 TOCTOU 边界 |
| [agents-reference.md](agents-reference.md) | active | 项目行为和模块说明 |
| [phase-11-mcp-advanced.md](phase-11-mcp-advanced.md) | legacy | 第 11 期范围与后续补充 |
| [MCP OAuth 约定](specs/mcp-oauth.md) | completed | OAuth 配置、存储与安全边界 |
| [MCP OAuth 实施记录](plans/active/PLAN-20261103-mcp-oauth.md) | active | OAuth 与追加修正离线回归完成；真实 AS/浏览器联调待执行 |
| [articles/README.md](articles/README.md) | active | 配套文章索引 |
| [MCP 双协议约定](specs/mcp-protocol-eras.md) | completed | 显式版本、现代请求及限制 |
| [MCP 双协议实施记录](plans/active/PLAN-20261103-mcp-protocol-eras.md) | completed | 本阶段范围、离线验证与下一步 |
| [MCP MRTR 约定](specs/mcp-mrtr.md) | active | 多轮请求和 elicitation 用户输入 |
| [MCP MRTR 实施记录](plans/active/PLAN-20261103-mcp-mrtr.md) | active | MRTR-1/2/3/4 离线回归完成；真实终端手工验收待执行 |
