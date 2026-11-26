#!/usr/bin/env python3
"""Phase2B-3a gate: plain Java must exit under PTY+setsid WITHOUT TIOCSCTTY.
The separate run-pty-java-exit-matrix.py retains the problematic CTTY case.
No PaiCLI, JLine, Maven, network, model or production code is loaded.
"""
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import struct
import subprocess
import termios
import time

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "target/phase2b3"
RUN = BASE / f"exit-baseline-{time.time_ns()}"
JDK = Path("/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home")

def main():
    RUN.mkdir(parents=True, exist_ok=False)
    env = {
        "PATH": f"{JDK}/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        "JAVA_HOME": str(JDK),
        "LANG": "en_US.UTF-8",
        "TERM": "xterm-256color",
        "TMPDIR": str(BASE / "tmp"),
    }
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 30, 120, 0, 0))
    outcome = {"case": "plain java -version with PTY + setsid, no TIOCSCTTY",
               "java_started": False, "child_returncode": None,
               "wait_timeout": False, "error": None}
    proc = None
    transcript = bytearray()
    try:
        proc = subprocess.Popen([str(JDK / "bin/java"), "-version"],
                                stdin=slave, stdout=slave, stderr=slave,
                                cwd=ROOT, env=env, start_new_session=True)
        outcome["java_started"] = True
        outcome["pid"] = proc.pid
        try:
            outcome["child_returncode"] = proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            outcome["wait_timeout"] = True
    except Exception as exc:
        outcome["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        # Only signal the exact Java process this script created.
        if proc is not None and proc.poll() is None:
            try:
                proc.terminate()
                outcome["termination_requested"] = True
                proc.wait(timeout=2)
            except Exception as exc:
                outcome["termination_error"] = f"{type(exc).__name__}: {exc}"
        for _ in range(64):
            readable, _, _ = select.select([master], [], [], 0.01)
            if not readable:
                break
            try:
                chunk = os.read(master, 65536)
            except OSError:
                break
            if not chunk:
                break
            transcript.extend(chunk)
        (RUN / "transcript.log").write_bytes(transcript)
        for fd in (master, slave):
            os.close(fd)
        (RUN / "result.json").write_text(
            json.dumps(outcome, indent=2, ensure_ascii=False) + "\n")
        print(json.dumps({"run": str(RUN.relative_to(ROOT)), **outcome},
                         ensure_ascii=False, indent=2))
    return 0 if outcome["child_returncode"] == 0 and not outcome["wait_timeout"] else 1

if __name__ == "__main__":
    raise SystemExit(main())
