#!/usr/bin/env python3
"""One-shot JLine 4 macOS PTY keyboard regression for Phase2B-3b.

Includes wake during a segmented arrow sequence. No main app, models, network,
Docker, or project source modifications. Test files go to target/phase2b3 only.
"""
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

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "target/phase2b3"
RUN = BASE / f"key-matrix-{time.time_ns()}"
JDK = Path("/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home")
JLINE = Path("/Users/a123/.m2/repository/org/jline/jline/4.0.0/jline-4.0.0-jdk11.jar")
# No shared build directory. Compile the real wakeable reader with the proof.
CLASSES = RUN / "classes"
CP = os.pathsep.join((str(CLASSES), str(JLINE)))


def environment():
    return {
        "PATH": f"{JDK}/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        "JAVA_HOME": str(JDK),
        "HOME": str(BASE / "home"),
        "TMPDIR": str(BASE / "tmp"),
        "LANG": "en_US.UTF-8",
        "TERM": "xterm-256color",
    }


def compile_proof(env):
    CLASSES.mkdir(parents=True, exist_ok=False)
    cmd = [
        str(JDK / "bin/javac"), "--release", "17", "-encoding", "UTF-8",
        "-cp", CP, "-d", str(CLASSES),
        str(ROOT / "src/main/java/com/paicli/cli/WakeableLineReader.java"),
        str(ROOT / "src/test/java/com/paicli/cli/JLineKeyMatrixProof.java"),
    ]
    with (RUN / "javac.log").open("wb") as log:
        return subprocess.run(cmd, cwd=ROOT, env=env,
                              stdout=log, stderr=subprocess.STDOUT,
                              timeout=45, check=False).returncode


