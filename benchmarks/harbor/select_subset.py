"""Pick a fixed, reproducible dev subset of a downloaded Harbor dataset.

Only tasks that fit a local OrbStack VM are eligible (no GPU, <= 4 GB memory,
<= 2 CPUs). Tasks are stratified by metadata.category with largest-remainder
allocation and drawn with a fixed seed, so the list never depends on which
tasks look easy.

    uv run --python 3.12 benchmarks/harbor/select_subset.py <dataset_dir> [--n 15] [--seed 20261127]
"""

import argparse
import collections
import glob
import os
import random
import tomllib


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset_dir")
    parser.add_argument("--n", type=int, default=15)
    parser.add_argument("--seed", type=int, default=20261127)
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
        elif (env.get("memory_mb") or 0) > args.max_memory_mb:
            excluded["memory"] += 1
        elif (env.get("cpus") or 0) > args.max_cpus:
            excluded["cpus"] += 1
        else:
            feasible.append((meta.get("category") or "?", name))

    by_category = collections.defaultdict(list)
    for category, name in feasible:
        by_category[category].append(name)
    share = {c: args.n * len(v) / len(feasible) for c, v in by_category.items()}
    alloc = {c: max(1, int(s)) for c, s in share.items()}
    while sum(alloc.values()) > args.n:
        c = min((c for c in alloc if alloc[c] > 1), key=lambda c: share[c] - alloc[c])
        alloc[c] -= 1
    while sum(alloc.values()) < args.n:
        c = max(share, key=lambda c: share[c] - alloc[c])
        alloc[c] += 1

    rng = random.Random(args.seed)
    picked = []
    for category in sorted(by_category):
        names = sorted(by_category[category])
        rng.shuffle(names)
        picked += names[: alloc[category]]

    print(f"# feasible={len(feasible)} excluded={dict(excluded)} seed={args.seed} n={args.n}")
    for name in sorted(picked):
        print(name)


if __name__ == "__main__":
    main()
