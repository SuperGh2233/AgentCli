# Terminal-Bench 基线与迭代评测

状态：用户已确认（2026-11-27）；smoke 已通过；数据集改为排行榜用的 `terminal-bench/terminal-bench@4.1.0`，下一步跑 dev 基线

## 目标

用当前版本在 Terminal-Bench 上跑出基线，之后每加一个功能在同样条件下重跑，记录效果变化，积累调优经验。结果记在 `docs/evals/terminal-bench-ledger.md`。

## 已确认的决定

| 项 | 决定 |
|---|---|
| 评测框架 / 数据集 | Harbor（`uv tool install harbor`，当前 0.27.0）跑 Terminal-Bench `terminal-bench/terminal-bench@4.1.0`（66 题，公开排行榜当前排的版本；固定写完整版本号，不用会移动的 `@4` / `@latest`） |
| 运行环境 | 本机 OrbStack（Docker），并发 2 |
| 模型 | DeepSeek V4.1 Flash（`deepseek/deepseek-flash`），所有轮次固定不变 |
| 规模 | 两层：日常迭代用固定子集 15 题 × 3 次（本机）；对外对比用全部题 × 5 次（按排行榜规则，建议云端沙箱） |

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

## 做法

1. Hao CLI 新增 `--permission-mode sandbox`：读写文件、执行任意命令；PathGuard、CommandGuard、审计照常；只在容器里能启用（`/.dockerenv`、`/run/.containerenv`），宿主机上要显式设 `HAOCLI_ALLOW_SANDBOX_MODE=1`
2. `benchmarks/harbor/haocli_agent.py`：Harbor 的 `BaseInstalledAgent`。JRE 和 jar 从宿主机上传（容器里不联网下载），无界面模式 `-p ... --permission-mode sandbox --output-format stream-json`，最后一行结果里的 Token 数回填给 Harbor
3. `benchmarks/harbor/run.sh smoke|dev|full|custom`：构建当前提交的 jar、缓存 JRE、读 `.env` 里的 Key、调 `harbor run`；任务名里带提交号，工作区有改动时带 `-dirty`。`dev` = 固定子集 × 3，`full` = 全部题 × 5
4. 适配器对退出码的处理：Hao CLI 退出码 0（完成）和 3（撞上限，部分完成）都算 Agent 正常结束，由判分决定对错；其他退出码才报错。事件流写文件不写 stdout，因为 Harbor 会在 stdout 里扫关键字分类错误，模型执行命令输出的“Connection refused”会被误判成网络错误（2.0 试跑时发现）

## 评测纪律

- 每次对比只改一件事；提交号、模型、数据集版本、并发、超时、Hao CLI 的上限参数都要和基线一致，有变化写进台账
- 每题跑 3 次（`-k 3`），所有尝试都计入；不开自动重试（`-r`），不挑最好的一次，不挑题
- 日常迭代一律用 `subsets/tb41-dev15.txt`，不换题
- `-dirty` 的结果只能当探索，不能当正式结果
- 通过率差异小于噪声（12 题 × 3 次时大约 ±10 个百分点）不下结论，要么扩大样本，要么看逐题变化

## 基线参数（当前版本）

- Hao CLI 无人值守默认上限：50 次模型请求、200 万 Token、30 分钟（`--max-turns` / `--max-tokens` / `--timeout` 可改，改了算一次实验）
- 不开 MCP（无界面模式本来就不启动 MCP）

## 已知风险

- Apple Silicon 上 x86 镜像走模拟，慢；有的题可能因此超时，要和“模型做不出来”分开看（看 Harbor 的异常类型和耗时）
- 少数镜像是 musl（Alpine），Temurin 的 glibc JRE 跑不起来；遇到再加 Alpine 版 JRE
- Hao CLI 的 CommandGuard 可能拦下题目需要的命令（比如 `sudo`、`rm -rf`），这本身就是要观察的基线行为，在逐题记录里标出来
