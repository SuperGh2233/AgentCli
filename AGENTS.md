# AGENTS.md

AI 编码助手进入本仓库时先读这个文件。它是 Codex、Claude Code、PaiCLI 三个工具共用的唯一一份规则：Codex 直接读取，Claude Code 和 PaiCLI 分别通过 `CLAUDE.md`、`PAI.md` 导入（分工和体积上限见文末“说明文件怎么分工”）。这里只放每次改动都要遵守的规则；实现细节写在 `docs/agents-reference.md`，评测历史写在 `benchmarks/paicli-native-agentbench-v0.1/AGENTS-SNAPSHOT-ARCHIVE.md`。

写文档和回复用户都用大白话：类名、内部叫法、英文术语第一次出现时说明它是什么；不要生造“账本”“合同”“门禁”这类让人看不懂的叫法。

## 信息优先级

代码实际行为 > `AGENTS.md`（`PAI.md`、`CLAUDE.md` 里的工具补充不能和它冲突）> `README.md` > `ROADMAP.md`。`ROADMAP.md` 写的是打算做什么，不代表已经做完。

## 最容易踩的坑

- 评测已暂停：不要主动调用真实模型、跑 Docker 或继续写评测代码，用户明确同意哪一步才做哪一步（见“评测”）
- 测试默认跳过：针对性测试必须带 `-DskipTests=false`，回归要对比改动前后的失败名单；删除、改名或搬走类之后要 `mvn clean test`，否则 `target/` 里残留的旧 class 会让按类名反射的测试假通过
- 直接改 `conversationHistory`（压缩、清理工具结果、恢复会话）之后必须写 `view_checkpoint`，否则 `/resume` 还原出的对话和模型当时看到的不一样
- 工具一律走 `executeTools()`；被安全策略拒绝的操作用户也批准不了，不要为了跑通去放宽 PathGuard、CommandGuard
- 改 Plan 失败处理、重规划或 `parsePlan` 前先看 `src/main/resources/benchmark/e1_replay.py`；`parsePlan` 提取代码块的正则必须和它完全一致，改之前先改它
- 交互输出走 `Renderer.stream()`，主流程不加直接的 `System.out.println`
- 不提交 `.env`、真实 API Key 和 `target/`；不要改掉 `com.paicli`、`~/.paicli`、`PAICLI_*` 这些兼容名字
- 计划文档里写的“已验证 / 未接线”以代码为准

## 项目概况

- 对外名字是 Hao CLI（见 `README.md`），PaiCLI 是旧名，本文件和评测记录里仍会出现。`com.paicli` 包名、`~/.paicli`、`PAICLI_*` 环境变量、`PAI.md`、jar 名都是有意保留的兼容名字，不要顺手改名
- 面向商业使用的 Java Agent 命令行工具，对标 Claude Code。迭代按 `ROADMAP.md` 分 21 期（从 ReAct 单 Agent 到微信接入，各期内容和完成状态看那里）
- 启动画面显示的版本是 `v16.1.0`，Maven 打出的 jar 是 `paicli-1.0-SNAPSHOT.jar`，两者不一致是正常的
- 产品功能：用户项目里的 `PAI.md` 是项目级记忆，启动时放进 system prompt，写团队共享、长期不变的规则；个人的、会变化的事实用 `/save` 存成长期记忆（本仓库自己的 `PAI.md` 只是导入本文件）
- MCP：HTTP server 支持预注册公共客户端的 OAuth 登录（授权码 + PKCE S256，`/mcp login|logout`，对授权服务器的要求见 `docs/specs/mcp-oauth.md`）。双协议默认 2025-03-26，server 显式写 `protocolVersion: 2026-07-28` 才走新版协议，不协商、不降级（`docs/specs/mcp-protocol-eras.md`）。只有新版的 tools/call、resources/read、prompts/get 支持 MRTR（server 中途向用户要补充输入），其他请求一律拒绝；只在当前界面真能显示表单或网址时才声明对应能力（`docs/specs/mcp-mrtr.md`）。不要实现旧的 sampling。各项进度和验收记录在 `docs/plans/active/PLAN-20261103-mcp-*.md`，不写进本文件
- 配套教程和面试文章以 `docs/articles/` 为准，改完用 `tools/sync-articles-to-javabetter.sh` 同步到 javabetter.cn 的副本；面试题对齐升级的取舍和实测数据见 `docs/interview-upgrade-plan.md`

