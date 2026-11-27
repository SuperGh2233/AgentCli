#!/usr/bin/env bash
# Rerun the 53 attempts of haocli-38bdb3b-dev-ctx-on-20261127-184323 that died on
# DeepSeek network drops / 402, on the same harness (38bdb3b) through magpie.
# The original job directory is left untouched; each batch is a new Harbor job.
# Same conditions as the original run: budget 128k, 150 requests / 10M tokens,
# concurrency 2, no retries. Every rerun attempt counts.
# See docs/evals/baselines/2026-11-27-38bdb3b-ctx-on.md on main.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
export HAOCLI_EVAL_JOBS_DIR="${HAOCLI_EVAL_JOBS_DIR:-/Users/a123/Projects/Haocli/benchmarks/harbor/jobs}"
export HAOCLI_CONTEXT_BUDGET_TOKENS=128000
export HAOCLI_EXTRA_ARGS="--max-turns 150 --max-tokens 10000000"
unset HTTPS_PROXY HTTP_PROXY https_proxy http_proxy

K1=(build-pmars build-pov-ray code-from-image crack-7z-hash custom-memory-heap-crash db-wal-recovery
    distribution-search feal-differential-cryptanalysis git-leak-recovery)
K2=(headless-terminal hf-model-inference large-scale-text-editing log-summary-date-ranges make-doom-for-mips
    model-extraction-relu-logits mteb-leaderboard openssl-selfsigned-cert password-recovery polyglot-c-py
    polyglot-rust-c protein-assembly prove-plus-comm qemu-startup raman-fitting regex-chess regex-log
    torch-tensor-parallelism write-compressor)
K3=(sam-cell-seg tune-mjcf)

batch() {
  local k="$1"; shift
  local args=()
  for t in "$@"; do args+=(-i "$t" -i "*/$t"); done
  HAOCLI_EVAL_LABEL="ctx-on-rerun-k$k" "$HERE/run.sh" custom -d terminal-bench@2.0 "${args[@]}" -k "$k"
}

batch 1 "${K1[@]}"
batch 2 "${K2[@]}"
batch 3 "${K3[@]}"
