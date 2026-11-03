# MCP 双协议约定

> 后续更新（2026-11-03）：下方保留双协议核心阶段的范围与验收记录。现代三种业务操作现已接入 MRTR-2；其他入口仍拒绝 input_required，见 [MRTR 约定](mcp-mrtr.md)。

状态：completed。依据：本阶段用户给定的 2026-07-28 协议决定及仓库已有说明；本阶段未联网核验外部规范。

## 配置和兼容
每个 server 可选 `protocolVersion`，只接受 `2025-03-26`、`2026-07-28`。省略或 null 保持旧版；空字符串和其他版本报配置错误。stdio 与 Streamable HTTP 都支持显式现代版本。没有协商、预探测版本或失败降级。版本进入项目信任指纹；省略字段的旧指纹保持原样。

```json
{"mcpServers":{"modern":{"command":"local-mcp-server","protocolVersion":"2026-07-28"}}}
```

HTTP 用 `url` 代替 command，可同时配置已有的 oauth 公共客户端。旧构造器保持默认旧版。

## 请求与响应
旧版保留 initialize → notifications/initialized、session、DELETE、stdio ping 和原来的服务端主动请求处理；不添加新 _meta。

现代版首次交换是 `server/discover`，其 complete 结果的 `supportedProtocolVersions` 必须是包含 `2026-07-28` 的数组，否则明确失败。读取 capabilities 以判断 resources/prompts 能力。所有请求和通知 params._meta 包含：

- `io.modelcontextprotocol/protocolVersion`: `2026-07-28`
- `io.modelcontextprotocol/clientCapabilities`: `{}`
- `io.modelcontextprotocol/clientInfo`: name 为 paicli，version 与当前启动画面使用同一常量（16.1.0）。

普通完整结果在 JSON-RPC result 对象中保留原业务字段，并要求 `resultType: "complete"`。`input_required` 明确报“尚未实现 MRTR 用户交互”，不当空结果处理；缺失或未知 resultType 报协议无效。不把现代结果当旧版结果悄悄解析。

现代 HTTP POST 强制设置 `MCP-Protocol-Version`、与方法一致的 `Mcp-Method`；tools/call、prompts/get 的非空字符串 params.name，以及 resources/read 的非空字符串 params.uri，派生为 `Mcp-Name`。缺少/空/null 名称时省略该头，绝不发送空头。配置不能覆盖这些派生值。没有 session 捕获、复用或 DELETE；配置注入的 session 头也会移除。

现代模式不发 initialize、initialized、ping、resources/subscribe 或 listen，不注册变化通知监听；服务端主动请求和通知均丢弃，不回旧版 -32601。资源订阅明确报不支持。工具、资源读取和 prompts 列表继续使用普通请求。

## OAuth 边界
OAuth 探测只按固定协议选择 initialize 或 server/discover，后者使用相同 _meta、版本与方法头；这不是版本协商。元数据发现、issuer/PKCE 校验、令牌存储、精确资源绑定、Bearer 注入、一次 401 刷新及跳转保护保持不变。旧 OAuth 方法入口默认旧版。

## 限制和下一阶段
现代变化通知尚未支持，资源变化需手动重新读取；现代 stdio 只有进程退出检测，没有 ping 卡死检测。没有 sampling、roots、logging 或旧式服务端请求处理扩展。下一阶段独立实现 MRTR（一次操作中多次补充输入）：elicitation 用户界面、有上限的往返轮次、原样回传服务端状态；本阶段不启动这项工作。

## 人工验收（本阶段未执行）
1. 用本地已知旧 server 省略版本启动，确认 READY、工具调用、资源读取与关闭行为不变。
2. 本地现代 stdio/HTTP server 显式配置版本，项目配置重新 trust；确认首个请求 discover，随后工具/资源/prompts 正常。
3. 抓取本地 HTTP 请求：版本/方法/名称正确，无 session；关闭无 DELETE。stdio 空闲不产生 ping。
4. 服务端返回不含固定版本的 discovery、缺失 resultType、input_required，分别看到明确错误，无自动降级或 MRTR 重试。
5. 人工配置现代 OAuth server 登录，确认探测 discover、401 仅刷新一次、logout 无 DELETE。该步骤需要用户另行提供并运行授权服务，本阶段仅本地模拟验证。