### 评测（Native AgentBench）

- 2026-09-05 起，用户因为 Token 消耗太大暂停了评测：**不要主动继续做评测代码，也不要主动调用真实模型或跑 Docker**。只在用户明确同意某一步时执行那一步，默认不开子代理
- 题目生成器还没接全（进度见 `AGENTS-SNAPSHOT-ARCHIVE.md`），`formalScores=null`、`publishable=false`、`NOT_INTEGRATED` 这些状态不变，不能对外说成正式榜单
- 正式评测只评 DeepSeek V4 Flash 和 GLM-5.3-Flash。缺少证据算评测无效，不算 PaiCLI 得 0 分；模型一次都没被调用，算 PaiCLI 失败。禁止多跑几次挑最好的、禁止只挑部分题、禁止自动重试
- 已知问题：E2 校验器（`validators/final/E2`）的输出格式不统一，`FinalSourceGeneratorTest.referencePrototypes…` 因此失败（详情见 ARCHIVE）；修它需要用户同意
- 评测统计工具调用时，从 `ToolRegistry.onPolicyToolResults` 取数据（包括被策略拒绝的调用，顺序不变），不能只看 `executeTools` 里被放行的那部分
- 细节见 `benchmarks/paicli-native-agentbench-v0.1/` 下的 RUNBOOK、FINAL-DATASET-RUNBOOK 和 AGENTS-SNAPSHOT-ARCHIVE

## 运行与命令

需要 Java 17+ 和 Maven；`ripgrep` 可选（`grep_code` 优先用它，没装就用 Java 自己扫描）。至少配一个 API Key：`DEEPSEEK_API_KEY`、`GLM_API_KEY`、`HUNYUAN_API_KEY`、`STEP_API_KEY`、`KIMI_API_KEY`、`FREELLMAPI_API_KEY`、`XFYUN_MAAS_API_KEY`、`AGNES_API_KEY`。

```bash
cp .env.example .env
mvn clean package                           # 默认跳过测试（pom 中 skipTests=true）
java -jar target/paicli-1.0-SNAPSHOT.jar
java -jar target/paicli-1.0-SNAPSHOT.jar wechat setup|start
java -jar target/paicli-1.0-SNAPSHOT.jar --continue          # 恢复当前项目最近的会话；--session <id> 按 ID 恢复，两者不能同时用
java -Dpaicli.steering.enabled=true -jar target/paicli-1.0-SNAPSHOT.jar   # 打开执行中动态指令（实验功能，默认关）
java -jar target/paicli-1.0-SNAPSHOT.jar -p "任务" --output-format json  # 无界面模式（docs/specs/headless-mode.md）
mvn test -Pquick                            # 常规回归（不含评测包和几个慢测试）
mvn test -Pbenchmark                        # 只跑评测包（eval.benchmark）测试
mvn test -Pphase16-smoke                    # TUI 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性（必须带 -DskipTests=false）
mvn test -DskipTests=false                  # 全量
```

交互命令：`/plan`、`/team`、`/mode auto|plan|ask`（Shift+Tab 轮流切换）、`/hitl on|default`、`/permissions list|revoke <id>|clear --confirm`、`/save`、`/memory`、`/compact`、`/context`、`/init`、`/export`、`/resume`、`/better-harness`、`/wechat`。不认识的 `/xxx` 由命令行直接报“未知命令”，不交给模型处理。

