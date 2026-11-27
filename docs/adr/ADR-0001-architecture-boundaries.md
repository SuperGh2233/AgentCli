# ADR-0001 架构边界：4 个模块、显式注册、包名不改

状态：已接受（2026-11-26，用户确认） · 实施：`docs/plans/active/PLAN-20261126-runtime-roadmap.md` 的甲阶段和拆模块阶段

ADR（架构决策记录）记一个重要决定的背景、内容和放弃的方案，方便以后的人知道“为什么是这样”，再想推翻时先看这里。

## 背景

- 产品方向定为“Java 应用可以嵌入的 Agent 运行时”，需要一个不依赖终端、不依赖 `Main` 的入口
- 现在是单个 Maven 模块：`Main.java` 3519 行、`ToolRegistry.java` 2203 行，内核 `AgentLoop` 已经抽出来，但包之间没有任何依赖约束
- 原路线文档建议 6 类以上模块、用 ServiceLoader 发现本地扩展、包名迁移到 `com.haocli`

## 决定

1. **先守边界，后拆模块。** 甲阶段加依赖方向测试（ArchUnit 或扫描 import），内核包不准引用 `cli`、`render`、`mcp`、存储实现、评测。边界由测试证明稳定后，最后一个工程阶段再拆 Maven 模块
2. **拆成 4 个模块：**
   - `hao-core`：接口和数据类型（模型接口、`Tool`、工具结果、`AgentEvent`、运行结果）、`AgentLoop`、预算、取消、策略接口
   - `hao-runtime`：进程内入口 `HaoRuntime`、默认内置工具、模型客户端、策略和沙箱实现、会话存储、上下文装配；可选能力（Plan、Team、MCP、RAG、记忆、LSP）各占独立包
   - `hao-cli`：`Main`、终端界面、`-p` 无界面模式、Runtime API 服务、微信
   - `hao-eval`：评测
   
   依赖方向：`hao-cli` → `hao-runtime` → `hao-core`；`hao-eval` 可以依赖前三个，任何模块都不依赖 `hao-eval`
3. **扩展只做代码里显式注册。** `HaoRuntime.builder()` 提供 `.tool(...)`、`.contextProvider(...)`、`.onEvent(...)`。注册接口只暴露注册工具、上下文提供者、事件订阅；扩展拿不到原始的 `ToolRegistry`，有副作用的操作一律经过同一个工具执行器和策略。内置可选能力也按同一套接口接入，用一个“关掉全部可选能力”的测试证明内核最小可用
4. **命令行不加载第三方 jar。** 命令行的扩展继续走 MCP（独立进程）和 Skill
5. **包名保持 `com.haocli`。** 新模块坐标用 `hao-*`；模块名和包名不一致在 README 里说明

## 放弃的方案

| 方案 | 为什么不选 |
|---|---|
| 先拆 Maven 模块再整理 | 边界没稳定就拆，会反复挪类、改构建脚本和 CI |
| 按原文档拆 6 类以上模块（api / core / runtime / tools / providers / 每个扩展一个） | 一个人维护，每多一个模块就多一份构建配置、版本对齐和 CI 时间，收益只是“看起来分层” |
| ServiceLoader 自动发现 `~/.haocli/extensions/*.jar` | 等于在用户机器上执行任意第三方代码；同进程插件挡不住它直接调 JDK 的文件和网络接口，不能当安全边界；嵌入方本来就控制自己的 classpath |
| 包名迁移到 `com.haocli` | 几百个文件替换，风险高，收益只有观感；有意保留兼容标识本身是合理的工程取舍 |

## 后果

- 好处：SDK 示例只依赖 `hao-runtime` 就能 `clean build`，“不需要终端也能用”有实物证明；内核被依赖测试守住，往里塞终端或存储代码会直接让测试失败
- 代价：拆模块前，边界只靠测试守；模块名和包名不一致需要解释
- 以后真有第三方扩展需求，可以在显式注册接口之上加 ServiceLoader 发现，接口不用改
