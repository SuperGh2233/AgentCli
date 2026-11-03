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
