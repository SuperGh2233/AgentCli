# MCP MRTR 实施计划

## MRTR-4 验收范围

状态：active。复用 ElicitationPrompter，为 Lanterna 增加可滚动的表单和 URL 模态交互。所有 GUI 操作通过 runOnGuiThread；同步入口拒绝 GUI 线程，等待有界且可中断，关闭、失败、超时均取消。完整显示服务端、请求、字段说明和 URL/host；打开浏览器必须明确同意。保留无控制台接线，不改 OAuth、协议选择、传输和 MRTR 重试。使用确定性替身验收，并保留原 18 类/170 项；离线输出只写 /tmp。

状态：completed；MRTR-1、MRTR-2 与 MRTR-3 均已完成离线验收。最新行为和证据见末尾 MRTR-3 验证记录；原阶段说明作为历史保留。

以下 MRTR-1 范围、当前进展和限制保留为阶段历史；MRTR-2 已更新原来的“尚未实现往返”状态。

## 目标与边界

建立 MCP 补充输入的不可变请求、响应和终端界面，复用可切换 HITL 生命周期。
只处理 elicitation/create（表单和 URL），默认取消。审批授权不代表允许补充输入。
保留全部 Day3-B OAuth 和双协议修改；不提交、不清理现有文件。
不修改现代协议封装、JsonRpcClient、空 clientCapabilities，不实现重试、sampling 或 roots。

## 实施顺序与验收

1. 请求解析、响应编码、有限表单验证（拒绝敏感字段和不支持的结构）。
2. 共用表单填写、检查修改、接受/拒绝/取消流程；URL 仅明确同意后打开，不读取页面。
3. Renderer、HITL、MCP manager/client 接线；inline 使用已有输入网关。
4. 确定性测试覆盖解析、验证、交互、浏览器替身和委托；只在 /tmp 离线编译测试。
5. 更新文档并检查 diff；MRTR-2 保持待做。

## 风险与回退

外部字段和文案可能包含终端控制符，必须限制数量、长度并拒绝控制符。
不支持的 schema 明确取消，不能猜测字段规则。非交互通道默认取消。
本阶段无协议调用点，回退只需撤销本阶段新增接口接线，不碰原有修改。

## 当前进展

- 已新增 `ElicitationRequest` / `ElicitationResponse` / `McpElicitationHandler`，解析仅接受 `elicitation/create`。
- 已新增受限 schema 校验与共用 `ElicitationPrompter`：flat primitive form、enum/oneOf、enum/anyOf 多选数组、review/edit/submit、decline/cancel；URL 只在用户明确输入 open 后调用浏览器，不预取或读取页面。
- 已将输入回调接入 `McpClient` / `McpServerManager`，并通过 `SwitchableHitlHandler` → `RendererHitlHandler` → `Renderer` 动态跟随 UI delegate；不受 `/hitl` 审批开关或批量放行影响。
- Plain 与 Inline 提供交互；Inline 复用 `TerminalInputGateway` / `TerminalInputLock`。Lanterna、WeChat 和无界面 renderer 暂用默认 cancel。
- 已添加模型、表单、URL、默认取消、Renderer/HITL 委托测试；扩展覆盖 Unicode code point 长度、溢出约束、双向文本控制符和 URL 非法端口。
- 本轮静态审查补强：约束整数范围用 BigInteger 比较，数值上下界用 BigDecimal 比较；文本长度按 Unicode code point；拒绝显示字段中的 FORMAT 控制字符；多选值不允许逗号（当前终端输入使用逗号分隔）；TUI 启动失败降级后恢复原 CLI Renderer HITL delegate。
- 两次尝试启动 Codex 验收任务都因 provider 使用额度限制失败，提示额度恢复时间为 2:36 PM。当前没有 Maven 编译或测试结果；上述本轮静态修补也尚未验证。
- 现代 clientCapabilities 仍为空；McpClient 的 handler seam 尚未被协议请求流程调用。没有 MRTR retry、inputResponses 或 requestState 回传。

## 验证记录

