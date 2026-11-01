#!/usr/bin/env python3
"""MANUAL Phase2B-3b native macOS Ctrl+C / Ctrl+D check.

Must be launched directly in a real foreground Mac Terminal. This script
compiles a stand-alone JLine class offline, then REPLACES itself with Java.
No Codex sandbox, fake PTY, PaiCLI Main, model, network, or Git operations.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "target/phase2b3"
RUN = BASE / f"native-ctrl-{time.time_ns()}"
JDK = Path("/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home")
JLINE = Path("/Users/a123/.m2/repository/org/jline/jline/4.0.0/jline-4.0.0-jdk11.jar")

def main():
    if sys.platform != "darwin":
        print("REFUSED: this test requires macOS Terminal.", file=sys.stderr)
        return 2
    if not (sys.stdin.isatty() and sys.stdout.isatty()):
        print("REFUSED: run directly in an interactive macOS Terminal, not through an agent or redirected I/O.",
              file=sys.stderr)
        return 2
    try:
        if os.tcgetpgrp(0) != os.getpgrp():
            print("REFUSED: terminal is not the foreground process group.", file=sys.stderr)
            return 2
    except OSError as exc:
        print(f"REFUSED: no usable controlling terminal: {exc}", file=sys.stderr)
        return 2
    if not (JDK / "bin/java").is_file() or not JLINE.is_file():
        print("REFUSED: pinned JDK21 or offline JLine4 jar is unavailable.", file=sys.stderr)
        return 2
    CLASSES = RUN / "classes"
    CLASSES.mkdir(parents=True, exist_ok=False)
    java_source = ROOT / "src/test/java/com/paicli/cli/JLineNativeCtrlProof.java"
    classpath = os.pathsep.join((str(CLASSES), str(JLINE)))
    command = [str(JDK / "bin/javac"), "--release", "17", "-encoding", "UTF-8",
               "-cp", str(JLINE), "-d", str(CLASSES), str(java_source)]
    with (RUN / "javac.log").open("wb") as log:
        compile_result = subprocess.run(command, cwd=ROOT,
                                        stdout=log, stderr=subprocess.STDOUT,
                                        timeout=45, check=False)
    (RUN / "preflight.json").write_text(json.dumps({
        "stage": "native terminal Ctrl+C and Ctrl+D",
        "javac_exit_code": compile_result.returncode,
        "classes": str(CLASSES.relative_to(ROOT)),
        "uses_real_foreground_tty": True,
        "exec_replaces_python": True,
        "production_wired": False,
    }, indent=2) + "\n", encoding="utf-8")
    if compile_result.returncode != 0:
        print(f"javac failed, see {RUN / 'javac.log'}", file=sys.stderr)
        return 1
    state_file = RUN / "state.txt"
    print(f"Native terminal test output: {state_file}")
    print("Java will take over THIS terminal process. Read the two prompts carefully.")
    sys.stdout.flush()
    argv = [str(JDK / "bin/java"), f"-Dpaicli.nativectrl.stateFile={state_file}",
            "-cp", classpath, "com.paicli.cli.JLineNativeCtrlProof"]
    # Replacing rather than spawning preserves the user's real controlling tty
    # and makes native SIGINT behavior observable, not translated by Python.
    os.execv(argv[0], argv)

if __name__ == "__main__":
    raise SystemExit(main())
