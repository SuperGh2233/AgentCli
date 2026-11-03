# MCP HTTP OAuth 登录

状态：原 OAuth 范围及 2026-11-03 发布前追加修正已通过本轮完整离线回归（43 个测试类、387 项全部通过）。真实授权服务器/浏览器联调与 Windows ACL 真实环境验收仍待执行。对应 HaoCli Day3-B。

## 范围与验收

只给现有 Streamable HTTP 传输增加预注册公共客户端的 Authorization Code + PKCE S256 登录。保留 MCP JSON-RPC 2025-03-26 和 initialize 流程，不迁移传输、不做 sampling、CIMD、动态注册、客户端密钥、device code 或自动追加 scope。

完整路径：oauth 配置与项目信任 → `/mcp login <name>` → 无凭证探测 401 → 资源元数据与授权服务器发现 → 验证 issuer/S256 → 127.0.0.1 回调 → 校验 state/iss/code → 带 resource 换令牌 → 用户目录安全保存 → 重启原连接。运行时通过窄接口读令牌；带令牌的请求遇到 401 只刷新并重试一次；403 不重试。logout 先停连接再删该资源凭证，保持停用。

## 测试入口与实现顺序

1. 配置、指纹和发现；MockWebServer 验证路径顺序、challenge、issuer 和 S256。
2. 令牌存储和授权流程；注入浏览器与回调接口、临时存储路径，验证 state/iss/resource 和轮换。
3. 传输接口、manager、CLI；注入令牌提供器，验证一次重试、无跳转泄漏、注销停用与命令分发。
4. 文档与离线回归；使用已有隔离 Maven runner 的 POM 生成函数，所有产物在 /tmp；不调用外部服务、模型或真实浏览器。

存储约定见 `docs/specs/mcp-oauth.md`。保持 Jackson、OkHttp、JDK 17，不新增依赖。不修改 Git 引用。回退时移除 oauth 配置即可继续使用原静态 headers/stdio。


## 验证结果与手工验收

- 相关回归：250 tests，0 failures / errors / skipped。日志 `/tmp/haocli-oauth-1793671246448743000/maven.log`。
- 最终受影响测试（默认专用目录、scope 三分支、补全、DELETE 前刷新与非 OAuth 跳转兼容性）：159 tests，0 failures / errors / skipped。日志 `/tmp/haocli-oauth-1793671833017014000/maven.log`。使用 JDK 21 编译，source/target 保持 Java 17；未升级依赖。
- 测试入口：`python3 /tmp/run-haocli-oauth-tests.py 'OAuth*Test,LoopbackCallbackTest,McpOAuth*Test,StreamableHttpTransportTest,CliCommandParserTest,PaiCliCompleterTest,MainInputNormalizationTest,MainBrowserCommandTest'`。适配器调用现有 `tools/run-phase3b3-tests.py` 的隔离 POM 生成函数，Maven `-o -DskipTests=false`；源代码只读，构建与测试 home/tmp 都在 /tmp。
- 早期失败已修正：测试误实例化私有构造器、Dispatcher 重名；macOS /tmp symlink 路径被误拒；超大回调连接关闭后的 TCP reset。最终结果以完整 Maven 日志为准。
- 已检查完整变更与 `git diff --check`。未运行真实浏览器、外部提供商、模型或 Docker；没有改动提交、分支或标签。

手工验收（本次不执行真实联调）：

1. 在 HTTPS AS 预注册公共客户端，允许随机端口的 `http://127.0.0.1:<port>/oauth/callback`；配置 HTTP MCP url 与 oauth.clientId。需要限制 scope 时配置允许范围。
2. 项目配置先 `/mcp trust remote`，再 `/mcp login remote`；完成浏览器授权，确认恢复 READY、工具可用。无自动浏览器支持时手动打开打印的 URL。
3. 检查默认 `~/.paicli/oauth/` 是 0700、`mcp-oauth-tokens.json` 是 0600，原有 0755 的 `~/.paicli` 权限不变；项目 mcp.json 没有令牌。
4. 令牌失效后验证一次刷新与轮换；服务返回 403 时应直接提示，invalid_grant 时要求重新登录。
5. `/mcp logout remote` 后工具移除、连接 DISABLED；再次 login 成功恢复，取消或失败仍停用。

限制见 `docs/specs/mcp-oauth.md` 和 README：只选第一个 AS、无远程撤销、只支持预注册公共客户端；没有 secrets/DCR/CIMD/step-up/device-code/sampling；资源和端点 query 限制已明确记录。非 POSIX ACL 分支未在真实 Windows 上联调。

## 发布前追加静态审查（2026-11-03）

检查 loopback HTTP 响应时发现状态行缺少 status-code 与 reason-phrase 之间的第二个空格；现已改为标准 `HTTP/1.1 200 OK` / `HTTP/1.1 400 Bad Request`，并把回归断言从仅检查数字改为检查完整状态行。另核对 RFC 8414 公共客户端要求，发现 discovery 未检查 `token_endpoint_auth_methods_supported`；现要求元数据明确声明 `none`，缺失时按规范默认 `client_secret_basic` 拒绝，新增缺失/仅 confidential 方法的回归用例。也补齐授权代码流元数据校验：`response_types_supported` 必须含 `code`，若提供 `grant_types_supported` 必须含 `authorization_code`；缺失 grant_types 时遵循 RFC 默认值，并新增拒绝/默认值回归用例。另修正 `expires_in: 0` 被误当作“无过期时间”的边界：字段缺失继续使用未知过期时间标记，显式 0 则记录为立即过期并触发刷新/重新登录逻辑；新增对应单测。上述追加修复已通过本轮完整离线回归：`LoopbackCallbackTest`、`OAuthDiscoveryTest`、`OAuthFlowTest`、`OAuthManagerTest`、`OAuthTokenStoreTest`、`OAuthTransportTest`、MCP 双协议、MRTR 与 Lanterna 均在 43 类/387 项测试集中，最终 387 项全部通过（0 失败、0 错误、0 跳过，BUILD SUCCESS）。`LoopbackCallbackTest` 四处状态行断言已修正为真实 CRLF 转义。前述 250/159 项结果保留为历史记录，不再代表追加修复状态。最终隔离回归日志：`/tmp/haocli-release-validation-exd496fi/fixture-maven.log`；统计：`fixture-summary.json`；完整性核验：`fixture-integrity.json`。真实授权服务器、真实浏览器和 Windows ACL 仍未联调。
