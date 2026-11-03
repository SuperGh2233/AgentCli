# 已知文件重复失败观察

状态：completed（保留原路径）。Day2-B 范围：独立观察类和测试，不接入运行循环。

现有 RunawayGuard 的错误键包含完整参数，修改无关参数会打断计数。
现有结果只有成功、超时的类型字段，没有通用的取消、审批、错误类别字段。
因此本次不扩大运行中的错误推断，也不改变现有三次提醒和五步预算行为。

实现：显式 enabled（默认 false）的独立观察器，只观察 edit_file 的固定
old_text 不存在错误。new_text 对这个错误没有影响，比较时忽略它；old_text、
replace_all 和其他参数仍精确比较。路径必须能解析为项目内已有普通文件，
区分大小写；解析失败、越界和符号链接逃逸不计入。只返回一次观察事件，
不输出日志、不写入模型消息、不停止工具、不改变策略。事件不含路径和正文。
超时使用 typed timedOut 排除，不靠正文猜测；successful=true 一律不算失败。
其他失败、任何成功、取消、审批、未知工具和混合批次均清零。

验收：改变 new_text、等价路径连续三步只返回一次事件；不同路径、大小写、
old_text、分页、轮询、MCP、命令、混合成功、取消、审批均不触发新观察。
原有测试不修改。离线运行 RunawayGuardTest、AgentBudgetTest、工具执行与
Phase3B 原有 12 suites，独立 target 子目录、白名单环境，不带凭据。

回退：删除独立类和新增测试即可；没有生产接线。

验证结果：HEAD 始终为 e2013c91c0ecb009576623d3c7951a9647add929，
开始时工作区干净。最终离线回归 18 suites / 150 tests，零失败、零错误、
零跳过，包括 RunawayGuardTest、AgentBudgetTest、原有 Phase3B 12 suites、
ToolArgumentValidationTest、CommandExecutionObservationTest，以及 ToolRegistryTest
的并行顺序、编辑串行、读写批次顺序、浏览器批次串行、批次超时取消五项。
复用 tools/run-phase3b3-tests.py 的隔离 POM 和白名单环境，在进程内扩展测试名单，
未修改该脚本。Maven 使用 -o、-DskipTests=false；额外授权仅为本地 .locks 写入。
最终报告：target/day2b/run-1793666816360714000/summary.json，日志在同目录。
git diff --check 通过；无真实模型、网络、Docker 或 Git 写操作。

限制：只观察当前仍存在的普通文件，不识别删除后的文件、未知错误、命令或 MCP。
取消/审批没有通用类型字段，所以仅接受工具实现的固定错误前缀，不能凭正文
随意归类其他失败；当前不接入 RunawayGuard，不会产生新的用户或模型提醒。
这刻意保留漏报，避免扩大错误状态推断。未来如要接入，先补明确的类型化失败原因，
并在三个运行循环中传入正确项目根目录；本次不做这些改动。

## Day2-C 接入（completed）

只通过 -Dpaicli.stagnation.resource.observer.enabled=true 启用，默认关闭。
每次 ReAct run、Plan 单任务、SubAgent execute 创建带项目根目录的局部观察器。
工具批次返回后、Skill 改写前观察原始结果，只记一次固定事件，不含路径、正文或任务标识。
不改变模型输入、工具顺序、返回值、策略、预算和 RunawayGuard。关闭属性即可回退。
验收覆盖 FakeLLM 开关对照、混合成功、拒绝、取消、重复运行和并发 worker 隔离；
新 target 目录、白名单环境、离线 Maven 和 Phase3B 原有 94 项；无网络、API、Docker 或 Git 写操作。

2026-11-03 验证：基线为干净 HEAD a204b73a60b3cbec5d867758d2975cc2a8af1568。
生产改动仅四个 Java 文件；新增 ResourceFailureIntegrationTest 六项 FakeLLM 测试，
覆盖三模式开关对照（完整模型消息和返回值相等）、每次运行重置与一次性事件、
混合成功、并发 worker 隔离、三模式真实 HITL 拒绝、ReAct 审批期间取消后不执行。
取消检查沿用已存在的 Steering（执行中输入控制）路径，不改 Plan/Team 的取消机制。

离线结果全部零失败、零错误、零跳过：
- 定向 6 suites / 52 tests：target/day2c/targeted-1793667185363900000/summary.json。
- Phase3B 默认关闭 12 suites / 94 tests：target/day2c/phase3b-default-1793667199688838000/summary.json。
- Phase3B 显式开启 12 suites / 94 tests：target/day2c/phase3b-enabled-1793667225419987000/summary.json。

