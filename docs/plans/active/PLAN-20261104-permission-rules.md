# HITL 权限规则持久化

状态：active；Stage 3A、Stage 3B1、Stage 3B2、Stage 3C1、Stage 3C2 均已独立提交并推送 GitHub。Stage 3C2 commit `b760befa38af6cb73c1fbd8bed1f23b1974ff7d2` 已推送；18 个测试类 199/199 隔离离线定向回归通过，BUILD SUCCESS。Stage 3B2 的真实 Lanterna TUI 审批对话框 smoke test 仍待真实终端交互验收；这不是自动化测试通过项。

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
7. 持久化文件损坏、权限不安全或锁失败时，不得应用 allow 规则；Stage 3B1 的执行链选择直接拒绝危险调用并明确提示，而不是冒险自动通过。

## Stage 3A：规则核心与安全存储

### 数据模型

- `PermissionRule`：id、projectRoot、effect、scope、matcher、createdAt、reason。
- Effect：DENY、ASK、ALLOW。
- Scope：TOOL、MCP_SERVER、COMMAND_EXACT、COMMAND_PREFIX、PATH_EXACT、PATH_PREFIX。
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
3. 对 `execute_command` 的 UI 永久授权只建议保存安全简单命令的精确匹配；不自动创建可附加任意参数的命令前缀。复杂 Shell 不提供永久授权候选。
4. 对文件写入只建议保存项目内单文件精确路径；路径必须经项目根规范化且不能逃逸。更宽的 PATH_PREFIX 仅允许在显式规则管理能力扩展后人工管理。
5. 对 MCP 提供精确工具或 server scope；敏感页面逐次审批不接受 allow。
6. `HitlToolRegistry` 统一执行规则裁决；DENY 硬拒绝、ASK 强制人工确认、ALLOW 跳过 HITL 但不跳过硬策略；无匹配时保持现有默认策略。
7. CLI / Plain / Inline / Lanterna / 其他 renderer 的语义一致；提供 `/permissions list|revoke|clear` 管理入口。
8. 审计记录 matched rule id 与 decision，不记录额外敏感参数。

### Stage 3B1 边界（已完成定向验证并推送）

- 新增 `PermissionRuleRuntime`，只从合法参数中提取真实项目路径、工具名、MCP server、简单命令与安全相对路径；PATH_PREFIX 仅用于 `write_file` / `edit_file`。
- `HitlToolRegistry` 新增可选 `setPermissionRuleStore`：启用后 DENY 拒绝、ASK 清理既有会话广泛授权并要求人工确认、有限制的 ALLOW 仅跳过 HITL；父类的参数验证、路径约束、CommandGuard 和沙箱仍运行。规则存储读取失败直接拒绝危险调用并记录审计。
- Stage 3B1 本身未在 Main 启用存储；Stage 3B2 开始接入 Main 的用户级存储，只有逐次审批界面展示并二次确认后才可保存永久规则。
- 对 `execute_command`、`write_file`、`edit_file` 的全工具 ALLOW 禁止直接跳过 HITL；只有简单命令前缀、项目内路径前缀允许自动命中。MCP server / MCP 工具的 DENY 与 ASK 生效，但永久 ALLOW 暂时不跳过审批：必须等 Stage 3B2 补足 MCP 配置指纹绑定及信任状态验证后才能启用。
- `HitlPermissionRuleIntegrationTest` 已在禁网隔离副本通过 10/10，失败、错误、跳过均为 0；此结果仅为 Stage 3B1 定向验证，不代表全量测试通过。Commit `a3386e49f5a4d0d9d1ea8bf613a9035d2675b3d8` 已随 Stage 3B2 的成功 push 上传 GitHub。

### Stage 3B2 完成（156/156 隔离离线定向回归通过）

