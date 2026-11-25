# 本机 macOS 测试报告（第 2 轮）

日期：2026-11-25 11:15 · 测试机：macOS（Apple Silicon）· 被测：`feat/steering-phase2b` @ `a4e5ca6` + 工作区未提交改动（P0/P1 修复）
环境：JDK 21.0.12.1 全量 · JDK 25.0.4.1 定向复核

## 结论

**P0、P1 两个缺陷的修复已验证有效，全量回归 1709 个测试零失败零错误，35.5 秒跑完。** 安全边界没有被削弱。JDK 25 下的终端挂死和 Mockito 问题依旧存在，但这批改动没碰相关代码，属于既有环境问题。

| 运行 | 结果 | 耗时 |
|---|---|---|
| `mvn clean test -Pquick`（JDK 21） | **1709 通过 / 0 失败 / 0 错误 / 5 跳过** | 35.5s |
| 定向复核（JDK 25，9 个类含 MCP trust + 路径 + steering） | **136 通过 / 0 失败** | 0.25s |
| `McpElicitationCapabilitiesTest`（JDK 25） | 9 个中 1 个 error（既有问题） | 0.3s |

对比第 1 轮：同样是全量，上一轮 16 个 error，本轮 0 个。

## P0 修复复核：MCP trust 符号链接

改法：`Files.isSymbolicLink(current) && !isRootOwnedLink(current)` —— 属主是 root 的符号链接放行，其他一律拒绝。

复核结论：**安全边界是完好的**，不是把检查变成摆设。

- macOS 的 `/var → private/var`、`/tmp → private/tmp` 属主是 root，普通用户改不了，放行合理。
- 用户自己建的目录符号链接仍被拒绝，`McpTrustStoreTest.userCreatedSymlinkedParentDirectoryIsRejected` 覆盖了这个边界，并且断言了「失败时没有在真实目录里留下文件」。
- trust 存储目录**本身**是符号链接时仍然拒绝（`ensurePrivateDirectory` 末尾的独立判断没被改掉），即使属主是 root。
- 读不到属主（Windows）按不安全处理，方向正确。

## P1 修复复核：绝对路径经过符号链接根

改法：绝对路径对不上 `safeRoot` 时，若落在未解析的项目根内，就换成真实根再比较，只替换根这一段。

复核：换根后 `startsWith` 通过，含 `..` 的穿越路径在 `normalize` 后不落入项目根、直接返回 null；项目内部的符号链接别名仍不合并（`toRealPath` 后不 startsWith 就跳过）。新增用例 `absolutePathThroughSymlinkedRootMatchesRelativePath` 覆盖「根是软链 + 绝对路径 + 相对路径算同一文件」。

## 仍然存在的问题（不是这批改动引入的）

1. **JDK 25 下终端测试挂死**：`SteeringRunLoopTest`、`SessionModeControllerTest`、`TerminalInputGatewayWakeupTest` 在全量运行时卡在 `JniNativePty.close → FileDescriptor.close0`，JDK 21 同样代码 35 秒跑完。本机回归请固定 JDK 21。
2. **JDK 25 下 Mockito 5.11** 对接口 `CALLS_REAL_METHODS` 报 `Cannot call abstract real method`（`McpElicitationCapabilitiesTest`），JDK 21 通过。

## 为什么 CI 一直看不到这两个缺陷

`.github/workflows/ci.yml` 用的是 **JDK 17 + Linux**：

- Linux 的 `/tmp` 不是符号链接，所以 P0 不会触发；
- CI 不跑 JDK 25，所以挂死不会触发。

建议至少加一个 macOS job（用 `@TempDir` 就能复现 P0），否则这类「只在开发机出现」的问题每次都要靠人工发现。

## 建议的下一步

- 这批修复建议尽快提交，我下一轮好对着 commit 复测（现在是对着工作区测的，无法区分版本）。
- Plan Steering 仍未接线（`configurePlanSteeringSafePoint` 无调用方），与计划文档描述一致。