复用 tools/run-phase3b3-tests.py 的隔离 POM、JDK 21、白名单环境和 Maven -o，
仅在调用进程内替换 RUN 和测试名单；开启组给 Maven 增加
-Dpaicli.stagnation.resource.observer.enabled=true，Surefire XML 确认属性传入。
各组 maven.log 与 summary.json 同目录，不覆盖旧构建产物。
最初一次 Maven 在编译前因本地缓存 .locks 写权限失败；获准只写该锁目录后通过。
没有网络、API、Docker、敏感配置或 Git 写操作；git diff --check 通过。

范围限制：仍只观察能够安全解析的已有普通文件和固定错误前缀；不增加
通用失败分类，不向模型提醒，不拦工具、不切策略。日志事件正文是固定枚举值，
没有额外任务、worker、路径或内容字段。没有需要跨会话继续的未完成工作。

## Day2-D 类型化识别（completed）

只给 TextEditMatcher 的源片段未找到异常增加明确失败码，经过 ToolOutput、
ToolExecutionResult、正文清洗、Skill 改写和大结果转存保留，观察器按码识别。
保留旧构造器、错误文案、successful/timedOut、默认关闭、三步一次观察及全部执行顺序。
不改运行循环、RunawayGuard、AgentBudget、外部接口或其他错误分类。
验收包含真实编辑失败、改变文案、伪造前缀、成功/超时/审批/取消/混合批次、
日志隐私和转换元数据回归。复用隔离运行器，产物放 /tmp，Maven -o；不写 target。
回退仅撤销本节列出的局部类型字段及观察器条件。

Day2-D 已完成：FailureCode 是失败原因枚举，包含 NONE（未分类）和
EDIT_SOURCE_NOT_FOUND（源片段未找到）。只有 edit_file 捕获明确标记的
TextEditMatcher 异常才设置后者；空片段、多处匹配、其他失败和旧构造器保持未分类。
匹配器剥行号重试也使用失败码，原错误文案不变。
保留 ToolOutput 原四参/两参、ToolExecutionResult 原九参/七参构造器。
成功和超时计算、URL 来源处理不变。浏览器正文清洗、批次完成、大结果转存、
Skill 正文改写均保留失败码。本节取代前面阶段依赖固定错误前缀的限制；
路径检查、三步一次观察、默认关闭属性、日志和运行循环不变。

2026-11-03 离线验证：在调用进程内复用 tools/run-phase3b3-tests.py，
沿用隔离 POM、JDK 21、白名单环境、Maven -o -DskipTests=false；
只替换运行目录、临时 POM 路径和测试名单，脚本未修改。所有新产物放在
/private/tmp，未写仓库 target。
- 21 个测试组 / 179 项，零失败、零错误、零跳过。包含原 Phase3B 12 组，
  ResourceFailureObserverTest、ResourceFailureIntegrationTest、ToolFailureCodeTest、
  TextEditMatcherTest、ToolResultOffloaderTest、RunawayGuardTest、AgentBudgetTest、
  ToolArgumentValidationTest、CommandExecutionObservationTest。
  报告：/private/tmp/paicli-day2d-1793668208704158000/summary.json
- ToolRegistryTest 中工具顺序、串行编辑、读写顺序、浏览器批次和超时取消：
  1 组 / 5 项，零失败、零错误、零跳过。
  报告：/private/tmp/paicli-day2d-order-1793668225174934000/summary.json
各目录另有 maven.log 和 Surefire XML 报告。

首次 Maven 在编译前因本地缓存锁只读失败，仅授权写 ~/.m2/repository/.locks。
首次测试有两项测试问题：隐私断言把固定日志 Resource 中的 source 当成泄露；
macOS 的 /tmp 指向 /private/tmp，让原路径测试中的绝对路径不同。
修正断言为检查实际私有源文本，并在运行前解析隔离目录的真实路径后全部通过；
没有改生产路径检查。

限制：未跑全量测试或真实模型；隐私断言针对固定观察日志，不改变已有工具错误
正文和审计行为。旧构造器不会推断失败码，因此不触发本观察器。
没有网络、凭据、Docker、Git 写操作或其他阶段工作。git diff --check 通过。
本阶段结束，无需跨会话交接。
