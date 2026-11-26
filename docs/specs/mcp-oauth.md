# MCP OAuth 配置与存储

状态：OAuth 原始功能及 2026-11-03 发布审查追加修正已通过本轮 43 类/387 项完整离线回归（0 失败、0 错误、0 跳过）。真实授权服务器/浏览器联调和 Windows ACL 真实环境验收仍待执行。

配置仅允许 HTTP server 的 `oauth: {"clientId":"预注册的公共客户端 ID","scopes":["read"]}`，不能同时设置 Authorization header。不接受客户端密钥或其他 OAuth 配置字段。资源 URL 不接受 query/fragment，授权与 token endpoint 不接受预置 query。指纹包含 clientId 和 scopes。

令牌默认保存在 `~/.paicli/oauth/mcp-oauth-tokens.json`；系统属性 `paicli.mcp.oauth.tokens` 可指定用户私有目录中的其他路径，不能放进项目。键由规范化资源 URL、精确 issuer 字符串、client ID 组成。每次修改加文件锁、重读、临时文件原子替换；默认专用 oauth/ 新建为 0700，不修改已有的 ~/.paicli 根目录。POSIX 新建目录 0700、文件 0600；已有父目录必须已是 0700，只核验、不修改权限。非 POSIX 使用仅属主 FULL_CONTROL 的 ACL 并核验；若文件系统不支持 POSIX 或 ACL，拒绝保存，用户应使用该属性指定支持权限控制的本地文件系统路径。不会退回公开可读文件。token response 缺少 `expires_in` 时表示服务端未提供过期时间；显式 `expires_in: 0` 则视为立即过期，不会被当作永久有效。

OAuth HTTP 不跟随跳转（非 OAuth 传输保留原行为），不复制静态 headers 到发现端点，Bearer 只发往配置的 MCP URL。授权服务器 issuer、授权与令牌端点必须 HTTPS；只对本地 MCP resource 放行 HTTP，redirect 只绑定 127.0.0.1。授权服务器元数据必须声明 `response_types_supported` 包含 `code`；若提供 `grant_types_supported`，必须包含 `authorization_code`（字段缺失时按 RFC 8414 默认 grant types 处理）。同时必须明确包含 `token_endpoint_auth_methods_supported: ["none"]`；字段缺失按 RFC 8414 默认 `client_secret_basic` 处理并拒绝，确保只使用预注册公共客户端，不尝试 confidential client 认证。测试通过包内注入 HTTP 客户端/地址校验器使用 MockWebServer，不提供用户可配置的 HTTPS 绕过。元数据最多 1 MiB；回调只绑定 127.0.0.1、精确路径、GET、请求头/目标总计 16 KiB、单连接最多 2 秒、总等待 180 秒。错误不包含服务端响应正文、令牌、code 或 verifier。

多授权服务器时固定选择资源元数据的第一项；clientId 必须在该授权服务器预注册，支持随机端口 loopback redirect。首次 scope 优先使用 401 challenge（当前操作的权威要求），否则使用资源元数据 scopes_supported，两者都没有就省略 scope。配置 scopes 仅作为用户允许范围：有挑战时必须完整允许挑战要求，否则失败；没有挑战时取 PRM scopes_supported 与允许范围的交集；两者都没有时即使有配置也省略 scope。不会自动 scope 升级。logout 删除该资源与 client ID 的全部 issuer 凭证，不调用远程撤销端点。
