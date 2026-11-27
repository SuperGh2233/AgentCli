"""Pull every Docker image a task subset needs before a Harbor run.

Harbor gives each environment 10 minutes to start. On a fresh machine that
includes pulling large x86 images, which times out and leaves the late
container running; leaked containers then slow every later start. Pulling up
front keeps the start to a few seconds without touching any task timeout.

    <harbor python> benchmarks/harbor/prepull.py <dataset_dir> <subset_file>
"""

import os
import subprocess
import sys
import time
import tomllib


def images_for(task_toml: str) -> list[str]:
    data = tomllib.load(open(task_toml, "rb"))
    found = []
    for section in (data.get("environment") or {}, (data.get("verifier") or {}).get("environment") or {}):
        image = section.get("docker_image")
        if image and image not in found:
            found.append(image)
    return found


def present(image: str) -> bool:
    return subprocess.run(["docker", "image", "inspect", image], capture_output=True).returncode == 0


def pull(image: str) -> bool:
    for attempt in range(1, 4):
        for extra in ([], ["--platform", "linux/amd64"]):
            result = subprocess.run(["docker", "pull", "-q", *extra, image], capture_output=True, text=True)
            if result.returncode == 0:
                return True
            if "no matching manifest" not in (result.stderr or ""):
                break
        print(f"  pull failed (attempt {attempt}): {result.stderr.strip()[:200]}", flush=True)
        time.sleep(10 * attempt)
    return False


def main() -> int:
    dataset_dir, subset_file = sys.argv[1], sys.argv[2]
    tasks = [line.strip() for line in open(subset_file) if line.strip() and not line.startswith("#")]
    failed = []
    for task in tasks:
        toml = os.path.join(dataset_dir, task, "task.toml")
        if not os.path.exists(toml):
            print(f"{task}: task.toml not found under {dataset_dir}", flush=True)
            failed.append(task)
            continue
        for image in images_for(toml):
            if present(image):
                print(f"{task}: {image} (cached)", flush=True)
                continue
            start = time.time()
            ok = pull(image)
            print(f"{task}: {image} {'pulled' if ok else 'FAILED'} in {time.time() - start:.0f}s", flush=True)
            if not ok:
                failed.append(task)
    if failed:
        print(f"prepull failed for: {', '.join(failed)}", file=sys.stderr)
        return 1
    print(f"all images ready for {len(tasks)} tasks")
    return 0


if __name__ == "__main__":
    sys.exit(main())
