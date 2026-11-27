# AGENTS.md

AI 编码助手进入本仓库时先读这个文件。它是 Codex、Claude Code、Hao CLI 三个工具共用的唯一一份规则：Codex 直接读取，Claude Code 和 Hao CLI 分别通过 `CLAUDE.md`、`HAO.md` 导入（分工和体积上限见文末“说明文件怎么分工”）。这里只放每次改动都要遵守的规则；实现细节写在 `docs/agents-reference.md`，评测历史写在 `benchmarks/haocli-native-agentbench-v0.1/AGENTS-SNAPSHOT-ARCHIVE.md`。

写文档和回复用户都用大白话：类名、内部叫法、英文术语第一次出现时说明它是什么；不要生造“账本”“合同”“门禁”这类让人看不懂的叫法。

## 信息优先级

代码实际行为 > `AGENTS.md`（`HAO.md`、`CLAUDE.md` 里的工具补充不能和它冲突）> `README.md` > `ROADMAP.md`。`ROADMAP.md` 写的是打算做什么，不代表已经做完。

## 最容易踩的坑

- 评测已暂停：不要主动调用真实模型、跑 Docker 或继续写评测代码，用户明确同意哪一步才做哪一步（见“评测”）
- 测试默认跳过：针对性测试必须带 `-DskipTests=false`，回归要对比改动前后的失败名单；删除、改名或搬走类之后要 `mvn clean test`，否则 `target/` 里残留的旧 class 会让按类名反射的测试假通过
- 直接改 `conversationHistory`（压缩、清理工具结果、恢复会话）之后必须写 `view_checkpoint`，否则 `/resume` 还原出的对话和模型当时看到的不一样
- 三种执行方式的工具一律走 `executeTools()`，不要自己写循环挨个调用；被安全策略拒绝的操作用户也批准不了，不要为了跑通去放宽 PathGuard、CommandGuard
- 改 Plan 失败处理、重规划或 `parsePlan` 前先看 `src/main/resources/benchmark/e1_replay.py`；`parsePlan` 提取代码块的正则必须和它完全一致，改之前先改它
- 交互输出走 `Renderer.stream()`，主流程不加直接的 `System.out.println`；ReAct 内核 `AgentLoop` 只发事件，终端显示改 `TerminalEventRenderer`；上下文压缩、诊断注入、提醒这类机制实现 `TurnHook` 挂上去，并在 `HarnessFeatures` 加开关，不写进循环
- 不提交 `.env`、真实 API Key 和 `target/`
- 计划文档里写的“已验证 / 未接线”以代码为准

## 项目概况

- 名字统一是 Hao CLI：包名 `com.haocli`、配置目录 `~/.haocli`、环境变量 `HAOCLI_*`、系统属性 `haocli.*`、项目记忆 `HAO.md`、jar `haocli-*.jar`（2026-11-27 全量改名，不兼容旧名字）。`docs/reviews/` 和 `docs/UPSTREAM_ATTRIBUTION.md` 里的旧名是历史记录和对上游项目的致谢，不要改
- 面向商业使用的 Java Agent 命令行工具，对标 Claude Code。迭代按 `ROADMAP.md` 分 21 期（从 ReAct 单 Agent 到微信接入，各期内容和完成状态看那里）
- 启动画面显示的版本是 `v16.1.0`，Maven 打出的 jar 是 `haocli-1.0-SNAPSHOT.jar`，两者不一致是正常的
- 产品功能：用户项目里的 `HAO.md` 是项目级记忆，启动时放进 system prompt，写团队共享、长期不变的规则；个人的、会变化的事实用 `/save` 存成长期记忆（本仓库自己的 `HAO.md` 只是导入本文件）
- MCP：双协议（默认 2025-03-26，显式写 `protocolVersion: 2026-07-28` 才走新版，不协商、不降级）、OAuth 登录、MRTR（server 中途向用户要补充输入）的约束见 `docs/specs/mcp-*.md` 和 `docs/agents-reference.md`；不要实现旧的 sampling。进度写在 `docs/plans/active/PLAN-20261103-mcp-*.md`
- 配套教程和面试文章以 `docs/articles/` 为准，改完用 `tools/sync-articles-to-javabetter.sh` 同步副本（取舍见 `docs/interview-upgrade-plan.md`）