def run_pty(env, defer_ctrl_c=False, direct_sigint=False):
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 30, 120, 0, 0))
    before = termios.tcgetattr(slave)
    ack = RUN / "exit-ack"
    ready = RUN / "exit-ack.ready"
    wake_request = RUN / "wake-request"
    process = None
    output = bytearray()
    cursor = 0
    details = {
        "status": "FAIL", "launch_mode": "PTY + setsid WITHOUT TIOCSCTTY",
        "split_arrow_wake": False, "split_arrow_restore": False,
        "arrow": False, "esc_separate": False, "tab_shift_tab": False,
        "bracketed_paste": False, "ctrl_c": False, "ctrl_d": False,
        "termios_config_restored": False, "terminal_close_handshake": False,
        "failure": None,
    }

    def expect(marker, seconds=8):
        nonlocal cursor
        needle = marker.encode("utf-8")
        deadline = time.monotonic() + seconds
        while True:
            index = output.find(needle, cursor)
            if index >= 0:
                cursor = index + len(needle)
                return
            if process.poll() is not None:
                raise AssertionError(f"JVM exited {process.returncode} before {marker}")
            if time.monotonic() >= deadline:
                raise AssertionError(f"missing {marker} within {seconds}s")
            available, _, _ = select.select([master], [], [], 0.1)
            if available:
                try:
                    chunk = os.read(master, 65536)
                except OSError as exc:
                    raise AssertionError(f"PTY read error before {marker}: {exc}") from exc
                if not chunk:
                    raise AssertionError(f"PTY EOF before {marker}")
                output.extend(chunk)

    def send(value):
        for byte in value.encode("utf-8"):
            os.write(master, bytes((byte,)))
            time.sleep(0.001)

    try:
        process = subprocess.Popen([
            str(JDK / "bin/java"),
            f"-Duser.home={BASE}/home",
            f"-Djava.io.tmpdir={BASE}/tmp",
            f"-Dpaicli.keymatrix.exitAck={ack}",
            f"-Dpaicli.keymatrix.wakeRequest={wake_request}",
            f"-Dpaicli.keymatrix.deferCtrlC={str(defer_ctrl_c).lower()}",
            f"-Dpaicli.keymatrix.directSigint={str(direct_sigint).lower()}",
            "-cp", CP, "com.paicli.cli.JLineKeyMatrixProof",
        ], cwd=ROOT, env=env, stdin=slave, stdout=slave, stderr=slave,
            start_new_session=True)
        details["child_pid"] = process.pid
        expect("KEY:INPUT_READY")
        # JLine emitted DECCKM enable (ESC[?1h) for application cursor
        # mode. Real xterm sends SS3 ESC O D for left-arrow in that mode.
        send("abc\x1bODX\r")
        expect("KEY:ARROW_PASS")
        details["arrow"] = True

        expect("KEY:INPUT_READY")
        send("abc\x1bO")
        # Let JLine enter the incomplete SS3 binding before requesting wake.
        time.sleep(0.15)
        wake_request.touch(exist_ok=False)
        requested = Path(str(wake_request) + ".requested")
        deadline = time.monotonic() + 3
        while not requested.exists():
            if process.poll() is not None or time.monotonic() >= deadline:
                raise AssertionError("worker did not request split-arrow wake")
            time.sleep(0.01)
        # No suffix or Enter yet: wake must not split the escape sequence.
        time.sleep(0.15)
        while select.select([master], [], [], 0)[0]:
            output.extend(os.read(master, 65536))
        if b"KEY:SPLIT_ARROW_WAKE_PASS" in output:
            raise AssertionError("wake interrupted an incomplete arrow sequence")
        send("D")
        expect("KEY:SPLIT_ARROW_WAKE_PASS")
        details["split_arrow_wake"] = True
        expect("KEY:INPUT_READY")
        send("X\r")
        expect("KEY:SPLIT_ARROW_RESTORE_PASS")
        details["split_arrow_restore"] = True

        expect("KEY:INPUT_READY")
        send("\x1b")
        expect("KEY:ESC_WIDGET", seconds=4)
        send("z\r")
        expect("KEY:ESC_PASS")
        details["esc_separate"] = True

        expect("KEY:INPUT_READY")
        send("A\tB\x1b[ZC\r")
        expect("KEY:TAB_WIDGET")
        expect("KEY:SHIFT_TAB_WIDGET")
        expect("KEY:TABS_PASS")
        details["tab_shift_tab"] = True

        expect("KEY:INPUT_READY")
        send("\x1b[200~中\n文\x1b[201~\r")
        expect("KEY:PASTE_PASS")
        details["bracketed_paste"] = True

        if defer_ctrl_c:
            # This path is ONLY a partial Ctrl+D and cleanup test.
            # Ctrl+C remains unverified and is never counted as PASS.
            expect("KEY:CTRL_C_DEFERRED")
            details["ctrl_c_status"] = "DEFERRED_NATIVE_CTTY_VERIFICATION"
        elif direct_sigint:
            # An OS signal addressed ONLY to this newly created Java child.
            # This covers macOS SIGINT -> JLine readLine handler, but NOT
            # keyboard Ctrl+C -> controlling terminal foreground process group.
            expect("KEY:INPUT_READY")
            details["ctrl_c_status"] = "DIRECT_OS_SIGINT_NOT_NATIVE_KEYBOARD"
            details["direct_sigint_target_pid"] = process.pid
            os.kill(process.pid, signal.SIGINT)
            expect("KEY:INTERRUPT_DIRECT_OS_SIGNAL_PASS", seconds=4)
            details["ctrl_c_direct_os_signal"] = True
            details["ctrl_c"] = False
        else:
            expect("KEY:INPUT_READY")
            # Diagnostic byte-level path; no controlling terminal is attached.
            ctrl_c_lflag = termios.tcgetattr(slave)[3]
            details["ctrl_c_lflag"] = ctrl_c_lflag
            details["ctrl_c_isig"] = bool(ctrl_c_lflag & termios.ISIG)
            details["ctrl_c_icanon"] = bool(ctrl_c_lflag & termios.ICANON)
            if ctrl_c_lflag & termios.ISIG:
                attrs = termios.tcgetattr(slave)
                attrs[3] &= ~termios.ISIG
                termios.tcsetattr(slave, termios.TCSANOW, attrs)
                details["ctrl_c_isig_cleared_by_driver"] = True
            else:
                details["ctrl_c_isig_cleared_by_driver"] = False
            details["ctrl_c_isig_at_send"] = bool(
                termios.tcgetattr(slave)[3] & termios.ISIG)
            if details["ctrl_c_isig_at_send"]:
                raise AssertionError("kernel ISIG still consumes Ctrl+C")
            send("\x03")
            expect("KEY:CTRL_C_BYTE_SEEN", seconds=4)
            details["ctrl_c_byte_seen"] = True
            expect("KEY:INTERRUPT_SIGNAL_PROXY_PASS", seconds=4)
            details["ctrl_c_signal_proxy"] = True
            details["ctrl_c_status"] = "JLINE_INT_HANDLER_PROXY_ONLY"
            # This does not prove native OS SIGINT / foreground terminal Ctrl+C.
            details["ctrl_c"] = False

        expect("KEY:INPUT_READY")
        ctrl_d_lflag = termios.tcgetattr(slave)[3]
        details["ctrl_d_lflag"] = ctrl_d_lflag
        details["ctrl_d_icanon"] = bool(ctrl_d_lflag & termios.ICANON)
        send("\x04")
        expect("KEY:EOF_PASS")
        details["ctrl_d"] = True
        expect("KEY:INPUT_COMPLETE")

        deadline = time.monotonic() + 5
        while not ready.exists():
            if process.poll() is not None:
                raise AssertionError("child exited before terminal-close marker")
            if time.monotonic() >= deadline:
                raise AssertionError("missing post-close file marker")
            time.sleep(0.01)
        details["terminal_close_handshake"] = True

        after = termios.tcgetattr(slave)
        names = ("iflag", "oflag", "cflag", "lflag", "ispeed", "ospeed", "cc")
        details["termios_exact"] = before == after
        details["termios_raw_differences"] = {
            name: {"before": repr(before[i]), "after": repr(after[i])}
            for i, name in enumerate(names) if before[i] != after[i]
        }
        mask = getattr(termios, "PENDIN",
                       0x20000000 if sys.platform == "darwin" else 0)
        expected = list(before)
        actual = list(after)
        expected[3] &= ~mask
        actual[3] &= ~mask
        details["termios_config_differences"] = {
            name: {"before": repr(expected[i]), "after": repr(actual[i])}
            for i, name in enumerate(names) if expected[i] != actual[i]
        }
        details["termios_config_restored"] = not details["termios_config_differences"]
        ack.touch(exist_ok=False)
        expect("KEY:PASS")
        process.wait(timeout=5)
        details["child_exit_code"] = process.returncode
        if not details["termios_config_restored"]:
            raise AssertionError("OS PTY terminal configuration was not restored")
        if process.returncode != 0:
            raise AssertionError(f"JVM did not exit 0: {process.returncode}")
        # Both modes lack proof of real foreground-tty Ctrl+C delivery.
        details["status"] = "PARTIAL_PASS_NATIVE_CTRL_C_UNVERIFIED"
    except Exception as exc:
        details["failure"] = f"{type(exc).__name__}: {exc}"
    finally:
        if process is not None and process.poll() is None:
            details["failed_cleanup"] = True
            for action, timeout in (("terminate", 1.5), ("kill", 2)):
                try:
                    getattr(process, action)()
                    process.wait(timeout=timeout)
                    details["cleanup"] = f"{action}:reaped"
                    break
                except Exception as exc:
                    details["cleanup"] = f"{action}:{type(exc).__name__}: {exc}"
        if process is not None:
            details["child_reaped"] = process.poll() is not None
            details["final_child_returncode"] = process.poll()
        try:
            for _ in range(80):
                available, _, _ = select.select([master], [], [], 0.01)
                if not available:
                    break
                try:
                    chunk = os.read(master, 65536)
                except OSError:
                    break
                if not chunk:
                    break
                output.extend(chunk)
        finally:
            (RUN / "pty-keyboard.log").write_bytes(output)
            os.close(master)
            os.close(slave)
    return details


