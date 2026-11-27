#!/usr/bin/env bash
# Run Hao CLI on Terminal-Bench through Harbor.
#
#   benchmarks/harbor/run.sh smoke                 # hello-world task, checks the pipeline
#   benchmarks/harbor/run.sh dev                   # daily loop: Terminal-Bench 2.0, fixed 30 tasks x 3 attempts
#   benchmarks/harbor/run.sh dev41                 # spot check: Terminal-Bench 4.1.0, fixed 15 tasks x 3 attempts
#   benchmarks/harbor/run.sh full                  # final comparison: 4.1.0, all tasks x 5 attempts (leaderboard rules)
#   benchmarks/harbor/run.sh custom <harbor args>  # anything else, e.g. -d terminal-bench@2.0 -t <task> -k 1
#
# Env: DEEPSEEK_API_KEY (read from .env if not set), HAOCLI_EVAL_MODEL
# (default deepseek/deepseek-flash), HAOCLI_EVAL_CONCURRENCY (per-mode default),
# HAOCLI_EXTRA_ARGS (extra Hao CLI flags, counts as an experiment), HTTPS_PROXY
# for host downloads.
# Never pass timeout/resource overrides to dev, dev41 or full runs: results would
# no longer be comparable with earlier rounds or the leaderboard.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/benchmarks/harbor"
CACHE="${HAOCLI_EVAL_CACHE:-$HOME/.cache/haocli-harbor}"
MODEL="${HAOCLI_EVAL_MODEL:-deepseek/deepseek-flash}"
JOBS_DIR="${HAOCLI_EVAL_JOBS_DIR:-$ROOT/benchmarks/harbor/jobs}"
TB20="terminal-bench@2.0"
TB41="terminal-bench/terminal-bench@4.1.0"
export PATH="$HOME/.local/bin:$HOME/.orbstack/bin:/opt/homebrew/bin:$PATH"

mode="${1:-}"
[ -n "$mode" ] || { sed -n '2,16p' "$0"; exit 2; }
shift || true

case "$mode" in
  smoke)  concurrency=1 ;;
  dev)    concurrency=2 ;;
  dev41)  concurrency=2 ;;
  full)   concurrency=2 ;;
  custom) concurrency=2 ;;
  *) echo "unknown mode: $mode" >&2; exit 2 ;;
esac
CONCURRENCY="${HAOCLI_EVAL_CONCURRENCY:-$concurrency}"

command -v harbor >/dev/null || { echo "harbor not found: uv tool install --python 3.12 harbor" >&2; exit 2; }
command -v docker >/dev/null || { echo "docker not found: install and start OrbStack" >&2; exit 2; }
docker info >/dev/null 2>&1 || { echo "docker is not running: start OrbStack" >&2; exit 2; }

if [ -z "${DEEPSEEK_API_KEY:-}" ] && [ -f "$ROOT/.env" ]; then
  DEEPSEEK_API_KEY="$(grep -E '^DEEPSEEK_API_KEY=' "$ROOT/.env" | tail -1 | cut -d= -f2- | tr -d '"'"'"'')"
fi
[ -n "${DEEPSEEK_API_KEY:-}" ] || { echo "DEEPSEEK_API_KEY is not set (add it to .env)" >&2; exit 2; }
export DEEPSEEK_API_KEY

# Linux JREs for the task containers (downloaded once on the host).
mkdir -p "$CACHE"
for arch in x64 aarch64; do
  tarball="$CACHE/temurin-21-jre-linux-$arch.tar.gz"
  if [ ! -s "$tarball" ]; then
    echo "downloading Temurin 21 JRE ($arch)..."
    curl -fL --retry 3 -o "$tarball.part" \
      "https://api.adoptium.net/v3/binary/latest/21/ga/linux/$arch/jre/hotspot/normal/eclipse"
    mv "$tarball.part" "$tarball"
  fi
done
export HAOCLI_JRE_X64="$CACHE/temurin-21-jre-linux-x64.tar.gz"
export HAOCLI_JRE_AARCH64="$CACHE/temurin-21-jre-linux-aarch64.tar.gz"

# Build the jar from the current commit and record what is being measured.
( cd "$ROOT" && JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}" mvn -q -B package -DskipTests )
export HAOCLI_JAR="$ROOT/target/haocli-1.0-SNAPSHOT.jar"
commit="$(git -C "$ROOT" rev-parse --short HEAD)"
dirty="$(git -C "$ROOT" status --porcelain --untracked-files=no | grep -q . && echo "-dirty" || true)"
job="haocli-${commit}${dirty}-$mode-$(date +%Y%m%d-%H%M%S)"
echo "commit=$commit$dirty model=$MODEL mode=$mode concurrency=$CONCURRENCY extra_args='${HAOCLI_EXTRA_ARGS:-}' job=$job"

export PYTHONPATH="$HERE${PYTHONPATH:+:$PYTHONPATH}"
common=(-a haocli_agent:HaoCliAgent -m "$MODEL" -n "$CONCURRENCY" -o "$JOBS_DIR" --job-name "$job")

# Pull the subset's images first so Harbor's 10-minute environment start never
# includes a large image pull (timeouts there leak containers and cascade).
prepull() {
  local dataset="$1" dir="$2" subset="$3" py
  py="$(ls "$HOME"/.local/share/uv/tools/harbor/bin/python)"
  if [ ! -d "$dir" ]; then
    harbor datasets download "$dataset" -o "$(dirname "$dir")"
  fi
  "$py" "$HERE/prepull.py" "$dir" "$subset"
}

# Task names are plain in 2.0 and org-prefixed in 4.x; match both.
subset_args() {
  local file="$1" task
  includes=()
  while IFS= read -r task; do
    case "$task" in ''|'#'*) continue ;; esac
    includes+=(-i "$task" -i "*/$task")
  done < "$file"
  echo "subset: $file ($(( ${#includes[@]} / 4 )) tasks)"
}

case "$mode" in
  smoke)  harbor run -t hello-world/hello-world -k 1 "${common[@]}" "$@" ;;
  dev)    prepull "$TB20" "$CACHE/datasets-tb20/terminal-bench" "$HERE/subsets/tb20-dev30.txt"
          subset_args "$HERE/subsets/tb20-dev30.txt"
          harbor run -d "$TB20" "${includes[@]}" -k 3 "${common[@]}" "$@" ;;
  dev41)  prepull "$TB41" "$CACHE/datasets/terminal-bench" "$HERE/subsets/tb41-dev15.txt"
          subset_args "$HERE/subsets/tb41-dev15.txt"
          harbor run -d "$TB41" "${includes[@]}" -k 3 "${common[@]}" "$@" ;;
  full)   harbor run -d "$TB41" -k 5 "${common[@]}" "$@" ;;
  custom) harbor run "${common[@]}" "$@" ;;
esac
