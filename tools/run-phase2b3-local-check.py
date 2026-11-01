#!/usr/bin/env python3
"""Run the Phase2B-3a PTY gate in a *normal macOS Terminal*, not Codex.

Stage 1: prove plain java -version exits with PTY+setsid, NO TIOCSCTTY.
Stage 2: only after Stage 1 succeeds, run offline JUnit + fresh javac + PTY proof
         with that same safe PTY launch mode.
The separate exit-matrix test documents why TIOCSCTTY was removed.
No provider calls, network, PaiCLI Main, or git mutations are performed.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "target" / "phase2b3"
RUN = BASE / f"local-check-{time.time_ns()}"


def run_stage(name, script, timeout_seconds):
    cmd = [sys.executable, str(ROOT / "tools" / script)]
    details = {"command": cmd, "returncode": None, "timeout": False, "error": None}
    try:
        completed = subprocess.run(
            cmd, cwd=ROOT, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=timeout_seconds,
            check=False,
        )
        details["returncode"] = completed.returncode
        (RUN / f"{name}.stdout.log").write_text(completed.stdout, encoding="utf-8")
        (RUN / f"{name}.stderr.log").write_text(completed.stderr, encoding="utf-8")
        # Both child scripts print exactly one final JSON object.
        try:
            details["result"] = json.loads(completed.stdout)
        except ValueError:
            details["output_parse_error"] = "Child did not print valid final JSON"
    except subprocess.TimeoutExpired as exc:
        details["timeout"] = True
        details["error"] = f"process timeout after {timeout_seconds} seconds"
        (RUN / f"{name}.stdout.log").write_bytes(exc.stdout or b"")
        (RUN / f"{name}.stderr.log").write_bytes(exc.stderr or b"")
    except Exception as exc:
        details["error"] = f"{type(exc).__name__}: {exc}"
    return details


def main():
    RUN.mkdir(parents=True, exist_ok=False)
    summary = {
        "stage": "Phase2B-3a local Mac PTY gate",
        "run_dir": str(RUN.relative_to(ROOT)),
        "method": "PTY+setsid without TIOCSCTTY: Java exit baseline THEN isolated JLine proof",
        "production_wired": False, "model_invoked": False,
        "status": "FAIL",
    }
    try:
        baseline = run_stage("baseline", "run-pty-java-exit-baseline.py", 25)
        summary["baseline"] = baseline
        baseline_result = baseline.get("result") or {}
        # Gate on genuine Java exit, not merely that -version printed.
        if (baseline.get("returncode") != 0
                or baseline_result.get("child_returncode") != 0
                or baseline_result.get("wait_timeout") is not False):
            summary["status"] = "BLOCKED_BY_JAVA_PTY_EXIT"
            summary["next_step"] = (
                "Investigate JDK/PTY/session exit locally; do not run the "
                "steering PTY proof or claim the gate passed.")
        else:
            proof = run_stage("proof", "run-jline-wakeup-proof.py", 270)
            summary["proof"] = proof
            result = proof.get("result") or {}
            pty = result.get("pty") or {}
            checks = (
                "wake_without_enter", "approval_isolated", "draft_restored",
                "jline_attributes", "terminal_close_handshake", "os_attributes",
            )
            passed = (
                proof.get("returncode") == 0
                and result.get("maven_exit_code") == 0
                and result.get("javac_exit_code") == 0
                and isinstance(pty, dict)
                and pty.get("status") == "PASS"
                and all(pty.get(name) is True for name in checks)
                and not pty.get("failed_cleanup")
            )
            summary["status"] = "PROTOTYPE_PASS" if passed else "PROTOTYPE_FAIL"
            summary["next_step"] = (
                "Continue independent key-input matrix; do not wire production yet."
                if passed else
                "Review proof run result.json and PTY transcript before fixing."
            )
    except Exception as exc:
        summary["error"] = f"{type(exc).__name__}: {exc}"
        summary["status"] = "RUNNER_ERROR"
    finally:
        (RUN / "summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(json.dumps({
            "status": summary["status"],
            "summary_file": str(RUN.relative_to(ROOT) / "summary.json"),
            "baseline_exit": summary.get("baseline", {}).get("returncode"),
            "proof_exit": summary.get("proof", {}).get("returncode"),
            "next_step": summary.get("next_step", "Inspect summary.json"),
        }, ensure_ascii=False, indent=2))
    return 0 if summary["status"] == "PROTOTYPE_PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