2026-11-03 续接验收：实际工作树为 `/Users/a123/Projects/paicli-agentcli-public-export-20261021-6c9049d020`（当前机器没有名为 paicli-phase2b 的另一个目录）；分支 `feat/steering-phase2b`，HEAD 为 `6a0a35253d7b75b9b2d21f514fc4cfb6402509ec`。已核对原 MRTR-1 会话 `01a19f5b-ad3e-7bf1-a00c-73645e628776`，与此前双协议会话 `01a19f46-6046-71a2-89e0-57b55f441b97` 的目录和现有计划一致。

复用 `tools/run-phase3b3-tests.py` 的 `isolated_pom`、JDK/Maven 查找及报告统计；本次适配脚本为 `/tmp/paicli-mrtr1-validation/run.py`。未改项目 runner。临时 POM、build、测试工作目录、HOME、JVM user.home/java.io.tmpdir、日志、错误文件及 Surefire 报告均置于该 `/tmp` 目录。依赖缓存复制到其 `repository/`，避免 Maven 锁文件写入原缓存。白名单环境不传 provider key；Maven 使用 `-o`。只用内存传输、MockWebServer 本机回环和模拟终端，没有外网、模型、真实浏览器、Docker 或评测执行。

执行入口：

```bash
python3 -B /tmp/paicli-mrtr1-validation/run.py
```

最终实际 Maven 命令（runner 另设 JDK 21、隔离 HOME/TMPDIR、TERM=xterm-256color；Surefire argLine 设置同一隔离 home/tmp、日志及 JVM 错误文件目录）：

```bash
/opt/homebrew/bin/mvn -o -s /Users/a123/Projects/paicli-agentcli-public-export-20261021-6c9049d020/target/publication-check/settings.xml -f /tmp/paicli-mrtr1-validation/pom.xml -Dmaven.repo.local=/tmp/paicli-mrtr1-validation/repository -Duser.home=/tmp/paicli-mrtr1-validation/home -Djava.io.tmpdir=/tmp/paicli-mrtr1-validation/tmp -DskipTests=false -Dtest=ElicitationPrompterTest,RendererHitlHandlerTest,SwitchableHitlHandlerTest,PlainRendererTest,McpServerManagerTest,McpClientTest,McpProtocolEraTest,JsonRpcClientTest,ModernHttpTransportTest,McpCallToolResultTest,McpSchemaSanitizerTest,TerminalInputGatewayTest,TerminalInputGatewayWakeupTest,InlineApprovalGatewayTest,InlineRendererTest,TuiBootstrapTest test
```

- 原始沙箱尝试：Maven 原依赖缓存锁不可写；改为 `/tmp` 缓存后，编译成功，143 项测试中有 36 项因本机监听/JVM attach 被沙箱拦截而出错。获准在沙箱外运行同一离线脚本后解决；未因此改产品代码。
- 加入边界回归后、修复前：145 项，5 失败、1 错误。其中 1 失败和 1 错误分别复现必填字符串回车绕过 enum/format 校验、合法默认邮箱被正则误拒绝；另 4 失败是 runner 的 TERM=dumb 关闭 ANSI 能力所致。
- 修复后、TERM=dumb：148 项，4 失败、0 错误；仅剩上述渲染环境问题。
- 最终 TERM=xterm-256color：**16 个测试类、148 项全部通过，0 失败、0 错误、0 跳过，BUILD SUCCESS**。生产代码 415 个源文件、测试代码 365 个源文件编译通过；未执行 benchmark/eval 测试。
- 分项：ElicitationPrompterTest 16、RendererHitlHandlerTest 9、SwitchableHitlHandlerTest 1、PlainRendererTest 17、McpServerManagerTest 14、McpClientTest 13、McpProtocolEraTest 6、JsonRpcClientTest 8、ModernHttpTransportTest 3、McpCallToolResultTest 4、McpSchemaSanitizerTest 7、TerminalInputGatewayTest 11、TerminalInputGatewayWakeupTest 8、InlineApprovalGatewayTest 8、InlineRendererTest 19、TuiBootstrapTest 4。
- 最终日志 `/tmp/paicli-mrtr1-validation/final-maven.log`；统计 `/tmp/paicli-mrtr1-validation/summary.json`；报告 `/tmp/paicli-mrtr1-validation/build/surefire-reports/`。此前结果另存 `before-fix-*`、`dumb-term-*`。
- Inline 新测试确认 elicitation 的每次读取都由 UI owner 执行，worker 等待不会占住终端锁，结束后 interaction 恢复 NONE。旧网关、超时、唤醒与审批回归通过。
- 已静态检查 Main 的 TUI 启动失败分支恢复 `rendererHitl`；TuiBootstrapTest 与可切换 delegate 测试通过。未实际启动 TUI、CLI Main 或真实终端，不将这部分算作真人交互验收。
- `git diff --check` 通过；最终 HEAD 未变、暂存区为空，现有 dirty/untracked 保留，项目 target 无本次写入。