`mvn test -Pquick` 之前登记的已知失败已在 2026-11-24 全部修复，现在出现失败就是新问题。终端相关测试用 JLine 建测试终端时要显式 `.type("dumb")`，否则会跟着环境变量 `TERM` 变，换台机器或在 CI 上结果就不一样。评测包测试已从 quick 移到 `-Pbenchmark`（91 个已知失败）；`CodeIndexTest` 连不上本机 Ollama 时跳过；`ToolRegistryTest.macSandboxAllowsDevelopmentRuntimesAndDevNull` 在较新的 macOS 上，JVM 在 Seatbelt 沙箱里会以 139 退出。判断有没有引入新问题，要对比改动前后失败的测试名单，不要只看失败总数。

### 验证命令

| 场景 | 命令（都要带 `-DskipTests=false`） |
|---|---|
| 工具 / 代码搜索 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| Plan / DAG | `mvn test -Dtest=ExecutionPlanTest,PlannerGraphValidationTest,PlanExecuteAgentTest` |
| Memory | `mvn test -Dtest='com.paicli.memory.*Test'` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| TUI | `mvn test -Pphase16-smoke` |
| HITL / 权限规则 | `mvn test -Dtest='com.paicli.hitl.*Test,PermissionCommandTest,SessionModeControllerTest'` |
| 安全策略（CI 在 quick 之外跑的就是这条，见 `.github/workflows/ci.yml`） | `mvn test -Dtest='ToolRegistryTest,PathGuardTest,CommandGuardTest,AuditLogTest'` |
| MCP（含 quick 排除的慢测试） | `mvn test -Dtest='com/paicli/mcp/**/*Test,McpOAuthCommandTest'` |
| Steering | `mvn test -Dtest='*Steering*Test,TurnInputManagerTest,InputCoordinatorTest,TerminalInputGateway*Test,JLineInputAdapterTest,InlineApprovalGatewayTest,AgentBudgetFinalizationTest,ToolRegistryToolStartGateTest'` |

## 架构

三种执行方式共用 ToolRegistry、MemoryManager、SnapshotService、ConversationLedger：ReAct（`Agent.java`，循环在内核 `AgentLoop`，默认）、Plan-and-Execute（`PlanExecuteAgent.java`，`/plan`）、Multi-Agent（`AgentOrchestrator.java`，`/team`）。

代码都在 `src/main/java/com/paicli/` 下按功能分包（`agent/`、`tool/`、`hitl/`、`policy/`、`llm/`、`mcp/`、`render/`、`runtime/`、`eval/` 等），各模块从哪个类读起见“从哪里开始读”。

