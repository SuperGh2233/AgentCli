# Terminal-Bench 基线与迭代评测

状态：用户已确认（2026-11-27）；smoke 已通过；改进阶段用 Terminal-Bench 2.0 固定 30 题 × 3 次，最终在 4.1.0 上和排行榜对比；下一步跑 2.0 dev 基线

## 目标

用当前版本在 Terminal-Bench 上跑出基线，之后每加一个功能在同样条件下重跑，记录效果变化，积累调优经验。结果记在 `docs/evals/terminal-bench-ledger.md`。

## 已确认的决定

| 项 | 决定 |
|---|---|
| 评测框架 / 数据集 | Harbor（`uv tool install harbor`，当前 0.27.0）。改进阶段：`terminal-bench@2.0`（89 题）；最终对比：`terminal-bench/terminal-bench@4.1.0`（66 题，公开排行榜当前排的版本；固定写完整版本号，不用会移动的 `@4` / `@latest`） |
| 运行环境 | 本机 OrbStack（Docker），并发 2 |
| 模型 | DeepSeek V4.1 Flash，所有轮次固定不变。2026-11-28 起（用户决定）经本机 magpie 网关走 WorkBuddy 订阅里的 `workbuddy/deepseek-v4.1-flash`（`run.sh` 默认 `magpie/workbuddy/deepseek-v4.1-flash`），不再扣 DeepSeek 余额；此前的轮次是 DeepSeek 官方 API（`deepseek/deepseek-flash`），两条路线的结果不混在一起比较 |
| 规模 | 日常迭代：2.0 固定 30 题 × 3 次，本机并发 3（`run.sh dev`）；阶段抽查：4.1.0 固定 15 题 × 3 次（`run.sh dev41`）；最终对比：4.1.0 全部题 × 5 次，按排行榜规则，云端沙箱（`run.sh full`），基线版本也在同样条件下补跑 |

这次是用户明确同意的评测，`AGENTS.md` 里“评测暂停”对这条线不适用；其余评测纪律照旧。

## 和公开排行榜的可比性（2026-11-27 核对 tbench.ai）

- 排行榜排的是 Terminal-Bench 4.1（54 行）；2.0 页面读不到数据。所以一开始用的 2.0 不能和排行榜比，已停掉并改用 4.1.0
- 同模型的对照行：Claude Code + DeepSeek V4.1 Flash（快照 1017，max）**38.5% ± 3.8%**（第 23 名，8.2B Token、约 $200）；Claude Code + GLM-5.3-Flash 37.0% ± 3.6%。用别的模型的行不能拿来比软件本身
- 可比的前提（`leaderboard/SUBMIT.md`）：全部题、每题至少 5 次、出错的尝试算 0 分、题目超时和资源用默认值、数据集不改。Hao CLI 自己的上限属于 Agent 设置，可以有，但要写清楚
- 自测结果只能说“按官方规则自测”，不能说上榜；4.0 社区提交目前关闭
- 我们的 `deepseek-flash` 不带快照号，不一定和排行榜的 1017 是同一个快照

## 本机资源的限制（4.1.0 的 66 题）

- 每题 Agent 时间上限 8 小时，人类专家中位数 4 小时，题目很重
- 3 题要 GPU；28 题要 4 GB 以上内存（9 题 ≥ 16 GB），6 题要 2 核以上；本机 OrbStack 虚拟机 8 GB / 10 核
- 所以全量 × 5 次在本机跑不全（跑不起来的题会算 0 分，对 Hao CLI 不公平），对外对比要用云端沙箱（Modal 支持 GPU）；本机只做日常迭代
- 日常迭代子集：从本机能跑的 29 题（不要 GPU、内存 ≤ 4 GB、CPU ≤ 2）里按分类分层、固定种子 20261127 抽 15 题，清单 `benchmarks/harbor/subsets/tb41-dev15.txt`，脚本 `benchmarks/harbor/select_subset.py` 可复现。子集里没有 Security 类（都超出本机资源）

## 为什么改进阶段用 2.0（2026-11-27 用户决定）

| | 4.1.0 | 2.0 |
|---|---|---|
| 每题 Agent 时间上限 | 8 小时 | 中位数 15 分钟（10 分钟～3.3 小时） |
| 资源 | 28 题 > 4 GB，3 题要 GPU | 71 题 2 GB、84 题 1 核，没有 GPU |
| 本机能跑 | 29 题 | 87 题 |

