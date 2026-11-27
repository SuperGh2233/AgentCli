"""Pick a fixed, reproducible dev subset of a downloaded Harbor dataset.

Only tasks that fit a local OrbStack VM are eligible (no GPU, <= 4 GB memory,
<= 2 CPUs by default). Tasks are stratified (by metadata.difficulty or
metadata.category) with largest-remainder allocation and drawn with a fixed
seed, so the list never depends on which tasks look easy.

    <harbor python> benchmarks/harbor/select_subset.py <dataset_dir> [--n 15] [--seed 20261127] [--strata category]
"""

import argparse
import collections
import glob
import os
import random
import tomllib


def memory_mb(env: dict) -> int:
    if env.get("memory_mb"):
        return int(env["memory_mb"])
    raw = str(env.get("memory") or "").strip().upper()
    if not raw:
        return 0
    for suffix, factor in (("GB", 1024), ("G", 1024), ("MB", 1), ("M", 1)):
        if raw.endswith(suffix):
            return int(float(raw[: -len(suffix)]) * factor)
    return int(float(raw))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset_dir")
    parser.add_argument("--n", type=int, default=15)
    parser.add_argument("--seed", type=int, default=20261127)
    parser.add_argument("--strata", choices=("category", "difficulty"), default="category")
    parser.add_argument("--max-memory-mb", type=int, default=4096)
    parser.add_argument("--max-cpus", type=int, default=2)
    args = parser.parse_args()

    feasible, excluded = [], collections.Counter()
    for path in sorted(glob.glob(os.path.join(args.dataset_dir, "*", "task.toml"))):
        data = tomllib.load(open(path, "rb"))
        env, meta = data.get("environment", {}), data.get("metadata", {})
        name = os.path.basename(os.path.dirname(path))
        if (env.get("gpus") or 0) > 0:
            excluded["gpu"] += 1
        elif memory_mb(env) > args.max_memory_mb:
            excluded["memory"] += 1
        elif (env.get("cpus") or 0) > args.max_cpus:
            excluded["cpus"] += 1
        else:
            feasible.append((meta.get(args.strata) or "?", name))

    by_stratum = collections.defaultdict(list)
    for stratum, name in feasible:
        by_stratum[stratum].append(name)
    share = {s: args.n * len(v) / len(feasible) for s, v in by_stratum.items()}
    alloc = {s: max(1, int(x)) for s, x in share.items()}
    while sum(alloc.values()) > args.n:
        s = min((s for s in alloc if alloc[s] > 1), key=lambda s: share[s] - alloc[s])
        alloc[s] -= 1
    while sum(alloc.values()) < args.n:
        s = max(share, key=lambda s: share[s] - alloc[s])
        alloc[s] += 1

    rng = random.Random(args.seed)
    picked = []
    for stratum in sorted(by_stratum):
        names = sorted(by_stratum[stratum])
        rng.shuffle(names)
        picked += names[: alloc[stratum]]

    print(f"# feasible={len(feasible)} excluded={dict(excluded)} seed={args.seed} n={args.n} "
          f"strata={args.strata} alloc={dict(sorted(alloc.items()))}")
    for name in sorted(picked):
        print(name)


if __name__ == "__main__":
    main()