- 新增 `COMMAND_EXACT` / `PATH_EXACT`：逐次审批时只建议精确命令或单个项目文件路径，避免把一次批准扩展成宽泛前缀授权。
- `ApprovalRequest` 携带拟保存规则；Plain / Inline / Lanterna 均展示项目根、scope、matcher，用户选择 `p` 后还必须再次输入 `YES` 或在 Lanterna 二次确认对话框中确认。
- `HitlToolRegistry` 仅在收到 `PERMANENTLY_ALLOWED` 且候选规则仍属于当前真实项目时写入存储；保存失败时拒绝执行。现有 `a` 仍是会话级授权。
- Main 启动时加载 `~/.paicli/security/permissions.json`，安全检查失败时不启用；增加 `/permissions list`、`/permissions revoke <id>`、`/permissions clear --confirm`，清除操作只影响当前项目。
- MCP 永久 ALLOW 仍关闭；未绑定 MCP 配置指纹和 trust 状态前不能跳过审批。敏感页面逐次确认保持原样。
- 新增 `PermissionCommandTest`、精确 scope evaluator/store 测试、Plain/Inline 永久授权二次确认测试、永久授权执行链集成测试，以及 Lanterna lifecycle 测试。最终一次 Maven 离线禁网隔离定向回归：11 个测试类、156 项通过，失败/错误/跳过均为 0（BUILD SUCCESS）；这不是全量测试。Lanterna 的交互式对话框仍需在真实 TUI 终端做手工 smoke test。
- 为隔离环境避免 Mockito Inline MockMaker 依赖动态代理附加，测试资源显式使用 Subclass MockMaker；Lanterna lifecycle 测试改为注入 `LanternaElicitation`，不再 mock final `LanternaWindow`。`LanternaRenderer.close()` 对注入式测试构造器的空 window 安全处理。
- Stage 3B2 已独立提交为 `85a2866d1fb197be76a713aa88b7432b18ab8287`（`feat: add confirmed project-scoped permanent permissions`）并推送成功；同一次 push 同步上传此前因 Repo Ops 暂时失败而留在本地的 Stage 3B1 commit `a3386e49f5a4d0d9d1ea8bf613a9035d2675b3d8`。后续文档状态修正提交 `0672b49120bf6a39113059da51b3ddb32a2a370f` 也已推送；推送后本地工作区干净。

### Stage 3C：MCP 永久授权生命周期

**现有能力与只读审计发现（2026-11-04）**

- `McpConfigLoader.fingerprint()` 已存在：使用稳定排序后的配置字段，对 protocolVersion、command、args、env、url、headers、oauth 计算 SHA-256；项目级配置在 load 时将该指纹写入 `McpServerConfig.projectFingerprint`。`McpOAuthConfig` 只包含 public clientId/scopes，不包含 client_secret。
- 原有 `McpTrustStore` 按“项目配置绝对路径 + server 名 + fingerprint”判断信任；Stage 3C1 已将其加固为属主私有目录/文件权限、拒绝符号链接、进程锁+文件锁、随机同目录临时文件和原子替换，损坏/非法文件 fail closed，并新增按 config path + server name 撤销信任。
- `McpServerManager.isTrusted()` 仍为内部实现；Stage 3C1 新增只读 `permissionBinding(serverName)` 快照，返回当前 fingerprint、trusted、ready、projectScoped。用户级 `~/.paicli/mcp.json` 仍按用户配置来源默认视为可信。新增 `/mcp untrust <name>` 撤销项目级信任、停止 server 并注销工具。
- 原有 `McpServerManager.restartWithArgs()` 修改运行时 args 后未更新 `projectFingerprint`；Stage 3C1 已改为从不可变 source fingerprint + runtime args 生成稳定新指纹，项目级旧信任立即失效，用户级 permission binding 也同步变化。
- `McpServerManager` 启动时加载配置；普通 restart 使用内存中的配置对象，不会重新读取配置文件。需要明确配置文件运行中被外部修改时，reload/restart 的预期行为，不能把旧快照误认为磁盘当前配置。
- `PermissionContext` 当前只有 projectRoot/toolName/serverName/command/projectRelativePath；`PermissionRuleRuntime` 无法取得 MCP 当前配置指纹或信任状态。`maySkipApproval()` 明确禁止 MCP_SERVER/TOOL ALLOW 跳过 HITL，因此当前 MCP 永久 ALLOW 尚未生效，安全默认仍是逐次审批。
- `McpConfigLoader.fingerprint()` 在变量展开前对原始配置计算指纹，注释明确表示本机环境变量值变化不触发重新确认。需要评估此语义：尤其 command/args/env/url/headers 中 `${VAR}` 的有效值变化是否应使授权失效；不得将原始 env/header 值写入日志或权限文件。当前 SHA-256 指纹本身虽不含明文，但低熵 secret 可能被离线猜测，需评估 keyed digest/本地密钥方案的成本。

### Stage 3C1：MCP trust lifecycle 基础设施（代码完成，定向回归 155/155）