### 评测（Native AgentBench）

- 2026-09-05 起因 Token 消耗太大暂停：只在用户明确同意某一步时执行那一步，默认不开子代理
- 题目生成器还没接全，`formalScores=null`、`publishable=false`、`NOT_INTEGRATED` 不变，不能对外说成正式榜单；禁止多跑几次挑最好的、只挑部分题、自动重试
- 统计工具调用从 `ToolRegistry.onPolicyToolResults` 取（含被策略拒绝的调用），不能只看 `executeTools` 放行的部分
- 评分规则、已知问题和流程见 `docs/agents-reference.md` 的“评测规则”和 `benchmarks/haocli-native-agentbench-v0.1/` 下的 RUNBOOK、FINAL-DATASET-RUNBOOK、AGENTS-SNAPSHOT-ARCHIVE

## 运行与命令

需要 Java 17+ 和 Maven；`ripgrep` 可选（`grep_code` 优先用它，没装就用 Java 自己扫描）。至少配一个 API Key：`DEEPSEEK_API_KEY`、`GLM_API_KEY`、`HUNYUAN_API_KEY`、`STEP_API_KEY`、`KIMI_API_KEY`、`FREELLMAPI_API_KEY`、`XFYUN_MAAS_API_KEY`、`AGNES_API_KEY`。

```bash
cp .env.example .env
mvn clean package                           # 默认跳过测试（pom 中 skipTests=true）
java -jar target/haocli-1.0-SNAPSHOT.jar
java -jar target/haocli-1.0-SNAPSHOT.jar wechat setup|start
java -jar target/haocli-1.0-SNAPSHOT.jar --continue          # 恢复当前项目最近的会话；--session <id> 按 ID 恢复，两者不能同时用
java -Dhaocli.steering.enabled=true -jar target/haocli-1.0-SNAPSHOT.jar   # 打开执行中动态指令（实验功能，默认关）
java -jar target/haocli-1.0-SNAPSHOT.jar -p "任务" --output-format json  # 无界面模式（docs/specs/headless-mode.md）
mvn test -Pquick                            # 常规回归（不含评测包和几个慢测试）
mvn test -Pbenchmark                        # 只跑评测包（eval.benchmark）测试
mvn test -Pphase16-smoke                    # TUI 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性（必须带 -DskipTests=false）
mvn test -DskipTests=false                  # 全量
tools/test-failures.sh > /tmp/before.txt    # 列出上次测试的失败名单；改完再输出到 after.txt，diff 多出来的就是新失败
```

交互命令：`/plan`、`/team`、`/mode auto|plan|ask`（Shift+Tab 轮流切换）、`/hitl on|default`、`/permissions list|revoke <id>|clear --confirm`、`/save`、`/memory`、`/compact`、`/context`、`/init`、`/export`、`/resume`、`/better-harness`、`/wechat`。不认识的 `/xxx` 由命令行直接报“未知命令”，不交给模型处理。

`mvn test -Pquick` 现在没有已知失败（2026-11-24 全部修复），出现失败就是新问题。终端相关测试用 JLine 建测试终端时要显式 `.type("dumb")`，否则结果跟着 `TERM` 变。评测包测试在 `-Pbenchmark`（2026-11-28 在 `38bdb3b` 实测 185 个已知失败：176 个断言失败 + 9 个错误；回归要比对改动前后的失败名单，不能只比数字）；`CodeIndexTest` 连不上本机 Ollama 时跳过；`ToolRegistryTest.macSandboxAllowsDevelopmentRuntimesAndDevNull` 在较新的 macOS 上会因 JVM 在 Seatbelt 沙箱里以 139 退出而失败。

### 验证命令

