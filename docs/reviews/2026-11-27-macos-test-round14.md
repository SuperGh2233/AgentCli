# macOS 测试反馈 · 第 14 轮（2026-11-27）

对象：分支 `docs/compress-agents-md`，`bf10615`（甲-1 `ToolStatus`）+ `b5d06ca`（沙箱修复）。
环境：macOS 26.6.2，JDK 21.0.12（`JAVA_HOME=$(/usr/libexec/java_home -v 21)`），Maven `/opt/homebrew/bin/mvn`；由 Claude Code 通过 SSH 在用户 Mac 上运行。

## 一、结论：甲-1 通过；顺带修了一个早就存在的沙箱问题

| 项 | 结果 |
|---|---|
| 甲-1 针对性 9 类（`ToolStatusTest`、`HeadlessRunnerTest`、`AgentLoopTest`、`TerminalEventRendererTest`、`HarnessScenarioSuiteTest`、`RuntimeApiServerTest`、`ToolRegistryTest`、`UnexecutedToolCallNoticeTest`、`AgentRunawayReminderTest`） | 94 个，修复前 93 过 / 1 失败（沙箱，见下），修复后全过 |
| 安全专项（`ToolRegistryTest`、`PathGuardTest`、`CommandGuardTest`、`AuditLogTest`、`CommandSandboxDetectorTest`） | 89 / 0 失败 |
| `mvn clean test -Pquick` | 1803 / 0 失败 / 0 错误 / 4 跳过；`tools/test-failures.sh` 无输出 |
| HITL（`com.paicli.hitl.*Test`、`PermissionCommandTest`、`SessionModeControllerTest`） | 173 / 0 失败 |
| 评测包 `-Pbenchmark` | 没跑（评测暂停，需用户同意） |

## 二、沙箱失败：和甲-1 无关

`ToolRegistryTest.macSandboxAllowsDevelopmentRuntimesAndDevNull` 在沙箱里跑 `java -version` 得到 exit 139。在 `31b26ae`（甲-1 之前）同样失败；用户自己终端也失败。第 13 轮没发现，是因为 quick 排除了 `ToolRegistryTest`（`pom.xml` 里按慢测试排除）。

手写 profile 逐项二分，找到三个原因（修复在 `b5d06ca`）：

1. JDK 装在 `~/Library/Java/JavaVirtualMachines` 下，启动时会查看安装路径每一级父目录；profile 没放行父目录元数据 → SIGSEGV。修复：对每个可读目录的各级父目录加 `(allow file-read-metadata (literal …))`，不含子树、不能读内容或列目录
2. `/usr/bin/python3`（Xcode shim）要确认 `/var/select/developer_dir`，而 `/var` 是符号链接被拦 → `xcode-select` 报错 exit 1。修复：放行 `/var` 这条链接的元数据和 `developer_dir` 这一个链接
3. Homebrew 的 `node` 从 `/opt/homebrew/opt/llhttp/lib` 加载动态库被拦 → dyld exit 134。`/opt/homebrew/opt` 只有指向 `Cellar`（本来就可读）的链接，修复：加入只读目录（Intel 对应 `/usr/local/opt`）

新增测试 `seatbeltProfileGrantsOnlyLiteralMetadataOnReadRootAncestors`：父目录只出现在元数据段、全是 `literal`、不出现在可写段。

## 三、提醒

- 改沙箱、`ToolRegistry` 时要单独跑 `ToolRegistryTest`：quick 不覆盖它（本轮保留 `pom.xml` 的排除不动）
- 跳过数从第 13 轮的 5 个变成 4 个，本轮跳过的是 `AutoFactExtractionEvaluationTest`、`CodeIndexTest`、`BubblewrapArgumentsTest`；少的那个没追查
- SSH 非交互登录的 PATH 里没有 `/opt/homebrew/bin`，远程跑要手动加；默认 `java` 是 JDK 25，跑测试要指定 21