- 内置工具 18 个：`read_file`、`write_file`、`edit_file`、`list_dir`、`glob_files`、`grep_code`、`execute_command`、`create_project`、`search_code`、`web_search`、`web_fetch`、`browser_connect`、`browser_disconnect`、`browser_status`、`save_memory`、`load_skill`、`save_skill_reference`、`revert_turn`。MCP 工具运行时注册，名字是 `mcp__{server}__{tool}`
- `edit_file` 默认只替换唯一一处匹配的 `old_text`，`replace_all=true` 时全部替换，新建文件用 `write_file`；精确匹配失败后的放宽规则（`TextEditMatcher`）见 `docs/agents-reference.md`
- 读代码默认顺序：`glob_files` → `grep_code` → `read_file`；`search_code`（RAG）只用来做模糊的语义查找
- MCP 配置合并 `~/.paicli/mcp.json` 和项目里的 `.paicli/mcp.json`；检测到 `STEP_API_KEY` 时自动加上 `step_search`。项目级 server（包括覆盖用户级的同名 server）要 `/mcp trust` 确认后才启动，确认绑定配置内容指纹；stdio 子进程只继承 `McpChildEnvironment` 白名单里的环境变量，不要改回继承全部
- stdio server 退出或卡死时自动重启，HTTP server 不重连（重启策略见 `docs/agents-reference.md` 的 MCP 协议一节）；提示攒在 `McpServerManager.drainNotices()`，等下一个输入框前打印，不在后台线程直接输出
- 思考模型（DeepSeek Flash / V4 Pro、GLM-5.3、混元 Hy4、Kimi thinking）返回的思考内容 `reasoning_content` 必须在下一轮请求里带回去；各家接口差异和重试策略见 `docs/agents-reference.md`
- 手动管理模型（`/model list|refresh|add`）：刷新只新增不删除，添加后不自动切换，不靠实际调用模型探测能力；细节见 `docs/agents-reference.md` 的“手动模型目录与能力覆盖”
- DeepSeek 是默认供应商（`PaiCliConfig.defaultProvider`，多个 Key 都有时优先用它），默认模型 `deepseek-flash`（V4.1 Flash，支持图片）。旧名 `deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` 只作兼容映射，不加成单独的内置模型、刷新时也不新增；以前评测记录里的模型标识不跟着改（细节见 `docs/agents-reference.md` 的 LLM 客户端）
- 模型、MCP、Skill 列表统一用 `TerminalTable` 显示，不截断模型 ID，列表只放用来比较的字段（窄窗口的换行规则见 `docs/agents-reference.md` 的 TUI 一节）
- 启动时用哪个模型：环境变量 → 项目 `.env` → 用户 `.env` → 上次保存的模型 → 默认值（`getStartupModel`）；会话里用 `/model` 主动选的优先。显示名只用在启动画面和状态栏，不能影响 API 请求、工具策略和评测记录里的模型 ID
- inline 界面的显示约定见 `docs/agents-reference.md`
- 执行中动态指令（Steering，Agent 运行时用户接着输入，用来引导当前任务或排到下一轮）：实验功能，默认关。只有 JVM 参数 `-Dpaicli.steering.enabled=true`（不是环境变量）并且走交互式 inline ReAct 时启用；Plain、Lanterna、非交互、Plan、Team 仍走原输入路径，排队的消息目前只保存并显示数量，不会自动开始下一轮。入口 `cli/SteeringRunLoop`、`InputCoordinator`、`WakeableLineReader`、`TerminalInputGateway`、`runtime/input/TurnInputManager`。只有 UI 线程读终端，Agent 工作线程要输入（比如审批）时通过 `TerminalInputGateway` 请求；输入线程不能直接改 `conversationHistory`。设计和进度见 `docs/mid-turn-steering-implementation-plan.md`，里面的状态以代码为准

## 关键行为约束

### Memory 与上下文

- 短期上下文就是各个 Agent 实际发给模型的 `conversationHistory`，不要再额外维护一份消息副本
- 会话记录（`~/.paicli/history/raw/`，由 `ConversationLedger` 写入）只追加、不改写；`/resume` 靠它还原 ReAct 对话。所以任何直接改动 `conversationHistory` 的操作（压缩、清理旧工具结果、恢复会话）之后，都要写一条 `view_checkpoint`，否则恢复出来的对话和模型当时看到的对不上
- 上下文压缩按代价从小到大分三步：单个工具结果太大就写入文件，对话里只留说明 → 清理旧工具结果（只替换正文，保留 `tool_call_id`）→ 生成摘要（会话记忆摘要是实验功能，默认关）。摘要栏目不全或压缩后 Token 没减少，就保留原来的历史；各步的触发线和公式见 `docs/agents-reference.md` 的 Memory 系统
- 长期记忆默认只对当前项目生效（project），跨项目都适用的偏好才存成 global；只存长期不变的事实。用户明确要求保存时，先去重，再检查冲突（数字、版本号不同，或 Dice 相似度 ≥ 0.8 时不写入，由用户选 replace 或 force），模型不能替用户选；超过 30 天没核实的记忆标“可能已过时”
- 长期记忆文件会被多个 PaiCLI 进程同时读写：修改必须通过 `withStorageLock`（加锁后重新读取 → 修改 → 整体写回），不要绕过它直接写文件
- 自动提取记忆只能从用户原话里逐字摘取，最多 3 条、只存为 project 级、标“待核实”，重复或冲突的一律跳过；会话里读过网页、浏览器、MCP、curl 等外部内容后，暂停自动写入。`save_memory` 只在用户本轮明确要求“记住”时调用
- system prompt 里的记忆规则：记忆只是线索，不一定还对，行动前要对照当前文件核实
- Agent 循环不设固定轮数上限。同一个动作或同一类错误连续出现 3 步时，`RunawayGuard` 往对话里加一次 `[runaway guard]` 提醒（只提醒，不拦工具）；停滞检测默认看最近 5 步，这个数必须大于提醒的 3 步。超出预算或判定停滞后，关掉工具，让模型把已完成的部分整理成结果，不丢弃已有工作