| 场景 | 命令（都要带 `-DskipTests=false`） |
|---|---|
| 工具 / 代码搜索 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| Plan / DAG | `mvn test -Dtest=ExecutionPlanTest,PlannerGraphValidationTest,PlanExecuteAgentTest` |
| Memory | `mvn test -Dtest='com.haocli.memory.*Test'` |
| Multi-Agent | `mvn test -Dtest=AgentRoleTest,AgentMessageTest,AgentOrchestratorTest` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| TUI | `mvn test -Pphase16-smoke` |
| HITL / 权限规则 | `mvn test -Dtest='com.haocli.hitl.*Test,PermissionCommandTest,SessionModeControllerTest'` |
| 安全策略（CI 在 quick 之外跑的就是这条，见 `.github/workflows/ci.yml`） | `mvn test -Dtest='ToolRegistryTest,PathGuardTest,CommandGuardTest,AuditLogTest'` |
| MCP（含 quick 排除的慢测试） | `mvn test -Dtest='com/haocli/mcp/**/*Test,McpOAuthCommandTest'` |
| Steering | `mvn test -Dtest='*Steering*Test,TurnInputManagerTest,InputCoordinatorTest,TerminalInputGateway*Test,JLineInputAdapterTest,InlineApprovalGatewayTest,AgentBudgetFinalizationTest,ToolRegistryToolStartGateTest'` |

## 架构

三种执行方式共用 ToolRegistry、MemoryManager、SnapshotService、ConversationLedger：ReAct（`Agent.java`，循环在内核 `AgentLoop`，默认）、Plan-and-Execute（`PlanExecuteAgent.java`，`/plan`）、Multi-Agent（`AgentOrchestrator.java`，`/team`）。

代码都在 `src/main/java/com/haocli/` 下按功能分包（`agent/`、`tool/`、`hitl/`、`policy/`、`llm/`、`mcp/`、`render/`、`runtime/`、`eval/` 等），各模块从哪个类读起见“从哪里开始读”。

- 内置工具 18 个：`read_file`、`write_file`、`edit_file`、`list_dir`、`glob_files`、`grep_code`、`execute_command`、`create_project`、`search_code`、`web_search`、`web_fetch`、`browser_connect`、`browser_disconnect`、`browser_status`、`save_memory`、`load_skill`、`save_skill_reference`、`revert_turn`。MCP 工具运行时注册，名字是 `mcp__{server}__{tool}`
- `edit_file` 默认只替换唯一一处匹配的 `old_text`，`replace_all=true` 时全部替换，新建文件用 `write_file`
- 读代码默认顺序：`glob_files` → `grep_code` → `read_file`；`search_code`（RAG）只用来做模糊的语义查找
- MCP 配置合并 `~/.haocli/mcp.json` 和项目 `.haocli/mcp.json`。项目级 server（包括覆盖用户级的同名 server）要 `/mcp trust` 确认后才启动，确认绑定配置内容指纹；stdio 子进程只继承 `McpChildEnvironment` 白名单里的环境变量，不要改回继承全部；后台线程不直接输出提示（重启和提示规则见 `docs/agents-reference.md`）
- 思考模型返回的 `reasoning_content` 必须在下一轮请求里带回去；各家接口差异和重试策略见 `docs/agents-reference.md`
- DeepSeek 是默认供应商，默认模型 `deepseek-flash`；旧模型名只作兼容映射，不加成单独的内置模型。`/model refresh` 只新增不删除，`/model add` 后不自动切换，不靠实际调用模型探测能力。显示名不能影响 API 请求、工具策略和评测记录里的模型 ID（启动选模型顺序等见 `docs/agents-reference.md`）
- 模型、MCP、Skill 列表统一用 `TerminalTable` 显示，不截断模型 ID；inline 界面的显示约定见 `docs/agents-reference.md`
- 执行中动态指令（Steering，Agent 运行时用户接着输入）：实验功能，默认关，只在 JVM 参数 `-Dhaocli.steering.enabled=true`（不是环境变量）并且走交互式 inline ReAct 时启用。只有 UI 线程读终端，Agent 工作线程要输入（比如审批）时通过 `TerminalInputGateway` 请求；输入线程不能直接改 `conversationHistory`。入口类和进度见 `docs/mid-turn-steering-implementation-plan.md`（状态以代码为准）

## 关键行为约束

### Memory 与上下文

