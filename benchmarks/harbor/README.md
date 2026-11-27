# 用 Harbor 跑 Terminal-Bench

一次性准备：

```bash
brew install --cask orbstack        # 装好后打开一次 OrbStack
brew install uv
uv tool install --python 3.12 harbor
```

模型默认走本机 magpie 网关转发的 WorkBuddy `deepseek-v4.1-flash`（`HAOCLI_EVAL_MODEL=magpie/workbuddy/deepseek-v4.1-flash`），用的是 WorkBuddy 订阅额度，不扣 DeepSeek 余额：

- 先启动 magpie，确认 `curl http://127.0.0.1:3425/v1/models` 里有 `workbuddy/deepseek-v4.1-flash`（`run.sh` 开跑前会检查）
- 容器通过 `host.docker.internal:3425` 访问 Mac 本机的网关（OrbStack 会转到本机 localhost），可用 `MAGPIE_BASE_URL` 改
- 适配器只把 `-m` 指定的那个服务商的 Key 传进容器，并设 `HAOCLI_PROVIDER`；指定的服务商不可用时 Hao CLI 直接报错，不会改走别的服务商
- 想改用 DeepSeek 官方 API：`HAOCLI_EVAL_MODEL=deepseek/deepseek-flash`，并在 `.env` 里写 `DEEPSEEK_API_KEY`（不要提交 `.env`）。两条路线的结果不要混在一起比较

运行（下载 JRE 时如果需要代理，先 `export HTTPS_PROXY=http://127.0.0.1:7897`）：

```bash
benchmarks/harbor/run.sh smoke     # hello-world，验证整条链路
benchmarks/harbor/run.sh dev       # 日常迭代：Terminal-Bench 2.0 固定 30 题 × 3 次
benchmarks/harbor/run.sh dev41     # 阶段抽查：Terminal-Bench 4.1.0 固定 15 题 × 3 次
benchmarks/harbor/run.sh full      # 最终对比：4.1.0 全部题 × 5 次（排行榜规则，建议云端沙箱）
benchmarks/harbor/run.sh custom -d terminal-bench@2.0 -i <task> -k 1   # 单题调试
harbor view benchmarks/harbor/jobs # 浏览结果：每次尝试的得分、Token、日志
```

结果目录 `benchmarks/harbor/jobs/` 不提交；每轮的结论写进 `docs/evals/terminal-bench-ledger.md`。

每次尝试的 Hao CLI 输出在 `jobs/<job>/<trial>/agent/haocli.jsonl`（事件流，最后一行是结果和 Token 数），错误输出在 `haocli.stderr.txt`。