### 工具执行

- 三种执行方式都通过 `executeTools()` 执行工具，不要自己写循环挨个调用
- 模型回复因输出长度上限被截断（停止原因 MAX_TOKENS）时，ReAct 内核不执行其中的工具调用（参数可能是半截 JSON），每个调用回一条“未执行、请缩短参数重试”的结果；截断的最终回复末尾加提示。Plan / Team 迁移到内核前还没有这层保护
- 只有 `ToolRegistry.PARALLEL_SAFE_TOOLS` 里的只读工具可以并行（最多同时 4 个）；写文件、执行命令、MCP、`save_memory`、`revert_turn` 和不认识的工具都按原顺序一个一个执行。新增只读工具要加进这个名单。一批调用里只要有浏览器工具，整批都一个一个执行。结果按原来的顺序返回
- 工具执行前按 JSON Schema 校验参数（`ToolArgumentValidator`），不合格就把字段级错误还给模型。内置工具只拦结构问题（缺参数、传了对象 / 数组、不在 enum 里），单值参数的类型和 null 仍由工具自己解析，评测复算依赖这套语义；MCP 工具按 server 的原始 Schema 严格校验，不能用清洗后给模型看的那份
- 工具结果放进对话前，统一用 `ToolResultBoundary.wrap()` 包一层 `trust="untrusted-data"` 标记，表示这是外部数据，不代表允许做任何事；太大的结果由 `ToolResultOffloader` 写进 `.paicli/tool-outputs/`

### HITL（人工确认）与安全策略

- 检查顺序：TurnToolPolicy（网址来源等本轮工具策略）→ HitlToolRegistry（先查参数，不合格直接返回校验错误，不弹框也不交给审查模型；再看项目权限规则；规则没给出结论时才按会话模式决定要不要确认）→ ToolRegistry → PathGuard / CommandGuard / 命令沙箱。被安全策略拒绝的操作，用户也无法批准
- 项目权限规则（`hitl/PermissionRule*`，存在 `~/.paicli/security/permissions.json`，每条绑定项目真实根路径）：优先级 DENY > ASK > ALLOW，都在会话模式之前生效。DENY 直接返回 `[PERMISSION]` 失败；ASK 强制弹确认框，没有确认通道就拒绝；ALLOW 只省掉确认和 auto 模式的拦截，PathGuard、CommandGuard、沙箱照常检查，敏感页面仍逐次确认。运行中读规则失败就拒绝执行；启动时规则文件安全检查不过，就整体不启用规则并提示
- 永久允许只能在确认框里选“始终允许”（`p`）并二次确认后保存，只存精确命令、单个项目内文件或精确 MCP 工具（MCP 还要绑定当前配置指纹、已信任、已就绪，配置一变就失效）；不存整个工具的放行，`a` 仍只管本会话
- 会话模式（`SessionMode`、`SessionModeController`）：Shift+Tab 按 auto → plan → ask 轮流切换，`/mode` 效果相同。交互式命令行没有“全部放行”，最宽松的就是 auto。模式只是“确认严格程度 + 是否走 plan”的组合，不另外保存权限状态。plan 模式下普通输入走 Plan-and-Execute，用户明确输入的 `/team` 优先；启动时默认 auto。目前只在 inline 命令行界面生效，Lanterna 全屏界面还没接
- auto 模式（`/hitl default`，启动默认）：
  - 读写文件直接执行
  - `execute_command` 先交给 `LlmApprovalClassifier` 审查：用当前供应商发一个关闭思考的小请求，可用 `PAICLI_AUTO_CLASSIFIER_MODEL` 换模型。通过就执行，并写一条 `auto-classifier` 审计记录
  - 没通过、审查出错（超时、异常、返回的不是约定格式的 JSON、参数超过 32000 字符、没有可用的审查模型），以及 `revert_turn`、所有 `mcp__*` 工具、改动敏感页面，都直接以 `[AUTO]` 开头的失败结果还给模型，不弹确认框
  - 同一轮里连续被拦 3 次（`AUTO_ESCALATE_AFTER`）才转成人工确认；有一次通过或用户输入新一轮（`startAutoReviewTurn`）就重新计数
  - 审查只看用户本轮原话、工具名和参数，不看工具结果；审查出错时绝不放行；“通过”的结论只在同一个请求里复用；审查之后 PathGuard、CommandGuard 照常检查