- 同一系列、同一类终端任务；题轻，本机一晚能跑完一轮，适合频繁试改动
- 风险：2.0 上的提升不一定完全带到 4.1（4.1 更考验几小时的长任务）。每个阶段节点跑一次 `dev41` 抽查
- 2.0 子集：87 道本机能跑的题里按难度分层、种子 20261127 抽 30 题（简单 1、中等 19、困难 10），清单 `benchmarks/harbor/subsets/tb20-dev30.txt`
- 4.1.0 的 dev 基线跑了约 10 分钟后停掉（还没有结果），4.1.0 上的基线留到最终上云时补跑

## 做法

1. Hao CLI 新增 `--permission-mode sandbox`：读写文件、执行任意命令；PathGuard、CommandGuard、审计照常；只在容器里能启用（`/.dockerenv`、`/run/.containerenv`），宿主机上要显式设 `HAOCLI_ALLOW_SANDBOX_MODE=1`
2. `benchmarks/harbor/haocli_agent.py`：Harbor 的 `BaseInstalledAgent`。JRE 和 jar 从宿主机上传（容器里不联网下载），无界面模式 `-p ... --permission-mode sandbox --output-format stream-json`，最后一行结果里的 Token 数回填给 Harbor
3. `benchmarks/harbor/run.sh smoke|dev|dev41|full|custom`：构建当前提交的 jar、缓存 JRE、读 `.env` 里的 Key、调 `harbor run`；任务名里带提交号，工作区有改动时带 `-dirty`。`dev` = 2.0 的 30 题 × 3，`dev41` = 4.1.0 的 15 题 × 3，`full` = 4.1.0 全部题 × 5
4. 适配器对退出码的处理：Hao CLI 退出码 0（完成）和 3（撞上限，部分完成）都算 Agent 正常结束，由判分决定对错；其他退出码才报错。事件流写文件不写 stdout，因为 Harbor 会在 stdout 里扫关键字分类错误，模型执行命令输出的“Connection refused”会被误判成网络错误（2.0 试跑时发现）

## 消融实验（2026-11-27 用户决定）

- 内核 `AgentLoop` 只认 `TurnHook`（发请求前 / 工具结果之后 / 上下文超长三个挂载点）；五层按从便宜到贵依次触发：裁剪（摘要阈值 × 0.4）→ 折叠（× 0.5）→ 清理（× 0.6）→ 摘要（达到阈值）；模型报上下文超长时紧急压缩一次再重试；模块：`LspDiagnosticsHook`、`ContextCompactionHook`、`RunawayReminderHook`，大结果落盘在 `ToolRegistry` 里单独开关
- 开关 `HarnessFeatures`：`context`（上下文处理策略 = 五层压缩 + 重复调用提醒）、细分名 `context.offload`（1 大结果落盘）/ `context.trim`（2 历史裁剪）/ `context.fold`（3 局部折叠）/ `context.clearing`（4 清理旧工具结果）/ `context.summary`（5 摘要压缩）/ `context.runaway`、`lsp`。`-p --harness-disable <名字>` 或环境变量 `HAOCLI_HARNESS_DISABLE`；写错名字直接报错
- 每次运行的结果 JSON 带 `harness_disabled`，适配器写进每次尝试的元数据；`run.sh` 用 `HAOCLI_EVAL_LABEL` 给任务名加标签
- 第一批只消融一项：上下文处理策略整体关掉。跑法：`HAOCLI_EXTRA_ARGS="--harness-disable context" HAOCLI_EVAL_LABEL=no-context benchmarks/harbor/run.sh dev`，和同一提交的全开基线比
- Terminal-Bench 每题是全新容器，长期记忆、项目记忆、LSP 在这里基本是空的，消融它们测不出差别；记忆要另设跨会话的测试
- ReAct 是内核本身，不做消融；“先规划再执行”这类属于策略变体对比

## 第一组正式实验：上下文处理策略消融（2026-11-27 用户确认）

起因：全功能版本第一次正式跑（`d5fff23`，跑了约 20 分钟后停掉）发现，DeepSeek V4.1 Flash 窗口 100 万，压缩阈值约 95 万，裁剪层也要约 38 万才触发；而 50 次请求上限下对话只有几万 Token，五层压缩一次都没有触发，3 次尝试里 2 次撞了请求上限。按这个条件跑新旧版本对比，量到的只是噪声。

调整：
- 上下文预算 `HAOCLI_CONTEXT_BUDGET_TOKENS=128000`：压缩阈值按 min(模型窗口, 预算) 计算（摘要约 9.5 万、裁剪约 3.8 万、折叠约 4.7 万、清理约 5.7 万），结果 JSON 带 `context_budget_tokens`
- 运行上限放宽：`--max-turns 150 --max-tokens 10000000`（原来 50 次 / 200 万）