## 本次续接实际修改文件

1. `src/main/java/com/paicli/mcp/elicitation/ElicitationForm.java`：修正默认邮箱正则的重复转义。
2. `src/main/java/com/paicli/mcp/elicitation/ElicitationPrompter.java`：必填空字符串同样走 enum/format/长度校验。
3. `src/test/java/com/paicli/mcp/elicitation/ElicitationPrompterTest.java`：新增空输入、默认邮箱、enum/oneOf/数组默认值与重复选择、不可变复制、解析类型和模式混用回归。
4. `src/test/java/com/paicli/render/inline/InlineRendererTest.java`：新增 elicitation 网关输入所有权与锁回归。
5. `docs/specs/mcp-mrtr.md`：记录 MRTR-1 验收状态与当前交互限制。
6. `README.md`：标明 MRTR-1 交互层完成。
7. `ROADMAP.md`：标明 MRTR-1 交互层完成。
8. `docs/plans/active/PLAN-20261103-mcp-mrtr.md`：记录本次命令、结果和边界。

所有其余原有源码、测试、文档及未跟踪文件保持原样；没有 stage、commit 或 Git 引用操作。

## 仍有的限制

- schema 仍是已有的有限子集，没有扩充到通用 JSON Schema；多选按逗号分隔，因此拒绝包含逗号的枚举值。不支持嵌套对象、pattern 等未知规则。
- 表单的 decline/cancel 操作在填写后的检查页；字段输入时 EOF、读取失败或中断会取消，没有字段级 decline/cancel 命令，也没有填写前的独立菜单。
- Lanterna、WeChat 和无人值守路径默认取消；TUI 降级接线已检查，但真实界面未手工验收。
- handler 尚未接入现代协议 input_required 往返，clientCapabilities 仍为空。协议重试、inputResponses、requestState 回传仍属于 MRTR-2。

## MRTR-1 验收时的下一步（已由下方 MRTR-2 实施接续）

MRTR-2：原请求的新 id 重试、opaque requestState 原样回传、有限交互往返、显式拒绝未知方法、端到端验证后再声明 capability。

## MRTR-2 本次实施

仅现代 tools/call、resources/read、prompts/get 使用有限往返，默认 JSON-RPC 入口仍严格要求 complete。每轮最多 8 个请求、输入 map 序列化最多 65536 字符、最多 4 轮补充输入。整轮验证后才询问，重试只携带本轮响应和字符串状态，使用新 id。错误消息不包含服务端输入和状态。
动态 HITL delegate 无 UI 能力查询接口，同一 manager 可用于非交互路径，本次不根据 handler 是否存在猜测能力，继续不声明 elicitation。验收使用本地替身及 /tmp 隔离离线构建。

## MRTR-2 验证记录（2026-11-03）

已实现的文件：`JsonRpcClient` 新增只允许三种现代操作返回 input_required 的入口，普通入口保持严格校验；`McpClient` 的 tools/call、resources/read 及新增 getPrompt 使用同一有限循环。整轮解析通过后才调用已有 handler，accept/decline/cancel 原样编码，校验响应 key 与本轮请求 key 完全相同。每次从原业务参数构造重试，JSON-RPC 自动分配新 id；requestState 只检查字符串并在下一次重试回传，无状态字段时省略，不进入 UI、模型或持久化数据。McpServerManager 原有动态 handler 接线可直接复用，未修改。