def main():
    parser = argparse.ArgumentParser()
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--defer-ctrl-c", action="store_true",
                       help="Test remaining keys and cleanup without claiming Ctrl+C passed")
    modes.add_argument("--direct-sigint", action="store_true",
                       help="Send real SIGINT to the Java child PID, not keyboard Ctrl+C")
    args = parser.parse_args()
    RUN.mkdir(parents=True, exist_ok=False)
    result = {
        "stage": "Phase2B-3b isolated macOS keyboard matrix",
        "run_dir": str(RUN.relative_to(ROOT)),
        "production_wired": False, "model_invoked": False,
    }
    try:
        result["javac_exit_code"] = compile_proof(environment())
        if result["javac_exit_code"] == 0:
            result["pty"] = run_pty(
                environment(), defer_ctrl_c=args.defer_ctrl_c,
                direct_sigint=args.direct_sigint)
    except Exception as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        (RUN / "result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
    status = result.get("pty", {}).get("status")
    # Distinct exit code 2: the PTY matrix or direct OS SIGINT test passed,
    # but this cannot prove keyboard Ctrl+C -> foreground tty -> OS SIGINT.
    if result.get("javac_exit_code") == 0 and status == "PARTIAL_PASS_NATIVE_CTRL_C_UNVERIFIED":
        return 2
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