- `McpConfigLoader.load()` 为用户级与项目级配置都生成稳定 source/config fingerprint；项目级仍保留项目配置路径和 trust fingerprint。
- `McpServerConfig.overrideArgs()` 将运行时 args override 绑定到 source fingerprint；相同 override 可复现相同指纹，变化后不再命中旧 project trust。
- 新增 `McpPermissionBinding` 与 `McpServerManager.permissionBinding()`，提供 server 名、配置指纹、信任状态、READY 状态和配置来源快照；当前仅是基础设施，尚未接入权限裁决。
- 新增 `/mcp untrust <name>`：只支持撤销项目级 trust；先在当前会话标记撤销、停止 server、取消自动重启并注销该 server 的工具，再持久化撤销。若 trust 存储写入失败，当前会话仍阻止 restart，并明确提示 CLI 重启前必须修复/移除旧记录，否则旧信任可能恢复。用户级 MCP 配置继续按用户配置来源视为可信，可通过 disable 或移除配置停用。
- `McpTrustStore` 已加固：0700 目录、0600 文件/锁文件、拒绝 symlink、进程锁+文件锁、随机临时文件、fsync+原子替换、最大文件大小、fingerprint 校验；损坏或不安全时按未信任处理，拒绝覆盖损坏文件。
- 最终禁网隔离离线定向回归：13 个测试类、156 项通过，失败/错误/跳过均为 0，BUILD SUCCESS。覆盖 trust store 权限/损坏/symlink/untrust、配置指纹、runtime args 变更、server trust revoke、permission binding、存储撤销失败时的会话级阻断、CLI parser，并回归 Stage 3B 权限规则/UI。未运行需要 MockWebServer 的 `McpServerManagerTest` / `McpServerLifecycleTest`，也不是全量测试。
- 使用此前成功的 `phase0-central-only` Maven settings，保留 Maven 默认 resources plugin 3.5.0；原 `~/.m2`、`_remote.repositories` 和工作区均未被测试任务改动。Stage 3C1 已提交为 `1cf61154a4b48f7be3109ab048591516d3d31638`（`feat: harden MCP trust lifecycle and revocation`）并推送成功；推送后工作区干净。

### Stage 3C2：MCP 永久 ALLOW 裁决集成（目标与验收要求，已完成）

- 为 `PermissionRule` 增加 MCP 配置 binding fingerprint；MCP TOOL / MCP_SERVER 的 ALLOW 必须带 fingerprint。旧的无 binding MCP ALLOW 不得自动迁移为有效授权，按 fail closed 处理。
- 将 `McpPermissionBinding` 注入 `HitlToolRegistry` / `PermissionRuleRuntime`，在每次 MCP 工具调用时取得当前 server fingerprint + trust + ready 状态；无 binding、未 READY、未信任、fingerprint 缺失/变化时 ALLOW 不匹配并退回逐次审批。
- 初始只开放精确 MCP tool ALLOW，server-wide ALLOW 保持关闭，避免配置不变但 server 新增工具时扩大授权面；若以后开放 server-wide scope，必须单独设计明确的宽范围二次确认。
- MCP 永久授权 UI 只在 server READY 且 trusted、fingerprint 可用时提供候选；展示 server、精确 tool、配置指纹短摘要、项目根与影响范围，再做二次确认。敏感页面逐次审批仍不可保存 ALLOW。
- DENY/ASK 保持原有优先级，不依赖 fingerprint 才能生效；配置变化或 trust revoke 后旧 ALLOW 自动失效。审计记录 rule id / decision，不记录环境变量、headers、token 或原始配置内容。
- 覆盖 server 配置变化、runtime args override、trust revoke/re-trust、server 新增工具、fingerprint mismatch、missing binding、not-ready、secret 不泄漏及 DENY/ASK precedence。

**实现边界与验收**

- 先定义统一 MCP permission binding（server 名、来源类型、配置指纹、当前 trust 状态、必要时配置路径）；无 server、配置缺失、fingerprint 缺失、信任状态不可读或 server 已撤销信任时，MCP ALLOW 一律不匹配并退回逐次 HITL。
- MCP `ALLOW` 必须绑定精确 server/tool scope + 当前 fingerprint + trust；配置变化、`restartWithArgs` 修改 args、trust revoke、server rename 或 reload 后 fingerprint 变化时旧 ALLOW 自动失效。DENY/ASK 的优先级与 fail-closed 语义不得被削弱。
- 明确用户级 server 的信任语义：沿用“用户配置来源即用户信任”还是增加显式 revoke；项目级 server 必须沿用已有 `/mcp trust` 授信并增加可撤销能力。不得仅凭 server 名相同复用旧授权。
- 加固 `McpTrustStore`：私有目录/文件权限、拒绝 symlink、进程锁+文件锁、随机同目录临时文件与原子替换、损坏文件 fail closed；新增按 config path + server name 撤销信任的原子操作。
- 覆盖 server command/args/env/url/headers/protocol/OAuth 配置变化、运行时 args override、trust revoke/re-trust、配置 reload、配置缺失/损坏、server rename、用户级/项目级来源、secret 不泄漏、DENY/ASK 优先级等测试。
- Stage 3C2 完成后独立禁网隔离离线定向回归、独立 commit 并推送 GitHub。真实 TUI smoke test 与全量测试另行记录，不把定向测试冒充全量测试。