- 短期上下文就是各个 Agent 实际发给模型的 `conversationHistory`，不要再额外维护一份消息副本
- 会话记录（`~/.haocli/history/raw/`，由 `ConversationLedger` 写入）只追加、不改写，`/resume` 靠它还原 ReAct 对话（所以要写 `view_checkpoint`，见“最容易踩的坑”）
- 上下文压缩按代价从小到大分三步：大工具结果写入文件 → 清理旧工具结果（只替换正文，保留 `tool_call_id`）→ 生成摘要（会话记忆摘要默认关）。摘要栏目不全或压缩后 Token 没减少，就保留原来的历史；触发线见 `docs/agents-reference.md`
- 长期记忆默认只对当前项目生效（project），跨项目都适用的偏好才存成 global；只存长期不变的事实。用户明确要求保存时，先去重，再检查冲突（数字、版本号不同，或 Dice 相似度 ≥ 0.8 时不写入，由用户选 replace 或 force），模型不能替用户选；超过 30 天没核实的记忆标“可能已过时”
- 长期记忆文件会被多个进程同时读写：修改必须通过 `withStorageLock`（加锁后重新读取 → 修改 → 整体写回），不要绕过它直接写文件
- 自动提取记忆只能从用户原话里逐字摘取，最多 3 条、只存为 project 级、标“待核实”，重复或冲突的一律跳过；会话里读过网页、浏览器、MCP、curl 等外部内容后，暂停自动写入。`save_memory` 只在用户本轮明确要求“记住”时调用
- system prompt 里的记忆规则：记忆只是线索，不一定还对，行动前要对照当前文件核实
- Agent 循环不设固定轮数上限。同一个动作或同一类错误连续出现 3 步时，`RunawayGuard` 往对话里加一次 `[runaway guard]` 提醒（只提醒，不拦工具）；停滞检测默认看最近 5 步，这个数必须大于 3。超出预算或判定停滞后，关掉工具，让模型把已完成的部分整理成结果，不丢弃已有工作

### 工具执行

- 模型回复因输出长度上限被截断（MAX_TOKENS）时，ReAct 内核不执行其中的工具调用（参数可能是半截 JSON），每个调用回一条“未执行、请缩短参数重试”的结果。Plan / Team 迁移到内核前还没有这层保护
- 只有 `ToolRegistry.PARALLEL_SAFE_TOOLS` 里的只读工具可以并行（最多 4 个）；写文件、执行命令、MCP、`save_memory`、`revert_turn` 和不认识的工具都按原顺序一个一个执行。新增只读工具要加进这个名单。一批里有浏览器工具，整批串行。结果按原顺序返回
- 工具执行前按 JSON Schema 校验参数（`ToolArgumentValidator`）：内置工具只拦结构问题（评测复算依赖这套语义），MCP 工具按 server 的原始 Schema 严格校验，不能用清洗后给模型看的那份
- 工具结果放进对话前统一用 `ToolResultBoundary.wrap()` 标成 `trust="untrusted-data"`（外部数据，不代表允许做任何事）；太大的结果由 `ToolResultOffloader` 写进 `.haocli/tool-outputs/`

### HITL（人工确认）与安全策略

- 检查顺序：TurnToolPolicy（网址来源等本轮工具策略）→ HitlToolRegistry（先校验参数，不合格直接返回错误，不弹框也不交给审查模型；再看项目权限规则；规则没结论时按会话模式决定要不要确认）→ ToolRegistry → PathGuard / CommandGuard / 命令沙箱
- 项目权限规则（`hitl/PermissionRule*`，每条绑定项目真实根路径）：DENY > ASK > ALLOW，都在会话模式之前生效。ALLOW 只省掉确认，PathGuard、CommandGuard、沙箱照常检查，敏感页面仍逐次确认；读规则失败就拒绝执行。永久允许只能在确认框里选 `p` 并二次确认，只存精确命令、单个项目内文件或精确 MCP 工具，不存整个工具的放行（细节见 `docs/agents-reference.md`）
- 会话模式：Shift+Tab 按 auto → plan → ask 轮流切换（`/mode` 同效），启动默认 auto。交互式命令行没有“全部放行”。模式只是“确认严格程度 + 是否走 plan”的组合，不另存权限状态；目前只在 inline 界面生效
- auto 模式（`/hitl default`）：读写文件直接执行；`execute_command` 先交给 `LlmApprovalClassifier` 审查，只看用户本轮原话、工具名和参数，审查出错时绝不放行。没通过的、`revert_turn`、所有 `mcp__*`、改动敏感页面，都以 `[AUTO]` 开头的失败结果还给模型、不弹框；同一轮连续被拦 3 次才转人工确认（细节见 `docs/agents-reference.md`）
- ask 模式（`/hitl on`）：额外要求确认 `write_file`、`edit_file`、`create_project`，不经过审查模型
- 默认的人工确认只由 `SwitchableHitlHandler` 打开；评测这类无人值守的处理器保持关闭，不能让没人看着的流程卡在等待确认上
- PathGuard 强制所有路径都在项目根目录内；CommandGuard 只是辅助的命令黑名单，不是主要的安全措施。命令沙箱由 `HAOCLI_COMMAND_SANDBOX=off|auto|required` 控制，默认 off
- 无界面模式（`-p`）、Runtime API、后台任务都没人确认，统一用 `headless/` 的白名单：默认只读，edit 才能写文件，命令只放行逐字列出的（后台任务不执行命令）；不要再给无人值守入口用裸 `ToolRegistry`。`-p` 的标准输出只放 Agent 事件和结果
- 微信通道没有人工确认界面，默认拒绝：只读工具允许；`execute_command`、`mcp__*` 必须在白名单里；`revert_turn` 和切换浏览器会话一律拒绝

