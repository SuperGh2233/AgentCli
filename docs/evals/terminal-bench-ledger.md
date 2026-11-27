# Terminal-Bench 评测台账

每跑一轮加一行；条件和基线不同的地方写在“改动”里。做法和纪律见 `docs/plans/active/PLAN-20261127-terminal-bench-eval.md`。

固定条件：Harbor 0.27.0 · `terminal-bench/terminal-bench@4.1.0` · `deepseek/deepseek-flash` · 本机 OrbStack（8 GB / 10 核）· 并发 2 · dev 子集每题 3 次 · 不重试

公开对照（tbench.ai，Terminal-Bench 4.1，全部题 × 5 次）：Claude Code + DeepSeek V4.1 Flash **38.5% ± 3.8%**；Claude Code + GLM-5.3-Flash 37.0% ± 3.6%。dev 子集的数不能直接和它比，只有 full 才能比

## 轮次

| 日期 | 提交 | 规模 | 改动（相对上一轮） | 通过率 | 平均 Token / 尝试 | 平均耗时 / 尝试 | 备注 |
|---|---|---|---|---|---|---|---|
| 2026-11-27 | `a1175b6` | smoke（hello-world × 1） | 基线，链路验证 | 1/1 | 输入 15.4k（缓存 10.0k）/ 输出 143 | 2.2 秒（含装环境共 2 分 42 秒） | 3 次模型请求，read_file ×2、write_file ×2 |

## dev 子集题目清单

`benchmarks/harbor/subsets/tb41-dev15.txt`（15 题，种子 20261127，按分类分层，只含本机能跑的题）：batched-eval-parity、cargo-flight-dispatch、embedding-drift-monitor、freecad-platform-drawing、glycan-ms2-elucidation、heat-pump-warranty、kv-live-surgery、music-harmony、photonic-waveguide-routing、react-lead-form、retro-console-soc、roy-polymorph-cn、session-window-debug、sound-change-cascade、wal-recovery-ordering

## 探索记录（不计入对比）

2026-11-27 `9e02fc1` 在 2.0 上试跑 12 题 × 3 次，约 30 分钟后因版本不对停掉，完成 10 次尝试：
- 通过 4（bn-fit-modify、build-pmars、cancel-async-tasks、chess-best-move）
- 撞 50 次请求上限 4 次、撞 200 万 Token 上限 1 次：Hao CLI 自己的上限是最明显的瓶颈，适合作为第一个调优实验
- 环境启动超时 2 次（镜像拉取 / 启动慢，基础设施问题），Agent 超时 1 次
- 发现适配器问题：退出码 3 被 Harbor 当成出错、stdout 里的命令输出被误判成网络错误；已修

## 逐题变化

（每轮和上一轮对比：哪些题从失败变成通过、哪些反过来，以及看日志得出的原因）
