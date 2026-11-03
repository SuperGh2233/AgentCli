# MCP MRTR 与补充输入约定

状态：MRTR-1/2/3/4 已完成实现并通过本轮 43 类/387 项离线回归（0 失败、0 错误、0 跳过，BUILD SUCCESS）。真实 Lanterna 终端手工验收与真实 MCP 服务端联调仍待执行；18 类/170 项是 MRTR-1/2/3 阶段的历史记录。

MRTR（多轮请求）是用新的 JSON-RPC id 重试原来的 tools/call、resources/read 或 prompts/get。
input_required 至少带 inputRequests 或 requestState 之一。requestState 是服务端的不透明值，只能在原请求重试时原样回传，不能查看或修改。
MRTR-2 改变三种原请求的补充输入结果处理；MRTR-3 仅在这三种操作的现代 MRTR 入口按当前 UI 声明能力。

## MRTR-1 交互接口

请求包含服务端名、服务端分配的 key、mode（省略时 form）、message、requestedSchema 或 url。
只解析 method=elicitation/create 的 entry，其他 method 明确拒绝。
响应 action 为 accept/decline/cancel。表单接受带 content 对象；URL 接受及拒绝/取消不带 content。
数据不写日志、不交给模型。HITL 只复用界面生命周期，审批启用状态、会话放行不适用于补充输入。
默认处理器和默认 Renderer 方法都取消；WeChat 与无人值守 renderer 暂不提供表单 UI，安全取消。Lanterna 的界面能力由 MRTR-4 增加，仅在 GUI 主循环运行时启用。

表单只支持平面 object 的 string/number/integer/boolean、enum、带 const/title 的 oneOf，以及字符串 enum / 带 const/title 的 anyOf 多选数组。
支持 required/default、说明、数值上下界、字符串长度、数组数量；未知结构或规则取消。
限制字段和选项数量、文案和输入长度；拒绝控制字符和明显密码、密钥、token、支付凭据字段。
填写后展示结果并允许重新填写、拒绝或取消，最后明确提交。字段读取遇到 EOF、失败或中断时取消；当前没有填写前菜单或字段级拒绝/取消命令。多选使用逗号分隔，所以不支持枚举值内含逗号。

URL 必须是绝对 http/https URI，显示完整地址和 host，只提供 open/decline/cancel。
只有用户输入 open 后调用浏览器导航；打开失败取消，不预取、不查询元数据、不读取网页。

## MRTR-2 协议往返

- 仅为 `tools/call`、`resources/read`、`prompts/get` 开启 MRTR；其他 JSON-RPC 请求继续走现有严格 `complete` 校验。
- 每次 `input_required` 都必须验证至少存在 `inputRequests` 或 `requestState`。`inputRequests` 必须是有界对象；本阶段只接受其中全部请求均为 `elicitation/create`。先解析并验证整张 map，再逐个询问，避免先收集一部分后才发现不支持的 method。
- `inputResponses` 的 key 与本轮 `inputRequests` 的 key 一一对应；值使用 elicitation 的 accept/decline/cancel JSON。用户取消或拒绝也作为响应发回，由服务端决定后续行为。
- `requestState` 只验证它是字符串，不读取内容；只在同一次原请求的下一次重试中原样放回，不保存到会话、日志、模型上下文或其他请求。没有该字段时重试请求也不得包含它。
- 每次重试保留原 method 和原始业务 params，只新增本轮 `inputResponses` / `requestState`；每次通过 JSON-RPC 层分配新 id。新一轮的 responses 只对应新一轮的 inputRequests，不跨轮累积。
- 对单次原请求设置有限重试次数和每轮输入请求数上限；超过上限、格式错误或出现 sampling/roots/未知 method 时明确失败，不猜测、不静默跳过。
- 只有具备对应交互 UI 的客户端才在三种现代 MRTR 操作每次发送的 `_meta.io.modelcontextprotocol/clientCapabilities` 中声明 `elicitation.form` / `elicitation.url`。未接入 UI 的 benchmark、WeChat 和无人值守路径保持不声明或安全取消；Lanterna 仅在 GUI 主循环运行时声明对应能力。legacy 2025-03-26 行为完全不变。

## 官方参考

- https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/mrtr
- https://modelcontextprotocol.io/specification/2026-07-28/client/elicitation

上方为协议参考链接；本次按仓库约定实现，未访问外网、连接真实 MCP server、外部服务或浏览器进行联调。

## MRTR-2 实现边界

`McpClient` 的 tools/call、resources/read 及新增 getPrompt（prompts/get）共用往返循环。`JsonRpcClient.requestWithInputs` 只放行这三种现代操作；普通 request 仍拒绝 input_required。legacy 不触发补充输入。