新增 `McpMrtrTest` 11 项确定性测试：complete、三种操作的单轮 accept/decline/cancel、多轮、新 id、业务参数保留、特殊字符状态原样回传/省略、state-only 与跨请求隔离、整轮格式/未知方法拒绝、数量/大小/轮数上限、URL 接受、默认与 null 响应取消、不合法 accept content、legacy 和其他请求的严格行为。只使用 InMemoryTransport；URL 只是测试数据，没有调用浏览器。

验证入口：

```bash
python3 -B /tmp/paicli-mrtr2-validation/run.py
```

复用 MRTR-1 runner，复制依赖缓存到本次 `/tmp/paicli-mrtr2-validation/repository`，其余隔离设置不变：JDK 21、Maven `-o`、白名单环境（无 provider key）、TERM=xterm-256color、独立 HOME/user.home/tmp、测试工作目录、构建和报告。只读取仓库 target 中原有 settings.xml，没有写入仓库 target。最终 Maven 命令：

```bash
/opt/homebrew/bin/mvn -o -s /Users/a123/Projects/paicli-agentcli-public-export-20261021-6c9049d020/target/publication-check/settings.xml -f /tmp/paicli-mrtr2-validation/pom.xml -Dmaven.repo.local=/tmp/paicli-mrtr2-validation/repository -Duser.home=/tmp/paicli-mrtr2-validation/home -Djava.io.tmpdir=/tmp/paicli-mrtr2-validation/tmp -DskipTests=false -Dtest=McpMrtrTest,ElicitationPrompterTest,RendererHitlHandlerTest,SwitchableHitlHandlerTest,PlainRendererTest,McpServerManagerTest,McpClientTest,McpProtocolEraTest,JsonRpcClientTest,ModernHttpTransportTest,McpCallToolResultTest,McpSchemaSanitizerTest,TerminalInputGatewayTest,TerminalInputGatewayWakeupTest,InlineApprovalGatewayTest,InlineRendererTest,TuiBootstrapTest test
```

- 首轮：17 类、155 项，1 失败、2 错误，均来自新增测试替身使用了不合法的空字段表单；原有 148 项全部通过。修正替身并新增边界用例后，第二轮 158 项全部通过。
- 最终源码回归：**17 个测试类、159 项，0 失败、0 错误、0 跳过，BUILD SUCCESS**；原 MRTR-1 的 16 类/148 项全部保留通过，新增 11 项全部通过。
- 日志：`/tmp/paicli-mrtr2-validation/final-maven.log`；统计：`summary.json`；报告：`build/surefire-reports/`。首轮及第二轮证据以 first-/second- 前缀保留在同一临时目录。
- 未运行真实 server、provider/model、外网、浏览器、Docker、eval 或 benchmark；相关原有 HTTP 测试只使用本机回环替身。未进行真实终端人工验收。
- `git diff --check` 通过；分支仍为 feat/steering-phase2b，HEAD 仍为 6a0a35253d7b75b9b2d21f514fc4cfb6402509ec，暂存区为空。保留全部已有 dirty/untracked，未 reset/clean/stash/rebase/stage/commit/push/tag。

本次文档同步：本计划、MRTR 规范、README、ROADMAP、AGENTS、agents-reference 和 docs/index；双协议规范只加后续进展指引，保留原阶段说明。未改 OAuth、传输、协议版本选择、界面实现或已有测试。

## MRTR-2 当时的后续阻塞（已由 MRTR-3 解决）

MRTR-2 协议往返的安全范围已验收；当前不声明 elicitation 能力。handler 是函数接口，manager 的动态委托无法查询当前 renderer 是否确实具有 form/url 界面；Plain/Inline、Lanterna、WeChat、benchmark 的能力尚未通过统一接口传到请求层。后续先增加可靠的当前界面能力查询与非交互回归，再在现代请求的 `_meta.io.modelcontextprotocol/clientCapabilities` 中声明对应能力。依赖声明才发起 elicitation 的服务端目前不会主动启用交互。计划保持 active 仅跟踪此后续事项；本次协议实现与验证已完成，无需另建交接文件。

