# HITL 权限规则持久化

状态：active；Stage 3A（规则模型、纯函数裁决与安全持久化）代码和 16 项隔离离线定向回归已通过，待独立提交、推送。Stage 3B 再接入审批 UI、授权预览和工具执行链。

## 背景与当前行为

- `TerminalHitlHandler`、`RendererHitlHandler`、`TuiHitlHandler` 各自维护进程内的 `approvedAllByTool` / `approvedAllByServer` 集合。
- 当前 `a` 仅代表本次会话放行；重启后失效，且 `execute_command` 只能按整个工具授权。
- 当前 `ApprovalPolicy` 是工具名静态规则，尚无统一的规则匹配与冲突裁决层。
- 当前 UI 的拒绝原因只作为本次审批结果反馈，尚无规则管理界面。

## 目标

1. 引入可测试、纯函数的权限规则裁决：`deny > ask > allow`。
2. 支持精确工具、MCP server、Shell 命令前缀、项目内路径前缀四类 scope。
3. 规则绑定项目真实根路径，避免在不同项目间意外复用路径或命令授权。
4. 提供用户级、版本化 JSON 持久化；安全目录、文件权限、文件锁和原子替换必须 fail closed。
5. 只有后续 UI 明确展示并确认了规则范围，才能保存永久 allow；现有 `a` 继续代表会话级授权，不得悄悄改变语义。
6. `allow` 只影响 HITL 是否弹框，不得绕过参数校验、CommandGuard、PathGuard、CommandSandbox、敏感页面逐次确认或其他硬策略。
7. 持久化文件损坏、权限不安全或锁失败时，不得应用 allow 规则；给用户明确警告，保持默认确认行为。

## Stage 3A：规则核心与安全存储

### 数据模型

- `PermissionRule`：id、projectRoot、effect、scope、matcher、createdAt、reason。
- Effect：DENY、ASK、ALLOW。
- Scope：TOOL、MCP_SERVER、COMMAND_PREFIX、PATH_PREFIX。
- `PermissionContext`：projectRoot、toolName、serverName、command、projectRelativePath。
- 规则 matcher 必须在构造/加载时校验；未知版本、未知枚举、非法路径、空规则或危险命令前缀不得进入裁决器。

### 纯函数裁决

- 仅使用显式传入的规则和上下文，不读文件、不访问环境、不调用工具。
- 先按 effect 优先级裁决：DENY > ASK > ALLOW。
- 同 effect 有多个匹配时，按 scope 特异性、matcher 长度、rule id 做稳定排序，返回明确的 matched rule。
- TOOL / MCP_SERVER 精确匹配；PATH_PREFIX 使用项目相对路径和分隔符边界，不能让 `src/a` 匹配 `src/ab`。
- COMMAND_PREFIX 采用保守的简单命令前缀：仅允许安全字符集、token 边界匹配；含 shell 控制操作符、换行、变量展开、命令替换、引号或转义的命令不参与 allow-prefix 匹配。复杂 Shell AST 安全分析留给 ROADMAP 的独立阶段。

### 持久化

- 默认位置：`~/.paicli/security/permissions.json`。
- schemaVersion 固定并验证；限制文件大小、规则数量、重复 id 和字段长度。
- 文件与锁文件拒绝符号链接；POSIX 使用属主私有目录/文件权限，Windows 使用属主 ACL；读改写使用进程锁 + 文件锁。
- 写入同目录私有临时文件，flush 后原子替换；不支持安全原子替换时拒绝保存。
- JSON 损坏或安全检查失败时抛出可诊断错误，不覆盖原文件，不加载任何 ALLOW。

### Stage 3A 验收

- 规则模型、命令前缀匹配、路径边界、项目隔离、优先级和稳定性单元测试。
- Store 往返、损坏文件、重复 ID、符号链接、文件权限、原子保存/并发读改写测试。
- Maven 离线定向回归；测试使用独立临时目录，不读写真实用户 `~/.paicli`。
- 通过后独立提交并推送 GitHub。

## Stage 3B：审批 UI 与执行链集成

1. 新增“仅本次批准 / 本会话允许 / 始终允许 / 拒绝并填写原因 / 跳过 / 修改参数”语义。
2. “始终允许”必须先显示完整的拟保存规则：项目根、scope、matcher、影响范围；二次确认后才写入。
3. 对 `execute_command` 只建议保存安全简单命令前缀；复杂 Shell 不提供 allow-prefix。
4. 对文件写入只提供项目内路径前缀；路径必须经项目根规范化且不能逃逸。
5. 对 MCP 提供精确工具或 server scope；敏感页面逐次审批不接受 allow。
6. `HitlToolRegistry` 统一执行规则裁决；DENY 硬拒绝、ASK 强制人工确认、ALLOW 跳过 HITL 但不跳过硬策略；无匹配时保持现有默认策略。
7. CLI / Plain / Inline / Lanterna / 其他 renderer 的语义一致；提供 `/permissions list|revoke|clear` 管理入口。
8. 审计记录 matched rule id 与 decision，不记录额外敏感参数。

## 明确不做

- 不实现 Shell AST / 通用 Bash 解析器（另列 roadmap）。
- 不允许规则绕过 CommandGuard、PathGuard、沙箱、MCP trust、敏感页面或其他硬拒绝。
- 不把“本会话允许”自动升级为永久规则。
- 不把损坏或不可访问的规则文件当成空文件覆盖。
- 不自动创建全局 `execute_command` / `write_file` 无边界 allow 规则。

## 实施记录

- 2026-11-04：基于现有 HITL / Renderer / MCP trust / OAuth store 代码完成只读架构审查。确认当前 allow-all 仅保存在 handler 内存集合；主 CLI 使用 `RendererHitlHandler`，因此 Plain / Inline / Lanterna UI 都必须覆盖，不能只改 `TerminalHitlHandler`。
- 2026-11-04：Stage 3A 已新增 `PermissionRule`、`PermissionContext`、`CommandPrefixMatcher`、`PermissionRuleEvaluator` 和 `PermissionRuleStore`；增加裁决优先级、项目隔离、命令 token 边界、路径前缀边界、私有权限、文件锁、原子替换、损坏文件拒绝和符号链接拒绝测试。首次定向回归 16 项通过后，复核发现命令匹配器可归一化命令换行，违背保守匹配约束；现已拒绝含控制字符的命令并增加多行命令不匹配断言。隔离禁网副本重跑后，16/16 通过，失败、错误、跳过均为 0；真实 `~/.paicli` 访问受阻，原工作区 HEAD 与 Git 状态保持不变。此结果只覆盖 Stage 3A 定向测试，不代表全量测试通过。