最多处理 4 轮补充输入（原请求加 4 次重试）；每轮最多 8 项，inputRequests 紧凑 JSON 最多 65536 个 Java 字符。超限在该轮询问前失败。允许只有字符串 requestState 的一轮，此时不发送 inputResponses；显式空 map 对应空 inputResponses。null map、null/非字符串 state、未知 method、不合法表单/URL 均整轮失败，不询问部分请求。

响应 key 与当轮 map 完全相同；accept 表单有对象 content，accept URL、decline/cancel 无 content。默认或 null handler 响应作为 cancel 发回。每轮重建原业务参数，只加本轮 inputResponses 和存在的字符串 requestState；后者不 trim、不解析、不进入 UI、日志、会话或模型上下文。JSON 编码可转义字符，解码后的字符串逐字符相同。

## MRTR-3 动态能力声明

`McpElicitationHandler` 仍是函数接口，默认 `supportedModes()` 为空；仅存在 handler 不代表提供 UI。`HitlHandler.elicit()` 转交原 `requestElicitation()`，能力默认也为空。`Renderer.supportedElicitationModes()` 默认空。Plain 的现有表单和 URL 界面支持 form/url；Inline 按实际输入路径查询：无 terminal 或无 gateway/lineReader 时转交 Plain fallback，否则使用已有交互实现，关闭后为空。LanternaRenderer 通过独立的 LanternaElicitation bridge 提供表单/URL 模态交互，仅当 LanternaWindow 的 GUI 主循环运行且 renderer 未关闭时声明 form/url；GUI 关闭、不可用或 bridge 异常时返回空能力并安全取消。没有改变 Plain/Inline 的交互步骤。

### Lanterna 界面（MRTR-4）

Lanterna 使用独立的 `LanternaElicitation` worker bridge 复用同一 `ElicitationPrompter`：字段填写、检查/修改、提交/拒绝/取消与 URL 显示及明确打开确认保持一致。表单和 URL 使用可滚动的只读上下文区域及模态按钮；URL 打开只在用户选择“打开浏览器”后执行，不预取页面。所有窗口创建、关闭和控件访问都通过 `LanternaWindow.runOnGuiThread` 调度；同步入口拒绝 GUI 线程调用，等待有 5 分钟上限并响应中断。超时、GUI 关闭、调度失败或窗口异常均返回 cancel，不把用户输入交给模型。

Main 仅在 `System.console() != null` 时将 SwitchableHitlHandler 对象本身交给 manager；TuiBootstrap 启动 Lanterna 后将同一个 delegate 切换到 LanternaRenderer，因此 manager/client 无需重建即可在后续请求读取 GUI 当前能力。无控制台、重定向和不能可靠确认交互性的启动保持 CANCEL，不猜测支持能力。IDE 或某些终端即使能读取输入，也可能没有 Console；此时保守不声明。WeChat 使用独立 WechatToolRegistry；benchmark 的 client 默认 CANCEL，均未接入交互能力。Lanterna 只在 GUI 已运行时声明能力；本轮真实终端手工验收仍需单独完成。

manager 给新建及现有 client 传同一 handler 对象；Switchable 每次查询当前 delegate，RendererHitlHandler 查询当前 renderer。McpClient 向 JsonRpcClient 提供实时 supplier，发送每个合格请求时读取当前 handler；不在 setHandler 时缓存，查询返回 null 或抛 RuntimeException 时为空。切换 delegate 后下一次请求立即反映变化，包括 MRTR 重试。能力与 `/hitl` 审批开关无关。

仅现代 `JsonRpcClient.requestWithInputs` 的 tools/call、resources/read、prompts/get 声明；普通 request（即便 method 是这三种之一）、server/discover、所有 list、通知均不查询 UI，也不声明 elicitation。legacy 不改变参数或增加元数据。不从先前请求推断能力。

现代元数据精确形状：`params._meta["io.modelcontextprotocol/clientCapabilities"]` 为 `{"elicitation":{"form":{},"url":{}}}`；只支持一种时仅包含对应键。仅识别 form/url，忽略未知模式。保留其他 `_meta` 和调用方自定义 capability 键，复制参数而不修改原对象；每次移除旧 elicitation 再按本次能力重建，防止陈旧或调用方传入的能力误用到普通请求/通知。空模式无 elicitation；没有自定义能力时 clientCapabilities 为 `{}`。

OAuth、版本选择、传输和 MRTR 轮数/输入/状态处理语义均未改。未实现 sampling/roots。离线验证入口、分项结果及文件清单见实施计划末尾；未进行真实终端或真实服务端联调。