## MRTR-3 实施范围（2026-11-03）

按每次请求的当前 UI 声明 form/url。函数接口默认空能力，HITL 与 Renderer 逐层查询当前 delegate；Main 只在 System.console() 非空时连接交互 handler，否则保留 CANCEL。manager 为现有和新 client 传同一对象，client 向 JSON-RPC 提供实时 supplier，查询异常或 null 时为空。只在现代 requestWithInputs 的 tools/call、resources/read、prompts/get 声明；普通请求、通知、legacy 不声明。未知 mode 忽略，保留其他元数据。不改 UI、OAuth、版本选择、传输或重试语义。

验收：精确 metadata、模式组合、普通/legacy 请求、默认取消、动态 delegate 与 manager 接线，以及原 159 项离线回归；所有构建产物放 /tmp/paicli-mrtr3-validation。回退仅移除本阶段能力查询和声明，保留 MRTR-2。

## MRTR-3 验证记录（2026-11-03）

实现按上节范围完成；独立 diff 检查指出原 putObject 会覆盖调用方自定义 capability，已改为保留同一对象内其他键，只按当前 UI 重建 elicitation。新增回归证明 custom capability 与其他 _meta 保留、调用方原对象不变，普通请求/通知不会继承传入的旧 elicitation。Main 的 Console 保守检查及动态 delegate 测试保留。

验证入口：`python3 -B /tmp/paicli-mrtr3-validation/run.py`。复用 MRTR-2 runner，临时 POM、复制的依赖缓存、build、工作目录、HOME/user.home/tmp、日志和 Surefire 报告均在本次 /tmp 目录。JDK 21、Maven -o、TERM=xterm-256color、白名单环境无 provider key；仅读取仓库已有 settings.xml，无仓库 target 写入。完整 Maven 命令保存在 summary.json。

首轮 18 类/169 项全部通过。按独立检查意见补充自定义能力保留及旧 elicitation 清理后，最终 **18 个测试类、170 项，0 失败、0 错误、0 跳过，BUILD SUCCESS**。以测试 classname/name 对照 MRTR-2 XML：原 159 项全部保留，无缺项，新增 11 项。新增能力测试类 9 项；manager 新增 1 项；Inline 新增 1 项，并在原输入网关交互回归内断言能力。

分项：McpElicitationCapabilitiesTest 9、McpMrtrTest 11、ElicitationPrompterTest 16、RendererHitlHandlerTest 9、SwitchableHitlHandlerTest 1、PlainRendererTest 17、McpServerManagerTest 15、McpClientTest 13、McpProtocolEraTest 6、JsonRpcClientTest 8、ModernHttpTransportTest 3、McpCallToolResultTest 4、McpSchemaSanitizerTest 7、TerminalInputGatewayTest 11、TerminalInputGatewayWakeupTest 8、InlineApprovalGatewayTest 8、InlineRendererTest 20、TuiBootstrapTest 4。

新增回归覆盖模式组合与精确形状、未知模式、元数据保留、入口限制、默认 CANCEL/benchmark 风格 client、legacy、查询失败/null、Plain 与默认 Renderer/Lanterna、Renderer/HITL 动态查询、Switchable 在 client 创建后切换及 manager 为新建/现有 client 传活对象。新增 manager 回归通过实际发出请求的元数据验证更新，不反射私有 handler 字段；沿用既有测试配置装载 fixture。HTTP 请求等待有 3 秒上限。benchmark 只检查相同的默认 client 路径，未运行 eval/benchmark。未访问外网、provider/model、真实浏览器、Docker 或真实 server；HTTP 测试仅本机替身。未进行真实终端人工验收。

移除新增私有字段反射、为本机请求等待设置上限后最终重跑仍为 18 类/170 项全部通过，耗时 40.120 秒。`git diff --check` 通过，暂存区为空；启动时文件哈希对照仅出现本节列出的 21 个已有文件修改和 1 个新增测试，没有删除原文件，其他原有文件哈希不变。完整核对结果在 `/tmp/paicli-mrtr3-validation/final-integrity.json`。