- ask 模式（`/hitl on`）：额外要求确认 `write_file`、`edit_file`、`create_project`，不经过审查模型
- 默认的人工确认只由 `SwitchableHitlHandler` 打开；评测这类无人值守的处理器保持关闭，不能让没人看着的流程卡在等待确认上
- PathGuard 强制所有路径都在项目根目录内；CommandGuard 只是辅助的命令黑名单，不是主要的安全措施。命令沙箱由 `PAICLI_COMMAND_SANDBOX=off|auto|required` 控制，默认 off
- 无界面模式（`-p`）、Runtime API、后台任务都没人确认，统一用 `headless/` 的白名单：默认只读，edit 才能写文件，命令只放行逐字列出的（后台任务不执行命令）；不要再给无人值守入口用裸 `ToolRegistry`。`-p` 的标准输出只放 Agent 事件和结果
- 微信通道没有人工确认界面，默认拒绝：只读工具允许；`execute_command`、`mcp__*` 必须在白名单里；`revert_turn` 和切换浏览器会话一律拒绝

### Web 与浏览器

- 搜索服务：设置了 `SEARCH_PROVIDER` 就用它，否则按已配置的 Key 自动选，已有配置的优先级不变（顺序见 `docs/agents-reference.md`）。DeepSeek 搜索单独发一个 Anthropic Messages 格式的请求，链接只从返回的结构化搜索结果里取，缺少搜索结果或工具报错就直接失败，不从模型写的正文里找链接
- `web_fetch` 和浏览器能打开的网址，只能来自用户本轮亲手输入的原文，或者本次执行分支里 `web_search` 返回的结构化结果 `discoveredUrls`。工具返回的正文、模型的输出都不能让新网址变成可访问；被拒绝的调用不能换个工具绕过去
- Plan 的并行任务和 Team 的每个 worker 各用一份独立的策略副本；只有在 DAG 里声明了依赖，才能用上游任务 `web_search` 找到的网址
- 根据用户说的话做判断，只能用来少给工具，不能用来多给工具。工具默认全部开放，不要再用“动作词表”决定给不给模型工具（漏判会让正常任务因为缺工具悄悄失败）。只有输入明显像一个标题（`《…》`、`# `、“（附…面试题）”结尾、“X：Y？”），并且前面没有“帮我…”这类请求、也没有网址时，才关掉联网工具并提示用户；没有这些标记的纯标题，由提示词要求模型先问清楚。问“当前项目/文件”的问题优先用本地工具
- 最终回复里如果残留着没被执行的工具调用文本（比如 DeepSeek 的 DSML 格式），由 `LlmClient.looksLikeUnexecutedToolCall` 识别，并提示用户“工具调用未执行”；三种执行方式都要提示
- 已知网址先用 `web_fetch`；单页应用或有反爬时再用 Chrome DevTools MCP，读取页面优先 `take_snapshot`；公开页面不要提前切到 shared 模式（复用用户自己的浏览器）

### Plan 与 Team

