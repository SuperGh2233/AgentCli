#!/usr/bin/env bash
# Run Hao CLI on a Harbor dataset (Terminal-Bench by default).
#
#   benchmarks/harbor/run.sh smoke                 # hello-world task, checks the pipeline
#   benchmarks/harbor/run.sh dev                   # fixed dev subset (subsets/tb41-dev15.txt) x 3 attempts
#   benchmarks/harbor/run.sh full                  # whole dataset x 5 attempts, leaderboard rules
#   benchmarks/harbor/run.sh custom <harbor args>  # anything else, e.g. -t org/task -k 1
#
# Env: DEEPSEEK_API_KEY (read from .env if not set), HAOCLI_EVAL_MODEL
# (default deepseek/deepseek-flash), HAOCLI_EVAL_DATASET (default
# terminal-bench/terminal-bench@4.1.0, the version on the public leaderboard),
# HAOCLI_EVAL_SUBSET (default subsets/tb41-dev15.txt), HAOCLI_EVAL_CONCURRENCY
# (default 2), HTTPS_PROXY for downloads on the host.
# Never pass timeout/resource overrides to dev or full runs: results would no
# longer be comparable with the baseline or the leaderboard.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/benchmarks/harbor"
CACHE="${HAOCLI_EVAL_CACHE:-$HOME/.cache/haocli-harbor}"
MODEL="${HAOCLI_EVAL_MODEL:-deepseek/deepseek-flash}"
DATASET="${HAOCLI_EVAL_DATASET:-terminal-bench/terminal-bench@4.1.0}"
SUBSET="${HAOCLI_EVAL_SUBSET:-$HERE/subsets/tb41-dev15.txt}"
CONCURRENCY="${HAOCLI_EVAL_CONCURRENCY:-2}"
JOBS_DIR="${HAOCLI_EVAL_JOBS_DIR:-$ROOT/benchmarks/harbor/jobs}"
export PATH="$HOME/.local/bin:$HOME/.orbstack/bin:/opt/homebrew/bin:$PATH"

mode="${1:-}"
[ -n "$mode" ] || { sed -n '2,15p' "$0"; exit 2; }
shift || true

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
echo "commit=$commit$dirty model=$MODEL dataset=$DATASET job=$job"

export PYTHONPATH="$HERE${PYTHONPATH:+:$PYTHONPATH}"
common=(-a haocli_agent:HaoCliAgent -m "$MODEL" -n "$CONCURRENCY" -o "$JOBS_DIR" --job-name "$job")

case "$mode" in
  smoke)  harbor run -t hello-world/hello-world -k 1 "${common[@]}" "$@" ;;
  dev)
    includes=()
    while IFS= read -r task; do
      case "$task" in ''|'#'*) continue ;; esac
      includes+=(-i "$task")
    done < "$SUBSET"
    echo "dev subset: $SUBSET ($(( ${#includes[@]} / 2 )) tasks)"
    harbor run -d "$DATASET" "${includes[@]}" -k 3 "${common[@]}" "$@" ;;
  full)   harbor run -d "$DATASET" -k 5 "${common[@]}" "$@" ;;
  custom) harbor run "${common[@]}" "$@" ;;
  *) echo "unknown mode: $mode" >&2; exit 2 ;;
esac
