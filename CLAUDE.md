# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

## Claude Code 补充

- 本仓库规则以 `AGENTS.md` 为准（上面已导入），这里只放 Claude Code 专属说明，保持简短
- 改 Plan 失败处理、重规划或 `parsePlan` 前，先看 `src/main/resources/benchmark/e1_replay.py` 的对应约束
- 做分阶段的功能前，先找对应的计划和规格：`docs/plans/active/PLAN-*.md`（还在进行的计划）、`docs/specs/`（协议和行为规格）、`docs/*-implementation-plan.md`（比如执行中动态指令）。计划里写的“已验证 / 未接线”状态以代码为准
