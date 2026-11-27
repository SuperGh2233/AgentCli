# 用 Harbor 跑 Terminal-Bench

一次性准备：

```bash
brew install --cask orbstack        # 装好后打开一次 OrbStack
brew install uv
uv tool install --python 3.12 harbor
echo 'DEEPSEEK_API_KEY=...' >> .env  # 不要提交 .env
```

运行（下载 JRE 时如果需要代理，先 `export HTTPS_PROXY=http://127.0.0.1:7897`）：

```bash
benchmarks/harbor/run.sh smoke     # hello-world，验证整条链路
benchmarks/harbor/run.sh sample    # Terminal-Bench 前 12 题 × 3 次
benchmarks/harbor/run.sh full      # 全量 × 3 次
benchmarks/harbor/run.sh custom -t terminal-bench/<task> -k 1   # 单题调试
harbor view benchmarks/harbor/jobs # 浏览结果：每次尝试的得分、Token、日志
```

结果目录 `benchmarks/harbor/jobs/` 不提交；每轮的结论写进 `docs/evals/terminal-bench-ledger.md`。

每次尝试的 Hao CLI 输出在 `jobs/<job>/<trial>/agent/haocli.jsonl`（事件流，最后一行是结果和 Token 数），错误输出在 `haocli.stderr.txt`。