- 计划审阅时的按键：`Enter` 执行、`Ctrl+O` 展开、`ESC` 取消、`I` 补充要求后在当前计划上修改（`Planner.revisePlan`）；方向键不能被误认成 ESC。改这块要在 raw mode 和退回的普通行输入模式下都验证一遍
- 所有 `/plan` 目标都交给模型规划，不要在调模型之前用关键词判断“这是简单任务”而跳过规划（关键词会把危险操作或多步任务误判成一步）；计划尽量短，靠规划提示词来约束
- Planner 生成的计划要严格校验：tasks 不能为空，id 必须是唯一、非空的字符串，依赖只能引用已声明的 id，不能有环。不合格就直接失败，不要悄悄删掉依赖或猜测 id。`parsePlan` 里提取 ``` 代码块的正则必须和 `e1_replay.py` 完全一致，改之前先改 `e1_replay.py`
- 用户取消导致中断的任务不算失败，不触发重新规划。任务失败且完成度低于 50% 时重新规划，最多 `PAICLI_PLAN_MAX_REPLANS` 次（默认 2）；到达上限后立刻不再启动新任务，剩下的标 SKIPPED（和 `e1_replay` 的“触发后不再有新工作”一致）。完成度不低于 50% 时，失败任务下游所有依赖它的任务标 SKIPPED。汇总时先列已完成的结果，再列失败和跳过的原因
- Team 的规划结果由 `TeamPlanParser` 按同一套规则校验；Reviewer 只认 JSON 里的布尔值 `approved: true`，不要再加“看到某些关键词就算通过”的退路
- planner 和 reviewer 的请求不提供工具，这是评测审计的要求；想引入 `submit_*` 工具或 `response_format` 之前，先改评测的审计约定

### 输出与 Skill

- 共用的 `prompts/handoff.md` 要求模型遵守用户指定的严格输出格式（只要 JSON、CSV 或一个值时，不加代码块围栏、不加总结），但这只是提示词要求，模型不一定每次都照做
- Skill 分三部分：
  - 索引（SKILL.md 开头 frontmatter 里的 name 和 description）一直放在 system prompt 里，最多 20 个、4KB
  - SKILL.md 正文（frontmatter 之后的内容）在 `load_skill(name)` 成功后，同一轮里紧跟在工具结果后面，作为一条单独的 user 消息加进对话（`LoadedSkillMessages`），不要改回等用户下一条消息再加
  - 附属文件用 `load_skill(name, file)` 读取，像普通工具结果一样返回
- Skill 正文消息第一行带 `[PAICLI_SKILL_INJECTION]` 标记：
  - 压缩时不算对话轮次、不交给摘要；落在被摘要范围里的直接删掉，摘要末尾列出这些 Skill 的名字，模型需要时重新 `load_skill`（不要改回“原样放回去”，也不要在提示词里禁止重复加载）
  - 某个 Skill 的正文还在对话里时，重复加载不再追加。去重和 16KB 上限（对话里所有 Skill 正文的总字符数）都直接从对话历史计算，不另外存状态
  - `references/` 目录会合并用户自己的补充目录 `~/.paicli/skills/<name>/references/`；经验写回只能用 `save_skill_reference`（只写补充目录里的 `references/**.md`）
  - 不放宽 PathGuard，不让 SKILL.md 指示模型用 `read_file` 或 `execute_command` 去读 `~/.paicli/`；项目自带的 Skill 没用 `/skill trust` 信任之前，不生成正文消息，SKILL.md 内容只作为不可信的工具结果返回
- system prompt 由 `PromptAssembler` 组装（内置提示词在 `src/main/resources/prompts/`，可被 `~/.paicli/prompts/`、`.paicli/prompts/` 覆盖），按内容变化频率从低到高排列：固定内容 → Skills → 运行时信息 → 项目上下文。新增段落按它的变化频率插到对应位置，不要放到随每次提问变化的记忆前面

## 修改时要一起改的地方

| 改动 | 需要一起改 |
|---|---|
| 行为 | `AGENTS.md`（规则变了时）、`README.md`、`ROADMAP.md`（只在完成状态变化时）、受影响的 `docs/articles/` |
| 命令入口 | `Main.java` + `CliCommandParser.java` + 测试 + `README.md` |
| Plan 审阅交互 | `Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证 |
| 工具集 | `ToolRegistry.java` + 三种执行方式和 Planner 的提示词 + 文档 |
| 模型 / 接口 | 对应的 Client + `LlmClientFactory.java` + `.env.example` + 文档 |
| Embedding | `EmbeddingClient` + `VectorStore` + `.env.example` + 文档 |
| Web / 搜索 | `web/` + ToolRegistry + `.env.example` + 文档 + 测试 |
| Memory | `MemoryManager` + `LongTermMemory` + `TokenBudget` + 测试 + 文档 |
| HITL / 安全策略 | `policy/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试 |
| MCP | `mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试 |
| 分阶段功能完成一步 | 对应计划（`docs/plans/active/`、`docs/*-implementation-plan.md`）开头的状态行；测试数字等进度只写在计划里 |

代码保持好读，不过度抽象。

## 从哪里开始读

先看本文件 → `README.md` → `Main.java`，再按任务进入对应模块：命令行命令看 `Main` + `CliCommandParser`；规划看 `PlanExecuteAgent` + `Planner` + `ExecutionPlan`；工具看 `ToolRegistry` + `AgentLoop`；模型看 `llm/*Client` + `LlmClientFactory`；Multi-Agent 看 `AgentOrchestrator` + `SubAgent`；MCP 看 `McpServerManager` + `McpClient`；界面输出看 `render/Renderer` + `RendererFactory`；评测看 `eval/benchmark/BenchmarkCoordinatorMain` + `BenchmarkWorkerMain`。

做分阶段的功能前，先找对应的计划和规格：`docs/plans/active/PLAN-*.md`（进行中的计划）、`docs/specs/`（协议和行为规格）、`docs/*-implementation-plan.md`（比如执行中动态指令）。

## 还没做到的

- 还没有：通用的容器或虚拟机沙箱；MCP 的 confidential client secrets、DCR/CIMD、step-up scope、device-code 和 sampling；HTTP 类型 MCP server 的断线重连（自动重启只管 stdio 子进程）。评测用的 `DOCKER_RELAY` 只是让评测彼此隔离，不改变交互式 PaiCLI 的安全措施
- 命令沙箱默认 off：沙箱里没有网络，也看不到 `~/.m2`、`~/.gitconfig`，所以 `mvn`、`npm install`、`git push` 会失败；在 Seatbelt 沙箱里 `java -version` 仍会以 139 退出
## 说明文件怎么分工

- `AGENTS.md`（本文件）：三个工具共用的唯一规则来源。共享规则只改这里，形成稳定的协作规则时补进来
- `CLAUDE.md`、`PAI.md`：开头用一行 `@AGENTS.md` 导入本文件（Claude Code 和 PaiCLI 都支持这种写法），后面只写各自工具专属的补充，各保持十行左右，不复制本文件的内容
- 体积上限：Codex 默认只读项目说明的前 32 KiB（`project_doc_max_bytes`，按字节算，一个汉字 3 字节）；PaiCLI 的 `PAI.md` 连同导入内容最多 24,000 字符（`ProjectMemoryLoader.MAX_TOTAL_CHARS`，用户级 `~/.paicli/PAI.md` 先占预算），超出部分直接截掉。所以本文件保持在 30,000 字节、19,000 字符以内（用 `wc -c`、`wc -m` 检查），要紧的规则放前面；超了就把实现细节挪进 `docs/agents-reference.md`
- 进度和验收记录写进对应计划，评测进展写进 benchmark 目录下的 RUNBOOK 或 ARCHIVE，都不写进这三份文件；只给自己看的说明放 `PAI.local.md`（PaiCLI 会读，已被 git 忽略）