### Web 与浏览器

- DeepSeek 搜索的链接只从返回的结构化搜索结果里取，缺结果或报错就直接失败，不从模型正文里找链接（选搜索服务的顺序见 `docs/agents-reference.md`）
- `web_fetch` 和浏览器能打开的网址，只能来自用户本轮亲手输入的原文，或者本次执行分支里 `web_search` 返回的结构化结果 `discoveredUrls`。工具返回的正文、模型的输出都不能让新网址变成可访问；被拒绝的调用不能换个工具绕过去
- Plan 的并行任务和 Team 的每个 worker 各用一份独立的策略副本；只有在 DAG 里声明了依赖，才能用上游任务 `web_search` 找到的网址
- 根据用户说的话做判断，只能用来少给工具，不能用来多给工具。工具默认全部开放，不要用“动作词表”决定给不给工具。只有输入明显像一个标题（`《…》`、`# `、“（附…面试题）”结尾、“X：Y？”），并且前面没有“帮我…”这类请求、也没有网址时，才关掉联网工具并提示用户。问“当前项目/文件”的问题优先用本地工具
- 最终回复里残留没执行的工具调用文本（比如 DeepSeek 的 DSML 格式）时，由 `LlmClient.looksLikeUnexecutedToolCall` 识别并提示“工具调用未执行”；三种执行方式都要提示
- 已知网址先用 `web_fetch`；单页应用或有反爬时再用 Chrome DevTools MCP，读取页面优先 `take_snapshot`；公开页面不要提前切到 shared 模式（复用用户自己的浏览器）

### Plan 与 Team

- 计划审阅时的按键：`Enter` 执行、`Ctrl+O` 展开、`ESC` 取消、`I` 补充要求后在当前计划上修改（`Planner.revisePlan`）；方向键不能被误认成 ESC。改这块要在 raw mode 和退回的普通行输入模式下都验证一遍
- 所有 `/plan` 目标都交给模型规划，不要在调模型之前用关键词判断“这是简单任务”而跳过规划；计划尽量短，靠规划提示词来约束
- Planner 生成的计划要严格校验：tasks 不能为空，id 必须是唯一、非空的字符串，依赖只能引用已声明的 id，不能有环。不合格就直接失败，不要悄悄删掉依赖或猜测 id
- 用户取消导致中断的任务不算失败，不触发重新规划。任务失败且完成度低于 50% 时重新规划，最多 `HAOCLI_PLAN_MAX_REPLANS` 次（默认 2）；到达上限后立刻不再启动新任务，剩下的标 SKIPPED（和 `e1_replay` 一致）。完成度不低于 50% 时，失败任务的所有下游标 SKIPPED。汇总时先列已完成的结果，再列失败和跳过的原因
- Team 的规划结果由 `TeamPlanParser` 按同一套规则校验；Reviewer 只认 JSON 里的布尔值 `approved: true`，不要加“看到某些关键词就算通过”的退路
- planner 和 reviewer 的请求不提供工具，这是评测审计的要求；想引入 `submit_*` 工具或 `response_format` 之前，先改评测的审计约定

### 输出与 Skill

