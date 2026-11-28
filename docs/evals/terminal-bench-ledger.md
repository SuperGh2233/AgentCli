# Terminal-Bench 评测台账

每跑一轮加一行；条件和基线不同的地方写在“改动”里。做法和纪律见 `docs/plans/active/PLAN-20261127-terminal-bench-eval.md`。

固定条件：Harbor 0.27.0 · 本机 OrbStack（8 GB / 10 核）· 不重试 · 模型 DeepSeek V4.1 Flash：2026-11-27 各轮走 DeepSeek 官方 API（`deepseek/deepseek-flash`），2026-11-28 起走 magpie → WorkBuddy（`magpie/workbuddy/deepseek-v4.1-flash`），两条路线分开比较
- `dev`：`terminal-bench@2.0`，`subsets/tb20-dev30.txt`（30 题）× 3 次，并发 3
- `dev41`：`terminal-bench/terminal-bench@4.1.0`，`subsets/tb41-dev15.txt`（15 题）× 3 次，并发 2
- `full`：4.1.0 全部 66 题 × 5 次，排行榜规则

公开对照（tbench.ai，Terminal-Bench 4.1，全部题 × 5 次）：Claude Code + DeepSeek V4.1 Flash **38.5% ± 3.8%**；Claude Code + GLM-5.3-Flash 37.0% ± 3.6%。dev 子集的数不能直接和它比，只有 full 才能比

## 轮次

| 日期 | 提交 | 规模 | 改动（相对上一轮） | 通过率 | 平均 Token / 尝试 | 平均耗时 / 尝试 | 备注 |
|---|---|---|---|---|---|---|---|
| 2026-11-27 | `a1175b6` | smoke（hello-world × 1） | 基线，链路验证 | 1/1 | 输入 15.4k（缓存 10.0k）/ 输出 143 | 2.2 秒（含装环境共 2 分 42 秒） | 3 次模型请求，read_file ×2、write_file ×2 |
| 2026-11-27 | `38bdb3b` | dev ctx-on（30 题 × 3） | 上下文预算 128k、上限 150 次 / 1000 万 | **阶段结果 47/90（52.2%）**，混合路线：37 次 DeepSeek 官方 + 53 次 magpie 补跑 | 有效尝试平均输入 2.1M（97% 缓存）/ 输出 43k | — | 22:10 起 53 次尝试死于 DeepSeek 网络中断和 402 余额不足，2026-11-28 在 `eval/38bdb3b-magpie`（`9898e88`）上经 magpie 补跑完；magpie 每次请求慢约 60%，超时明显变多；当时架构和逐题结果见 [基线记录](baselines/2026-11-27-38bdb3b-ctx-on.md)，标签 `tb-baseline-38bdb3b` |
| 2026-11-28 | `f82e0e5-dirty`（magpie 路由改动未提交时） | smoke（hello-world × 1） | 改走 magpie → WorkBuddy `deepseek-v4.1-flash` | 1/1 | 输入 15.3k（缓存 12.8k）/ 输出 126 | 整轮 59 秒 | 链路验证，`provider=magpie`；dirty，只算探索 |

### 2026-11-28 单题验证（探索，不计入对比）

| 提交 | 题目 | 结果 | 说明 |
|---|---|---|---|
| `bffe18a` | regex-chess × 1（magpie） | 0（部分完成，`time_limit_exceeded`） | 验证单次请求不再限制总时长：最大单次输出 90,732 Token 正常完成，没有模型调用超时；66 次请求、输入 2.13M（缓存 2.02M）/ 输出 0.30M、约 31 分钟后撞上无人值守默认 30 分钟上限 |

## 子集题目清单

`benchmarks/harbor/subsets/tb20-dev30.txt`（30 题，种子 20261127，按难度分层：简单 1、中等 19、困难 10）：build-pmars、build-pov-ray、code-from-image、crack-7z-hash、custom-memory-heap-crash、db-wal-recovery、distribution-search、feal-differential-cryptanalysis、git-leak-recovery、headless-terminal、hf-model-inference、large-scale-text-editing、log-summary-date-ranges、make-doom-for-mips、model-extraction-relu-logits、mteb-leaderboard、openssl-selfsigned-cert、password-recovery、polyglot-c-py、polyglot-rust-c、protein-assembly、prove-plus-comm、qemu-startup、raman-fitting、regex-chess、regex-log、sam-cell-seg、torch-tensor-parallelism、tune-mjcf、write-compressor

`benchmarks/harbor/subsets/tb41-dev15.txt`（15 题，种子 20261127，按分类分层，只含本机能跑的题）：batched-eval-parity、cargo-flight-dispatch、embedding-drift-monitor、freecad-platform-drawing、glycan-ms2-elucidation、heat-pump-warranty、kv-live-surgery、music-harmony、photonic-waveguide-routing、react-lead-form、retro-console-soc、roy-polymorph-cn、session-window-debug、sound-change-cascade、wal-recovery-ordering

## 探索记录（不计入对比）

2026-11-27 `9e02fc1` 在 2.0 上试跑 12 题 × 3 次，约 30 分钟后因版本不对停掉，完成 10 次尝试：
- 通过 4（bn-fit-modify、build-pmars、cancel-async-tasks、chess-best-move）
- 撞 50 次请求上限 4 次、撞 200 万 Token 上限 1 次：Hao CLI 自己的上限是最明显的瓶颈，适合作为第一个调优实验
- 环境启动超时 2 次（镜像拉取 / 启动慢，基础设施问题），Agent 超时 1 次
- 发现适配器问题：退出码 3 被 Harbor 当成出错、stdout 里的命令输出被误判成网络错误；已修

## 逐题变化

（每轮和上一轮对比：哪些题从失败变成通过、哪些反过来，以及看日志得出的原因）