最终证据：`/tmp/paicli-mrtr3-validation/final-maven.log`、`summary.json`、`build/surefire-reports/`；首轮证据 first-*。Console 为空的 IDE、重定向或无法确认交互性的 CLI 保持空能力；这可能保守漏报，不推测支持。Lanterna、WeChat、benchmark 仍不声明。不改 UI、OAuth、协议选择、传输或 MRTR 重试语义。

本次文件清单（不包含此前已有改动）：

- 源码：cli/Main.java；hitl/HitlHandler.java、RendererHitlHandler.java、SwitchableHitlHandler.java；mcp/McpClient.java、McpServerManager.java、elicitation/McpElicitationHandler.java、jsonrpc/JsonRpcClient.java、protocol/McpProtocolVersion.java；render/Renderer.java、PlainRenderer.java、inline/InlineRenderer.java（均在 src/main/java/com/paicli/ 下）。
- 测试：src/test/java/com/paicli/mcp/McpElicitationCapabilitiesTest.java（新增）、McpServerManagerTest.java；src/test/java/com/paicli/render/inline/InlineRendererTest.java。
- 文档：AGENTS.md、README.md、ROADMAP.md、docs/agents-reference.md、docs/index.md、本计划及 docs/specs/mcp-mrtr.md。

原有 dirty/untracked 保留，分支 `feat/steering-phase2b`、验收前 HEAD `6a0a35253d7b75b9b2d21f514fc4cfb6402509ec` 不变。未 reset/clean/stash/rebase/stage/commit/push/tag。MRTR-1/2/3 的阶段性验收保持有效；MRTR-4 已通过本轮完整离线回归，真实终端手工验收仍待执行。

## MRTR-4 Lanterna 界面（2026-11-03，离线验收完成）

目标：复用现有 ElicitationPrompter，为 Lanterna 增加可滚动的表单与 URL 模态交互。所有控件操作通过 GUI 线程调度；同步入口拒绝 GUI 线程调用，等待有 5 分钟上限且可中断；关闭、失败、超时、GUI 不可用均安全取消。URL 只在用户明确选择打开后调用默认浏览器。Main 仍只在 System.console() 非空时连接 SwitchableHitlHandler；TuiBootstrap 启动全屏界面后切换同一 delegate，manager/client 可在下一次请求实时读取 Lanterna 能力。

本轮静态检查已发现并修正两处测试不匹配：`LanternaElicitation.Dialogs.show` 测试替身参数从旧 boolean 改为 `LanternaElicitation.Step`；`ElicitationDialog` 测试构造器改用 `Step.URL` / `Step.FIELD`。同时移除 MCP 能力测试中对无构造实例化 `LanternaRenderer` 并调用真实方法的错误断言；Lanterna 能力由 `LanternaElicitationTest.rendererAdvertisesExactlyBothOnlyWhileAvailable` 使用注入式 bridge 覆盖。相关说明已同步到 AGENTS、README、ROADMAP、agents-reference、MRTR 规范和 docs/index。另在 `LanternaRendererTest` 增加公开构造器生命周期测试，验证 GUI 未运行时不声明、运行时声明 form/url、renderer 关闭后再次清空能力。`ElicitationDialog` 的完成/关闭回调也改为幂等，避免按钮提交后 finally 清理再次触发关闭与重复回调。

**当前验证状态：本轮完整 Maven 离线回归已实际执行并通过。** 使用 `/tmp/haocli-release-validation-exd496fi/rerun.py` 生成隔离 POM/环境，Maven `-o`，本地依赖缓存复制到 `/tmp`，Maven 与 Surefire JVM 的 HOME/user.home/tmp、构建目录、工作目录、日志和报告均隔离；临时测试工作目录包含只读 `pom.xml` fixture，以复现本地路径补全测试所需的项目根目录文件。最终 43 个测试类、387 项全部通过，0 失败、0 错误、0 跳过，BUILD SUCCESS，exit code 0，耗时 45.460 秒。首次运行中两项 `PaiCliCompleterTest` 因临时工作目录缺少 `pom.xml` 失败；补充只读 fixture 后完整重跑通过，没有跳过测试或修改产品代码。未进行真实 Lanterna 终端手工验收。

