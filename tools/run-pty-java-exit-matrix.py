#!/usr/bin/env python3
"""Isolated Mac Java exit matrix for PaiCLI Phase 2B-3a.
Runs only JDK java -version. No JLine, PaiCLI, model, network, Maven or Docker.
Writes to a new target/phase2b3/exit-matrix-* folder. Never kills unrelated PIDs.
"""
import errno
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import subprocess
import sys
import termios
import time

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "target/phase2b3"
RUN = BASE / f"exit-matrix-{time.time_ns()}"
JAVA = Path("/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home/bin/java")
CASES = [
    ("file-stdio", False, False, False),
    ("file-stdio-setsid", False, True, False),
    ("pty-no-setsid", True, False, False),
    ("pty-setsid", True, True, False),
    ("pty-setsid-ctty", True, True, True),
]


def collect_pty(master, limit=65536):
    output = bytearray()
    for _ in range(128):
        try:
            available, _, _ = select.select([master], [], [], 0.015)
            if not available or len(output) >= limit:
                break
            chunk = os.read(master, min(4096, limit - len(output)))
            if not chunk:
                break
            output.extend(chunk)
        except OSError as exc:
            if exc.errno in (errno.EIO, errno.EBADF):
                break
            raise
    return output


def probe(name, use_pty, setsid, controlling_tty):
    master = slave = None
    process = None
    started = time.monotonic()
    result = {
        "name": name, "pty": use_pty, "setsid": setsid,
        "controlling_tty": controlling_tty, "pid": None,
        "exit_code": None, "wait_timeout": False, "error": None,
        "cleanup": None, "reaped": False,
    }
    transcript = RUN / f"{name}.log"
    env = {
        "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
        "HOME": str(BASE / "home"),
        "TMPDIR": str(BASE / "tmp"),
        "LANG": "en_US.UTF-8",
        "TERM": "xterm-256color",
    }
    log = None

    def setup_controlling_tty():
        os.setsid()
        fcntl.ioctl(0, termios.TIOCSCTTY, 0)

    try:
        if use_pty:
            master, slave = pty.openpty()
            streams = {"stdin": slave, "stdout": slave, "stderr": slave}
        else:
            log = transcript.open("wb")
            streams = {"stdin": subprocess.DEVNULL, "stdout": log, "stderr": subprocess.STDOUT}

        kwargs = {"cwd": ROOT, "env": env, **streams}
        if controlling_tty:
            kwargs["preexec_fn"] = setup_controlling_tty
        elif setsid:
            kwargs["start_new_session"] = True
        process = subprocess.Popen([str(JAVA), "-version"], **kwargs)
        result["pid"] = process.pid
        try:
            result["exit_code"] = process.wait(timeout=6)
        except subprocess.TimeoutExpired:
            result["wait_timeout"] = True
    except Exception as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        if process is not None and process.poll() is None:
            # Signal ONLY this test's direct child, never its process group.
            for method, seconds in (("terminate", 1.5), ("kill", 2)):
                try:
                    getattr(process, method)()
                    process.wait(timeout=seconds)
                    result["cleanup"] = f"{method}:reaped"
                    break
                except Exception as exc:
                    result["cleanup"] = f"{method}:{type(exc).__name__}:{exc}"
        if process is not None:
            result["reaped"] = process.poll() is not None
            result["final_child_returncode"] = process.poll()
        if master is not None:
            try:
                transcript.write_bytes(collect_pty(master))
            except Exception as exc:
                result["transcript_error"] = f"{type(exc).__name__}: {exc}"
        if log is not None:
            log.close()
        for fd in (master, slave):
            if fd is not None:
                try:
                    os.close(fd)
                except OSError:
                    pass
        result["elapsed_ms"] = int((time.monotonic() - started) * 1000)
    return result


def main():
    RUN.mkdir(parents=True, exist_ok=False)
    outcome = {
        "stage": "Phase2B-3a Java exit differential diagnosis",
        "run_dir": str(RUN.relative_to(ROOT)),
        "mode": "read-only project; isolated offline java -version",
        "cases": [], "status": "FAIL",
    }
    try:
        if sys.platform != "darwin":
            outcome["error"] = "This differential test must run on macOS"
        elif not JAVA.is_file():
            outcome["error"] = f"Missing pinned JDK: {JAVA}"
        else:
            for name, use_pty, setsid, ctty in CASES:
                case = probe(name, use_pty, setsid, ctty)
                outcome["cases"].append(case)
                if not case["reaped"]:
                    outcome["status"] = "ABORT_UNREAPED_CHILD"
                    outcome["error"] = "A test child could not be reaped; subsequent probes not started"
                    break
            else:
                outcome["status"] = (
                    "PASS_ALL" if all(c["exit_code"] == 0 and not c["wait_timeout"]
                                      for c in outcome["cases"])
                    else "MATRIX_HAS_EXIT_FAILURES"
                )
    finally:
        (RUN / "result.json").write_text(
            json.dumps(outcome, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(json.dumps(outcome, ensure_ascii=False, indent=2))
    return 0 if outcome["status"] == "PASS_ALL" else 1


if __name__ == "__main__":
    raise SystemExit(main())