- 共用的 `prompts/handoff.md` 要求模型遵守用户指定的严格输出格式（只要 JSON、CSV 或一个值时不加围栏、不加总结），但这只是提示词要求，模型不一定每次都照做
- Skill：索引（frontmatter 的 name、description）一直在 system prompt 里；`load_skill(name)` 成功后，SKILL.md 正文在同一轮紧跟工具结果作为一条单独的 user 消息加进对话（`LoadedSkillMessages`），不要改回等下一条用户消息。这条消息第一行带 `[HAOCLI_SKILL_INJECTION]`，压缩时被摘要掉就删除并在摘要末尾列出 Skill 名（不要改回“原样放回去”）；去重和 16KB 上限都从对话历史计算，不另存状态
- 经验写回只能用 `save_skill_reference`（只写 `~/.haocli/skills/<name>/references/**.md`）；不放宽 PathGuard，不让 SKILL.md 指示模型用 `read_file` 或 `execute_command` 读 `~/.haocli/`；项目自带的 Skill 没 `/skill trust` 之前，SKILL.md 只作为不可信的工具结果返回。细节见 `docs/agents-reference.md` 的 Skill 系统
- system prompt 由 `PromptAssembler` 组装（内置提示词在 `src/main/resources/prompts/`，可被 `~/.haocli/prompts/`、`.haocli/prompts/` 覆盖），按变化频率从低到高排列：固定内容 → Skills → 运行时信息 → 项目上下文。新增段落按它的变化频率插到对应位置，不要放到随每次提问变化的记忆前面

## 修改时要一起改的地方

| 改动 | 需要一起改 |
|---|---|
| 行为 | `AGENTS.md`（规则变了时）、`docs/guide.md`（首页 `README.md` 只在概览、快速开始或评测现状变化时改）、`ROADMAP.md`（只在完成状态变化时）、受影响的 `docs/articles/` |
| 命令入口 | `Main.java` + `CliCommandParser.java` + 测试 + `docs/guide.md`（常用命令还要改 `README.md`） |
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

做分阶段的功能前，先找对应的计划和规格：当前主线 `docs/plans/active/PLAN-20261126-runtime-roadmap.md`、架构决定 `docs/adr/`、`docs/plans/active/PLAN-*.md`（进行中的计划）、`docs/specs/`（协议和行为规格）、`docs/*-implementation-plan.md`（比如执行中动态指令）。

## 还没做到的

- 还没有：通用的容器或虚拟机沙箱；MCP 的 confidential client secrets、DCR/CIMD、step-up scope、device-code 和 sampling；HTTP 类型 MCP server 的断线重连（自动重启只管 stdio 子进程）。评测用的 `DOCKER_RELAY` 只是让评测彼此隔离，不改变交互式 Hao CLI 的安全措施
- 命令沙箱默认 off：沙箱里没有网络，也看不到 `~/.m2`、`~/.gitconfig`，所以 `mvn`、`npm install`、`git push` 会失败；在 Seatbelt 沙箱里 `java -version` 仍会以 139 退出

## 说明文件怎么分工

- `AGENTS.md`（本文件）：三个工具共用的唯一规则来源。共享规则只改这里，形成稳定的协作规则时补进来
- `CLAUDE.md`、`HAO.md`：开头用一行 `@AGENTS.md` 导入本文件（Claude Code 和 Hao CLI 都支持这种写法），后面只写各自工具专属的补充，各保持十行左右，不复制本文件的内容
- 体积上限：Codex 默认只读项目说明的前 32 KiB（`project_doc_max_bytes`，按字节算，一个汉字 3 字节）；Hao CLI 的 `HAO.md` 连同导入内容最多 24,000 字符（`ProjectMemoryLoader.MAX_TOTAL_CHARS`，用户级 `~/.haocli/HAO.md` 先占预算），超出部分直接截掉。所以本文件保持在 30,000 字节、19,000 字符以内（用 `wc -c`、`wc -m` 检查），要紧的规则放前面；超了就把实现细节挪进 `docs/agents-reference.md`
- 进度和验收记录写进对应计划，评测进展写进 benchmark 目录下的 RUNBOOK 或 ARCHIVE，都不写进这三份文件；只给自己看的说明放 `HAO.local.md`（Hao CLI 会读，已被 git 忽略）