## 明确不做

- 不实现 Shell AST / 通用 Bash 解析器（另列 roadmap）。
- 不允许规则绕过 CommandGuard、PathGuard、沙箱、MCP trust、敏感页面或其他硬拒绝。
- 不把“本会话允许”自动升级为永久规则。
- 不把损坏或不可访问的规则文件当成空文件覆盖。
- 不自动创建全局 `execute_command` / `write_file` 无边界 allow 规则。

## 实施记录

- 2026-11-04：基于现有 HITL / Renderer / MCP trust / OAuth store 代码完成只读架构审查。确认当前 allow-all 仅保存在 handler 内存集合；主 CLI 使用 `RendererHitlHandler`，因此 Plain / Inline / Lanterna UI 都必须覆盖，不能只改 `TerminalHitlHandler`。
- 2026-11-04：Stage 3A 已新增 `PermissionRule`、`PermissionContext`、`CommandPrefixMatcher`、`PermissionRuleEvaluator` 和 `PermissionRuleStore`；增加裁决优先级、项目隔离、命令 token 边界、路径前缀边界、私有权限、文件锁、原子替换、损坏文件拒绝和符号链接拒绝测试。首次定向回归 16 项通过后，复核发现命令匹配器可归一化命令换行，违背保守匹配约束；现已拒绝含控制字符的命令并增加多行命令不匹配断言。隔离禁网副本重跑后，16/16 通过，失败、错误、跳过均为 0。通过本地 commit da497544 独立提交；2026-11-04 重试后已推送 GitHub 并核实远端 HEAD。此结果只覆盖 Stage 3A 定向测试，不代表全量测试通过。
- 2026-11-04：Stage 3B1 实现可选权限执行链、原路径 guard 和作用域限制，10/10 禁网隔离定向回归通过；commit `a3386e49f5a4d0d9d1ea8bf613a9035d2675b3d8` 本地创建成功，首次推送因 Repo Ops 服务失败未完成。
- 2026-11-04：Stage 3B2 实现精确命令/文件永久授权候选、Plain/Inline/Lanterna 审批范围预览与二次确认、用户级存储启用和项目级 `/permissions` 管理命令。修正测试数据 ID 与 Mockito 隔离兼容问题，并将 Lanterna lifecycle 测试改为不依赖 final-class mock 的注入方式；最终 156/156 隔离离线定向回归通过。此结果不代表全量测试通过；真实 TUI 对话框 smoke test 尚待执行。
- 2026-11-04：Stage 3C1 完成 MCP trust store 加固、用户/项目级配置指纹、runtime args override 指纹更新、permission binding snapshot 和 `/mcp untrust`；最终 13 个测试类 156/156 禁网隔离离线定向回归通过，BUILD SUCCESS。新增验证了 trust 文件损坏时仍先停止 server 并在当前会话阻止 restart。该结果不代表全量测试通过。Commit `1cf61154a4b48f7be3109ab048591516d3d31638` 已推送 GitHub。

### Stage 3C2-A（代码完成，43/43 隔离离线定向回归通过）

