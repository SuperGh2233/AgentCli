#!/usr/bin/env python3
"""Isolated Phase2B-3 wake-up proof. Never starts PaiCLI Main, LLM, MCP, or Docker."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import signal
import struct
import subprocess
import sys
import termios
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "target/phase2b3"
RUN = BASE / f"run-{time.time_ns()}"
JDK = Path("/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home")
JLINE = Path("/Users/a123/.m2/repository/org/jline/jline/4.0.0/jline-4.0.0-jdk11.jar")


def offline_env():
    for name in ("home", "tmp"):
        (BASE / name).mkdir(parents=True, exist_ok=True)
    RUN.mkdir(parents=True, exist_ok=False)
    return dict(
        PATH=f"{JDK}/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        JAVA_HOME=str(JDK), TMPDIR=str(BASE / "tmp"),
        LANG="en_US.UTF-8", TERM="xterm-256color",
        MAVEN_OPTS=f"-Duser.home={BASE}/home -Djava.io.tmpdir={BASE}/tmp",
    )


def compile_probe(env):
    ns = "http://maven.apache.org/POM/4.0.0"
    ET.register_namespace("", ns)
    tree = ET.parse(ROOT / "pom.xml")
    build = tree.getroot().find(f"{{{ns}}}build")
    for name, value in (
        ("directory", BASE / "build"),
        ("sourceDirectory", ROOT / "src/main/java"),
        ("testSourceDirectory", ROOT / "src/test/java"),
    ):
        ET.SubElement(build, f"{{{ns}}}{name}").text = str(value)
    for name, item, folder in (
        ("resources", "resource", "src/main/resources"),
        ("testResources", "testResource", "src/test/resources"),
    ):
        parent = ET.SubElement(build, f"{{{ns}}}{name}")
        child = ET.SubElement(parent, f"{{{ns}}}{item}")
        ET.SubElement(child, f"{{{ns}}}directory").text = str(ROOT / folder)
    tree.write(BASE / "pom.xml", encoding="utf-8", xml_declaration=True)
    cmd = [
        "/opt/homebrew/opt/maven/libexec/bin/mvn", "-o", "-s",
        str(ROOT / "target/publication-check/settings.xml"),
        "-f", str(BASE / "pom.xml"),
        "-Dmaven.repo.local=/Users/a123/.m2/repository",
        "-DskipTests=false", f"-Duser.home={BASE}/home",
        f"-Djava.io.tmpdir={BASE}/tmp",
        "-Dtest=JLineInputAdapterTest,TerminalInputGatewayTest", "test",
    ]
    with (RUN / "maven.log").open("w") as log:
        run = subprocess.run(cmd, cwd=ROOT, env=env, stdout=log,
                             stderr=subprocess.STDOUT, timeout=170)
    return run.returncode


def compile_fresh_proof(env):
    """Compile both the promoted wakeup reader and the exact PTY proof sources."""
    cp = os.pathsep.join((
        str(BASE / "build/test-classes"), str(BASE / "build/classes"), str(JLINE)
    ))
    command = [
        str(JDK / "bin/javac"), "--release", "17", "-encoding", "UTF-8",
        "-cp", cp, "-d", str(BASE / "build/test-classes"),
        str(ROOT / "src/main/java/com/paicli/cli/WakeableLineReader.java"),
        str(ROOT / "src/test/java/com/paicli/cli/JLineWakeupProof.java"),
    ]
    with (RUN / "javac.log").open("w") as log:
        run = subprocess.run(command, cwd=ROOT, env=env,
                             stdout=log, stderr=subprocess.STDOUT, timeout=45)
    return run.returncode


def run_pty(env):
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 30, 120, 0, 0))
    before = termios.tcgetattr(slave)
    ack = RUN / "exit-ack"
    ready = RUN / "exit-ack.ready"
    cp = os.pathsep.join((
        str(BASE / "build/test-classes"), str(BASE / "build/classes"), str(JLINE)
    ))
    output = bytearray()
    cursor = 0
    process = None
    outcome = dict(status="FAIL", wake_without_enter=False,
                   empty_wake_without_enter=False,
                   approval_isolated=False, draft_restored=False,
                   jline_attributes=False, os_attributes=False,
                   launch_mode="PTY + setsid WITHOUT TIOCSCTTY",
                   failure=None)

    def expect(marker, seconds=8):
        nonlocal cursor
        target = marker.encode("utf-8")
        deadline = time.monotonic() + seconds
        while True:
            at = output.find(target, cursor)
            if at >= 0:
                cursor = at + len(target)
                return
            if time.monotonic() >= deadline:
                raise AssertionError(f"missing {marker}")
            ready, _, _ = select.select([master], [], [], 0.1)
            if ready:
                chunk = os.read(master, 65536)
                if not chunk:
                    raise AssertionError(f"PTY EOF before {marker}")
                output.extend(chunk)
            elif process.poll() is not None:
                raise AssertionError(f"child exited {process.returncode} before {marker}")

    def write_keys(value):
        for b in value.encode("utf-8"):
            os.write(master, bytes((b,)))
            time.sleep(0.001)

    try:
        process = subprocess.Popen(
            [
                str(JDK / "bin/java"), f"-Duser.home={BASE}/home",
                f"-Djava.io.tmpdir={BASE}/tmp",
                f"-Dpaicli.proof.exitAck={ack}", "-cp", cp,
                "com.paicli.cli.JLineWakeupProof",
            ],
            stdin=slave, stdout=slave, stderr=slave,
            cwd=ROOT, env=env, start_new_session=True,
        )
        outcome["child_pid"] = process.pid
        expect("PROOF:INPUT_READY")
        write_keys("草稿中文🚀\x1d")
        expect("PROOF:DRAFT_READY")
        expect("PROOF:APPROVAL_PENDING")
        expect("PROOF:WAKE_FLAG_SET", seconds=3)
        # No bytes at all are written between wake flag and wake proof.
        expect("PROOF:WAKE_WITHOUT_ENTER", seconds=3)
        outcome["wake_without_enter"] = True
        expect("PROOF:APPROVAL_UI")
        write_keys("y\r")
        expect("PROOF:INPUT_READY")
        write_keys("续\r")
        expect("PROOF:DRAFT_RESTORED")
        outcome["approval_isolated"] = outcome["draft_restored"] = True
        # No bytes written after DRAFT_RESTORED. The worker must wake a truly
        # EMPTY input read that has already entered the idle wait.
        expect("PROOF:EMPTY_INPUT_READY", seconds=3)
        expect("PROOF:EMPTY_WAKE_FLAG_SET", seconds=3)
        expect("PROOF:EMPTY_WAKE_WITHOUT_ENTER", seconds=3)
        outcome["empty_wake_without_enter"] = True
        expect("PROOF:JLINE_INPUT_COMPLETE")
        outcome["jline_attributes"] = True
        # Wait for a file written AFTER Terminal.close(); closed terminal
        # output is not a reliable control channel for the parent process.
        deadline = time.monotonic() + 4
        while not ready.exists():
            if process.poll() is not None:
                raise AssertionError("JVM exited before Terminal.close() handshake")
            if time.monotonic() >= deadline:
                raise AssertionError("missing post-close file handshake")
            time.sleep(0.01)
        outcome["terminal_close_handshake"] = True
        after = termios.tcgetattr(slave)
        names = ("iflag", "oflag", "cflag", "lflag", "ispeed", "ospeed", "cc")
        exact_differences = {
            name: {"before": repr(before[index]), "after": repr(after[index])}
            for index, name in enumerate(names) if before[index] != after[index]
        }
        # macOS may mark pending re-echo after restoring canonical input;
        # PENDIN is a transient tty state bit, not a persistent configuration
        # mode. Report it explicitly instead of silently accepting all drift.
        pendin = getattr(termios, "PENDIN",
                         0x20000000 if sys.platform == "darwin" else 0)
        effective_before, effective_after = list(before), list(after)
        effective_before[3] &= ~pendin
        effective_after[3] &= ~pendin
        configuration_differences = {
            name: {"before": repr(effective_before[index]),
                   "after": repr(effective_after[index])}
            for index, name in enumerate(names)
            if effective_before[index] != effective_after[index]
        }
        outcome["os_attributes_exact"] = not exact_differences
        outcome["os_raw_attribute_differences"] = exact_differences
        outcome["os_volatile_flags"] = {
            "PENDIN_mask": hex(pendin),
            "before": bool(before[3] & pendin),
            "after": bool(after[3] & pendin),
        }
        outcome["os_attribute_differences"] = configuration_differences
        outcome["os_attributes"] = not configuration_differences
        # Always acknowledge inspection and let the JVM exit normally before
        # judging a mismatch; failed cleanup must not obscure the first error.
        ack.touch(exist_ok=False)
        process.wait(timeout=5)
        outcome["child_exit_code"] = process.returncode
        if process.returncode != 0:
            raise AssertionError(f"child exit {process.returncode}")
        if configuration_differences:
            raise AssertionError("OS PTY configuration differs AFTER Terminal.close()")
        outcome["status"] = "PASS"
    except Exception as exc:
        outcome["failure"] = f"{type(exc).__name__}: {exc}"
    finally:
        # Java writes this bounded non-daemon thread snapshot immediately
        # before main returns. Preserve it even if wait/kill is sandbox-blocked.
        thread_file = RUN / "exit-ack.threads"
        if thread_file.exists():
            outcome["non_daemon_thread_snapshot"] = thread_file.read_text(
                encoding="utf-8", errors="replace")
        wake_diagnostic = RUN / "exit-ack.wake-diagnostic"
        if wake_diagnostic.exists():
            outcome["wakeup_watchdog"] = wake_diagnostic.read_text(
                encoding="utf-8", errors="replace")
        shutdown_diagnostic = RUN / "exit-ack.shutdown-threads"
        if shutdown_diagnostic.exists():
            outcome["jvm_shutdown_snapshot"] = shutdown_diagnostic.read_text(
                encoding="utf-8", errors="replace")
        if process is not None and process.poll() is None:
            outcome["failed_cleanup"] = True
            # Signal ONLY this test's direct Java child. No process-group kill,
            # especially when macOS sandbox can refuse killing a CTTY session.
            for action, timeout in (("terminate", 1.5), ("kill", 2)):
                try:
                    getattr(process, action)()
                    process.wait(timeout=timeout)
                    outcome["cleanup_result"] = f"{action}:reaped"
                    break
                except Exception as exc:
                    outcome["cleanup_result"] = (
                        f"{action}:{type(exc).__name__}: {exc}")
        if process is not None:
            outcome["child_reaped"] = process.poll() is not None
            outcome["final_child_returncode"] = process.poll()
        # Preserve the terminal transcript even when child cleanup failed.
        try:
            while select.select([master], [], [], 0)[0]:
                try:
                    chunk = os.read(master, 65536)
                except OSError:
                    break
                if not chunk:
                    break
                output.extend(chunk)
        except OSError as exc:
            outcome["transcript_drain_error"] = f"{type(exc).__name__}: {exc}"
        finally:
            (RUN / "pty-wakeup.log").write_bytes(output)
            for descriptor in (master, slave):
                try:
                    os.close(descriptor)
                except OSError as exc:
                    outcome.setdefault("descriptor_close_errors", []).append(
                        f"{type(exc).__name__}: {exc}")
    return outcome


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--compile-only", action="store_true")
    args = parser.parse_args()
    env = offline_env()
    status = dict(stage="Phase2B-3 isolated prototype",
                  log_dir=str(RUN.relative_to(ROOT)),
                  production_wired=False, model_invoked=False)
    try:
        code = compile_probe(env)
        status["maven_exit_code"] = code
        if code == 0:
            # Maven may skip testCompile when the exported source's mtime
            # predates cached classes. Never execute a stale wakeup proof.
            status["javac_exit_code"] = compile_fresh_proof(env)
            if status["javac_exit_code"] == 0 and not args.compile_only:
                status["pty"] = run_pty(env)
            elif args.compile_only:
                status["pty"] = "NOT_RUN"
    except Exception as exc:
        status["error"] = f"{type(exc).__name__}: {exc}"
    (RUN / "result.json").write_text(json.dumps(status, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(status, ensure_ascii=False, indent=2))
    return 0 if status.get("maven_exit_code") == 0 and (
        status.get("javac_exit_code") == 0
    ) and (
        args.compile_only or status.get("pty", {}).get("status") == "PASS"
    ) else 1


if __name__ == "__main__":
    raise SystemExit(main())
