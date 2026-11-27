# Terminal-Bench 评测台账

每跑一轮加一行；条件和基线不同的地方写在“改动”里。做法和纪律见 `docs/plans/active/PLAN-20261127-terminal-bench-eval.md`。

固定条件：Harbor 0.27.0 · `terminal-bench@2.0` · `deepseek/deepseek-flash` · 本机 OrbStack · 并发 2 · 每题 3 次 · 不重试

## 轮次

| 日期 | 提交 | 规模 | 改动（相对上一轮） | 通过率 | 平均 Token / 尝试 | 平均耗时 / 尝试 | 备注 |
|---|---|---|---|---|---|---|---|
| 2026-11-27 | `a1175b6` | smoke（hello-world × 1） | 基线，链路验证 | 1/1 | 输入 15.4k（缓存 10.0k）/ 输出 143 | 2.2 秒（含装环境共 2 分 42 秒） | 3 次模型请求，read_file ×2、write_file ×2 |

## 小样本题目清单

（第一次 sample 后填写，之后不变）

## 逐题变化

（每轮和上一轮对比：哪些题从失败变成通过、哪些反过来，以及看日志得出的原因）