MRTR-4 的离线编译与回归已通过；本计划仍保留 active，直至真实 Lanterna 终端手工验收完成。发布前仍须复核所有 dirty/untracked 文件、敏感文件与 `git diff --check`。

## 2026-11-03 发布前完整离线回归明细

本次执行 43 个测试类、387 项测试，最终 387 passed / 0 failed / 0 errors / 0 skipped，Maven `BUILD SUCCESS`，exit code 0，耗时 45.460 秒。测试名单由 runner 显式指定，包含：

- `com.paicli.cli`：`CliCommandParserTest`、`McpOAuthCommandTest`、`PaiCliCompleterTest`、`TerminalInputGatewayTest`、`TerminalInputGatewayWakeupTest`
- `com.paicli.hitl`：`RendererHitlHandlerTest`、`SwitchableHitlHandlerTest`
- `com.paicli.mcp`：`McpClientTest`、`McpElicitationCapabilitiesTest`、`McpMrtrTest`、`McpProtocolEraTest`、`McpResourceFailureTest`、`McpServerLifecycleTest`、`McpServerManagerTest`、`McpToolRegistrationTest`
- `com.paicli.mcp.config`：`McpConfigLoaderTest`、`McpOAuthConfigTest`
- `com.paicli.mcp.elicitation`：`ElicitationPrompterTest`
- `com.paicli.mcp.jsonrpc`：`JsonRpcClientTest`
- `com.paicli.mcp.mention`：`AtMentionCompleterTest`、`AtMentionExpanderTest`、`AtMentionParserTest`
- `com.paicli.mcp.notifications`：`NotificationRouterTest`
- `com.paicli.mcp.oauth`：`LoopbackCallbackTest`、`OAuthDiscoveryTest`、`OAuthFlowTest`、`OAuthManagerTest`、`OAuthTokenStoreTest`、`OAuthTransportTest`
- `com.paicli.mcp.protocol`：`McpCallToolResultTest`、`McpSchemaSanitizerTest`
- `com.paicli.mcp.resources`：`McpResourceCacheTest`
- `com.paicli.mcp.transport`：`McpChildEnvironmentTest`、`McpPidFileTest`、`ModernHttpTransportTest`、`StdioTransportTest`、`StreamableHttpTransportTest`
- `com.paicli.render`：`PlainRendererTest`
- `com.paicli.render.inline`：`InlineApprovalGatewayTest`、`InlineRendererTest`
- `com.paicli.tui`：`LanternaElicitationTest`、`LanternaRendererTest`、`TuiBootstrapTest`

首次执行中 `PaiCliCompleterTest` 的两个本地路径补全用例因隔离测试工作目录缺少 `pom.xml` fixture 失败；在 `/tmp` 测试工作目录加入只读 `pom.xml` 副本后，完整 43 类测试重跑通过。未跳过这两个用例，未修改产品代码。

执行入口：`python3 -B /tmp/haocli-release-validation-exd496fi/rerun.py`。Maven 使用 `-o` 和与本机缓存来源匹配的 `phase0-central-only` settings；本地 Maven 仓库复制到 `/tmp`，Maven 与 Surefire JVM 的 `HOME`、`user.home`、`java.io.tmpdir`、工作目录、构建输出、日志和报告均隔离。环境白名单未传入 provider API keys；测试 HTTP 仅使用本机 MockWebServer/loopback，终端交互使用测试替身。没有访问外网、真实模型、真实浏览器、Docker、eval 或 benchmark。

证据文件：完整日志 `/tmp/haocli-release-validation-exd496fi/fixture-maven.log`；分项统计 `/tmp/haocli-release-validation-exd496fi/fixture-summary.json`；完整性核验 `/tmp/haocli-release-validation-exd496fi/fixture-integrity.json`。执行前后项目文件（含 `target`）及原 `~/.m2/repository` 无变化；`.env*` 只核验元数据，未读取内容。未进行真实 Lanterna 终端手工验收、真实 MCP 服务端联调或 Windows ACL 真实环境验收。
