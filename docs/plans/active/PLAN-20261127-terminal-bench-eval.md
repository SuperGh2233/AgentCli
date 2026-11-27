# Terminal-Bench 基线与迭代评测

状态：用户已确认（2026-11-27）；适配器和 sandbox 权限模式已写，等 OrbStack 装好、`DEEPSEEK_API_KEY` 写进 `.env` 后跑 smoke

## 目标

用当前版本在 Terminal-Bench 上跑出基线，之后每加一个功能在同样条件下重跑，记录效果变化，积累调优经验。结果记在 `docs/evals/terminal-bench-ledger.md`。

## 已确认的决定

| 项 | 决定 |
|---|---|
| 评测框架 / 数据集 | Harbor（`uv tool install harbor`，当前 0.27.0）跑 Terminal-Bench（`terminal-bench@2.0`） |
| 运行环境 | 本机 OrbStack（Docker），并发 2 |
| 模型 | DeepSeek V4.1 Flash（`deepseek/deepseek-flash`），所有轮次固定不变 |
| 第一轮规模 | 先 smoke（hello-world），再 12 题 × 3 次估算成本，再决定是否跑全量 |

这次是用户明确同意的评测，`AGENTS.md` 里“评测暂停”对这条线不适用；其余评测纪律照旧。

## 做法

1. Hao CLI 新增 `--permission-mode sandbox`：读写文件、执行任意命令；PathGuard、CommandGuard、审计照常；只在容器里能启用（`/.dockerenv`、`/run/.containerenv`），宿主机上要显式设 `HAOCLI_ALLOW_SANDBOX_MODE=1`
2. `benchmarks/harbor/haocli_agent.py`：Harbor 的 `BaseInstalledAgent`。JRE 和 jar 从宿主机上传（容器里不联网下载），无界面模式 `-p ... --permission-mode sandbox --output-format stream-json`，最后一行结果里的 Token 数回填给 Harbor
3. `benchmarks/harbor/run.sh smoke|sample|full|custom`：构建当前提交的 jar、缓存 JRE、读 `.env` 里的 Key、调 `harbor run`；任务名里带提交号，工作区有改动时带 `-dirty`

## 评测纪律

- 每次对比只改一件事；提交号、模型、数据集版本、并发、超时、Hao CLI 的上限参数都要和基线一致，有变化写进台账
- 每题跑 3 次（`-k 3`），所有尝试都计入；不开自动重试（`-r`），不挑最好的一次，不挑题
- 小样本的题目清单在第一次 sample 后固定下来（写进台账），之后都用同一份清单
- `-dirty` 的结果只能当探索，不能当正式结果
- 通过率差异小于噪声（12 题 × 3 次时大约 ±10 个百分点）不下结论，要么扩大样本，要么看逐题变化

## 基线参数（当前版本）

- Hao CLI 无人值守默认上限：50 次模型请求、200 万 Token、30 分钟（`--max-turns` / `--max-tokens` / `--timeout` 可改，改了算一次实验）
- 不开 MCP（无界面模式本来就不启动 MCP）

## 已知风险

- Apple Silicon 上 x86 镜像走模拟，慢；有的题可能因此超时，要和“模型做不出来”分开看（看 Harbor 的异常类型和耗时）
- 少数镜像是 musl（Alpine），Temurin 的 glibc JRE 跑不起来；遇到再加 Alpine 版 JRE
- Hao CLI 的 CommandGuard 可能拦下题目需要的命令（比如 `sudo`、`rm -rf`），这本身就是要观察的基线行为，在逐题记录里标出来