- 只完成 MCP 配置一致性基础设施，不接入权限执行链，MCP 永久 ALLOW 继续禁用。
- canonical JSON 包含 protocolVersion、command、args、env、url、headers、OAuth 和 disabled；本地随机 32-byte HMAC-SHA256 密钥保存在私有 `mcp-trust.json`，使用现有 0700/0600、文件锁和原子替换。旧 SHA 信任记录首次迁移时会清空并要求项目重新 `/mcp trust`；损坏文件/非法密钥拒绝覆盖。trust 记录的历史 `sha256` 字段名保留兼容，但新值是 HMAC。
- source fingerprint 绑定原始配置与 runtime args override；effective fingerprint 在恢复 raw source、重新展开环境变量、校验 transport 后计算。重复 `prepare()` 不会丢失 `${VAR}` 占位符；runtime args 和 runtime enable/disable override 均可稳定恢复，原始文件内容仍参与 source fingerprint。
- `permissionBinding()` 每次重新读取当前 mcp.json、重新展开 System.getenv / System properties / 项目 `.env` / 用户 `~/.env`，并比较 source/effective HMAC。配置消失、变化、展开失败、HMAC 不可用或 fingerprint 不一致时返回 `current=false`，不做 SHA 回退、不自动启动/重启、不泄露 secret。binding 检查是读取时快照，检查后立即发生的文件变化需在下一次调用重新发现。
- 本阶段不自动 reload；外部修改 mcp.json 后需重新启动 CLI 载入。 `/mcp restart` 只重启已载入配置并重新展开变量；`restartWithArgs` 只修改当前会话的运行时参数，不重读文件。
- 禁网隔离离线定向回归：`McpConfigLoaderTest` 14、`McpTrustStoreTest` 8、`McpServerManagerTrustTest` 12、`McpProtocolEraTest` 6、`McpOAuthConfigTest` 3，合计 **43/43**，失败/错误/跳过均为 0，BUILD SUCCESS。测试使用全新临时副本、独立 Maven 缓存/HOME、`phase0-central-only` settings 和 `sandbox-exec` 禁网。未运行含 MockWebServer/loopback 的 `McpServerManagerTest`、`McpServerLifecycleTest`、`OAuthManagerTest`；因此这不是全量测试。
- `git diff --check` 通过；测试任务前后工作区状态一致，未在原工作区运行 Maven。C2-B 已接入执行链：每次 MCP 调用将 `current/trusted/ready/fingerprint` 作为 ALLOW 的必要条件。

### Stage 3C2 完成（199/199 隔离离线定向回归通过）

- MCP 永久 ALLOW 仅支持精确 `TOOL` scope，并绑定当前 64 位 HMAC-SHA256 配置指纹；`MCP_SERVER ALLOW` 保持关闭。规则匹配要求当前配置、trusted、READY 和指纹一致；缺失 binding、配置变化、撤销信任、server 未 READY、环境变量展开变化时旧 ALLOW 不匹配。旧版无指纹 MCP TOOL ALLOW 与旧 MCP_SERVER ALLOW 可读取但保持 inert；DENY > ASK > ALLOW 优先级不变。
- `Main` 将 `McpServerManager.permissionBinding()` 接入 `HitlToolRegistry`。每次调用重新读取当前配置并重新展开 System.getenv、System properties、项目 `.env`、用户 `~/.env`，比较 source/effective HMAC；外部修改配置文件不会自动 reload 已运行 server，需重启 CLI 载入新配置。当前绑定检查是调用时快照，检查后立即发生的文件变化在下一次调用重新发现。
- 永久授权候选仅在 server READY、受信任且 fingerprint 可用时出现；UI 显示精确 server/tool、指纹短摘要、项目根和授权范围，并要求二次确认。敏感页面和显式 ASK 使用 `forcePerCall`，不提供会话级/永久放行；Registry 额外拒绝 handler 返回的宽范围放行结果。
- MCP 工具注册身份在审批前捕获；执行期间持有注册读锁，注册替换/撤销在同一写锁内先清理会话授权再变更工具，避免审批期间替换工具或旧授权在撤销后重新出现。审批结果延迟提交给实际展示审批的 handler，切换 Plain/Inline/Lanterna/TUI delegate 不会串单。
- 独立静态安全复审未发现可确认的 P0/P1；识别到一个低风险边界：审批拒绝、跳过或后续校验失败时，`SwitchableHitlHandler` 的每线程 `PendingApproval` 会保留到同线程下次审批或线程退出，每线程最多一条，不构成累积泄漏。
- 最终一次 Maven 禁网隔离离线定向回归：18 个测试类、199 项全部通过，失败/错误/跳过均为 0，BUILD SUCCESS；指定 JDK 21、独立 Maven 缓存/HOME、`phase0-central-only` settings 和 `sandbox-exec` 禁网。未运行需要 MockWebServer/真实网络的 MCP 生命周期测试，也未运行全量测试。
- Commit `b760befa38af6cb73c1fbd8bed1f23b1974ff7d2`（`feat(security): bind MCP permissions to trusted config`）已提交并推送至 `origin/feat/steering-phase2b`；推送后工作区干净。