两轮只差一个开关，其余全同（同一提交、2.0 的 30 题 × 3 次、并发 2）：
1. 全开：`HAOCLI_CONTEXT_BUDGET_TOKENS=128000 HAOCLI_EXTRA_ARGS="--max-turns 150 --max-tokens 10000000" HAOCLI_EVAL_LABEL=ctx-on benchmarks/harbor/run.sh dev`
2. 关掉上下文处理策略：同上，`HAOCLI_EXTRA_ARGS` 里加 `--harness-disable context`，标签 `ctx-off`

两轮的差就是上下文处理策略的效果；同时看 Token 用量（压缩的另一个目的是省钱）。

## 评测纪律

- 每次对比只改一件事；提交号、模型、数据集版本、并发、超时、Hao CLI 的上限参数都要和基线一致，有变化写进台账
- 每题跑 3 次（`-k 3`），所有尝试都计入；不开自动重试（`-r`），不挑最好的一次，不挑题
- 日常迭代一律用 `subsets/tb20-dev30.txt`，阶段抽查用 `subsets/tb41-dev15.txt`，不换题
- `-dirty` 的结果只能当探索，不能当正式结果
- 通过率差异小于噪声（12 题 × 3 次时大约 ±10 个百分点）不下结论，要么扩大样本，要么看逐题变化

## 基线参数（当前版本）

- Hao CLI 无人值守默认上限：50 次模型请求、200 万 Token、30 分钟（`--max-turns` / `--max-tokens` / `--timeout` 可改，改了算一次实验）
- 不开 MCP（无界面模式本来就不启动 MCP）

## 2026-11-28 补的可观测性（`bb423ae`）

- `ctx-on`（`38bdb3b`）跑到 80/90 时发现：11-27 22:10 起 41 次尝试全部是 DeepSeek 网络中断（TLS 握手被对端断开）和 `402 Insufficient Balance`，这一轮作废，不计入对比；产物保留在 `benchmarks/harbor/jobs/haocli-38bdb3b-dev-ctx-on-20261127-184323`
- 结果 JSON 新增 `reason`（失败分类 `llm_transient` / `llm_billing` / …，部分完成原因）和 `context`（五层各自的触发次数、处理条数、紧急压缩次数、估算节省的 Token），stream-json 新增 `context_compacted` 事件，见 `docs/specs/headless-mode.md`
- TLS 握手被对端断开现在会重试（以前所有 SSL 异常都不重试）；`-p` 输出固定 UTF-8
- 重跑基线和消融要用包含这些改动的提交，并先确认 DeepSeek 余额；摘要压缩本身的 Token 消耗还没统计

## 2026-11-28 改走 magpie（WorkBuddy）

- 起因：之前各轮共用掉 DeepSeek 官方 API 约 1.2 亿输入 Token（约 97% 命中缓存）和约 270 万输出 Token，余额耗尽；用户要求评测改走 magpie 转发的 WorkBuddy `deepseek-v4.1-flash`
- 做法：无界面模式新增 `HAOCLI_PROVIDER`（只用指定的服务商，不可用直接报错、不回退）；适配器只把 `-m` 指定服务商的 Key 传进容器；`run.sh` 默认模型改为 `magpie/workbuddy/deepseek-v4.1-flash`，开跑前检查网关在线并列出该模型，容器经 `host.docker.internal:3425` 访问本机网关
- 验证：smoke（hello-world × 1）通过，结果记录里 `provider=magpie`、`model=workbuddy/deepseek-v4.1-flash`，3 次请求、输入 15,338（缓存 12,800）/ 输出 126
- 可比性：同一个模型名，但经第三方网关和订阅通道，快照、限流、缓存和用量统计可能和官方 API 不同；magpie 路线的结果只和 magpie 路线比，和排行榜比时要写明
- 注意：`.env.example` 提醒过“经第三方网关使用订阅额度是否符合服务条款请自行确认”，这一点仍由用户自己确认
- 整轮还没有总 Token 上限（每次尝试 1000 万 × 90 次），大规模跑之前先定总预算

## 已知风险

- Apple Silicon 上 x86 镜像走模拟，慢；有的题可能因此超时，要和“模型做不出来”分开看（看 Harbor 的异常类型和耗时）
- 少数镜像是 musl（Alpine），Temurin 的 glibc JRE 跑不起来；遇到再加 Alpine 版 JRE
- Hao CLI 的 CommandGuard 可能拦下题目需要的命令（比如 `sudo`、`rm -rf`），这本身就是要观察的基线行为，在逐题记录里标出来
