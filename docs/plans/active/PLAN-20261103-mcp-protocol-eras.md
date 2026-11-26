# MCP 双协议核心

状态：completed。保留原路径，便于已建立的链接继续使用。

## 范围和约束
保留未提交的 Day3-B OAuth。默认 2025-03-26；单个 server 配置 protocolVersion=2026-07-28 才启用现代协议。不自动协商、预探测或降级。只用仓库资料和用户协议决定，不联网、不调用模型、不跑 Docker 或评测，不修改 Git 引用。

## 顺序与验收
1. 协议类型、配置校验及指纹；保留所有旧构造器。
2. 现代 server/discover 校验固定版本，集中添加请求 _meta 和验证 resultType；input_required 明确报 MRTR 未实现。
3. HTTP 版本、方法、主名称头，无 session/DELETE；保留 OAuth。现代 stdio 无 ping、主动请求回复、资源订阅或通知监听。
4. 内存和 MockWebServer 测试；现有隔离 POM 方式离线运行，产物写 /tmp；检查完整差异。

## 风险与回退
移除 protocolVersion 恢复旧协议。现代变化通知未支持。下一阶段 MRTR：交互界面、有上限的往返轮次、原样回传状态，不是旧 sampling。

## 验证记录
最终离线 Maven：2026-11-03 10:49:29 +08:00，BUILD SUCCESS，30 个测试类，244 项测试，失败 0、错误 0、跳过 0；耗时 49.508 秒。覆盖全部 MCP 测试及 McpOAuthCommandTest、PaiCliCompleterTest、CliCommandParserTest。

使用 tools/run-phase3b3-tests.py 的 isolated_pom 生成函数，临时 POM、构建输出和测试报告均位于 /tmp/paicli-protocol-tests/。运行 Maven -o、-DskipTests=false，并用白名单环境隔离用户配置与 provider Key。只读取现有 target/publication-check/settings.xml；未写项目 target。

精确执行参数和数量：/tmp/paicli-protocol-tests/summary.json；最终日志：/tmp/paicli-protocol-tests/final-maven.log；本地重跑入口：python3 /tmp/paicli-protocol-tests/run.py。

已检查完整工作差异，git diff --check 通过。Day3-B OAuth 保留；其新增调整仅为经用户确认的协议版本透传（OAuthDiscovery 探测入口及 OAuthFlow/manager 传参），未改元数据发现、issuer、PKCE、存储、Bearer 绑定、401 刷新或重定向保护。通知复用请求元数据处理；主名称缺失、null、空值均省略 Mcp-Name。

未调用真实 provider、外部网络、浏览器、模型或 Docker；未跑 benchmark/eval 测试、未改 Native AgentBench 文件。没有提交、推送、tag、checkout、reset 或 amend；HEAD 保持 6a0a35253d7b75b9b2d21f514fc4cfb6402509ec，分支 feat/steering-phase2b。tag 0.1.5 保持原样。

## 本阶段精确改动文件
以下仅列本阶段新增或修改的 22 个文件，不把原有 Day3-B 的其他未提交文件算成本阶段改动：

- AGENTS.md
- README.md
- ROADMAP.md
- docs/agents-reference.md
- docs/index.md
- docs/specs/mcp-protocol-eras.md（新增）
- docs/plans/active/PLAN-20261103-mcp-protocol-eras.md（新增）
- src/main/java/com/paicli/cli/Main.java（仅共享现有版本常量）
- src/main/java/com/paicli/mcp/McpClient.java
- src/main/java/com/paicli/mcp/McpServerManager.java
- src/main/java/com/paicli/mcp/config/McpConfigLoader.java
- src/main/java/com/paicli/mcp/config/McpServerConfig.java
- src/main/java/com/paicli/mcp/jsonrpc/JsonRpcClient.java
- src/main/java/com/paicli/mcp/protocol/McpProtocolVersion.java（新增）
- src/main/java/com/paicli/mcp/transport/StreamableHttpTransport.java
- src/main/java/com/paicli/mcp/oauth/OAuthDiscovery.java（原 Day3-B 新文件）
- src/main/java/com/paicli/mcp/oauth/OAuthFlow.java（原 Day3-B 新文件，仅版本透传）
- src/test/java/com/paicli/mcp/McpProtocolEraTest.java（新增）
- src/test/java/com/paicli/mcp/McpServerManagerTest.java
- src/test/java/com/paicli/mcp/jsonrpc/JsonRpcClientTest.java
- src/test/java/com/paicli/mcp/transport/ModernHttpTransportTest.java（新增）
- src/test/java/com/paicli/mcp/oauth/OAuthDiscoveryTest.java（原 Day3-B 新文件）

## 限制与人工验收
现代变化通知和 MRTR 尚未支持；现代 stdio 不用 ping 检测卡死，但保留进程退出检测。不增加 sampling、roots、logging。人工验收步骤见 docs/specs/mcp-protocol-eras.md，涵盖旧版不变、两种现代传输、错误反馈和 OAuth。本阶段只做本地模拟，未进行真实服务联调；没有待解决的阻塞。下一阶段为 MRTR，不在本阶段继续实现。
