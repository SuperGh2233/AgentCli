#!/usr/bin/env python3
"""Offline JDK21/JLine proof. No application startup, providers or model requests."""
import argparse
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import termios
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / 'target/phase2b2'
JDK = Path('/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home')
JLINE = Path('/Users/a123/.m2/repository/org/jline/jline/4.0.0/jline-4.0.0-jdk11.jar')


def setup():
    for name in ('home', 'tmp'):
        (BASE / name).mkdir(parents=True, exist_ok=True)
    # Deliberate allowlist: neither Maven nor the PTY child inherits API keys.
    return dict(PATH=f'{JDK}/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin',
                JAVA_HOME=str(JDK), TMPDIR=str(BASE / 'tmp'), LANG='en_US.UTF-8',
                TERM='xterm-256color', MAVEN_OPTS=f'-Duser.home={BASE}/home -Djava.io.tmpdir={BASE}/tmp')


def junit(tests, label, env):
    ns = 'http://maven.apache.org/POM/4.0.0'
    ET.register_namespace('', ns)
    tree = ET.parse(ROOT / 'pom.xml')
    build = tree.getroot().find(f'{{{ns}}}build')
    for name, value in [('directory', BASE / 'build'), ('sourceDirectory', ROOT / 'src/main/java'),
                        ('testSourceDirectory', ROOT / 'src/test/java')]:
        ET.SubElement(build, f'{{{ns}}}{name}').text = str(value)
    for group, item, directory in [('resources', 'resource', 'src/main/resources'),
                                    ('testResources', 'testResource', 'src/test/resources')]:
        parent = ET.SubElement(build, f'{{{ns}}}{group}')
        child = ET.SubElement(parent, f'{{{ns}}}{item}')
        ET.SubElement(child, f'{{{ns}}}directory').text = str(ROOT / directory)
    tree.write(BASE / 'pom.xml', encoding='utf-8', xml_declaration=True)
    cmd = ['/opt/homebrew/opt/maven/libexec/bin/mvn', '-o', '-s',
           str(ROOT / 'target/publication-check/settings.xml'), '-f', str(BASE / 'pom.xml'),
           '-Dmaven.repo.local=/Users/a123/.m2/repository', '-DskipTests=false',
           f'-Duser.home={BASE}/home', f'-Djava.io.tmpdir={BASE}/tmp', f'-Dtest={tests}', 'test']
    with (BASE / f'{label}.log').open('w') as log:
        log.write('Command: ' + ' '.join(cmd) + '\n')
        log.flush()
        result = subprocess.run(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    print('\n'.join((BASE / f'{label}.log').read_text().splitlines()[-24:]))
    return result.returncode


def pty_case(name, steps, env, fake=False):
    import pty
    import fcntl
    import struct
    master = slave = None
    if not fake:
        master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 30, 120, 0, 0))
    original = None if fake else termios.tcgetattr(slave)
    cp = os.pathsep.join([str(BASE / 'build/test-classes'), str(BASE / 'build/classes'), str(JLINE)])

    def child_terminal():
        os.setsid()
        fcntl.ioctl(0, termios.TIOCSCTTY, 0)

    child = subprocess.Popen([str(JDK / 'bin/java'), f'-Duser.home={BASE}/home',
                              f'-Djava.io.tmpdir={BASE}/tmp', '-cp', cp,
                              'com.paicli.cli.JLineInputProof', name] + (['fake'] if fake else []),
                             stdin=subprocess.PIPE if fake else slave,
                             stdout=subprocess.PIPE if fake else slave,
                             stderr=subprocess.STDOUT if fake else slave, cwd=ROOT, env=env,
                             preexec_fn=os.setsid if fake else child_terminal)
    if fake:
        master = child.stdout.fileno()
    input_fd = child.stdin.fileno() if fake else master
    output = bytearray()
    offset = 0

    def expect(marker):
        nonlocal offset
        deadline = time.monotonic() + 8
        wanted = marker.encode()
        while True:
            found = output.find(wanted, offset)
            if found >= 0:
                offset = found + len(wanted)
                return
            if time.monotonic() > deadline:
                raise AssertionError(f'{name}: missing {marker!r}')
            if select.select([master], [], [], 0.1)[0]:
                chunk = os.read(master, 65536)
                if not chunk:
                    raise AssertionError(f'{name}: PTY EOF before {marker!r}')
                output.extend(chunk)
            elif child.poll() is not None:
                raise AssertionError(f'{name}: child exited {child.returncode} before {marker!r}')

    try:
        for marker, data in steps:
            expect(marker)
            if data is not None:
                # Split even within UTF-8 codepoints; never a second terminal reader.
                for byte in data:
                    os.write(input_fd, bytes([byte]))
                    time.sleep(0.001)
        expect('PROOF:PASS')
        code = child.wait(timeout=3)
        assert code == 0, (name, code)
        if not fake:
            assert termios.tcgetattr(slave) == original, f'{name}: terminal attributes leaked'
        return dict(scenario=name, status='PASS',
                    attributes_check='DumbTerminal only' if fake else 'JLine and OS PTY')
    finally:
        if child.poll() is None:
            # Failure cleanup only, never evidence of a working wakeup.
            os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=3)
        (BASE / f'{"fake" if fake else "pty"}-{name}.log').write_bytes(output)
        if fake:
            child.stdin.close()
            child.stdout.close()
        else:
            os.close(master)
            os.close(slave)


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--tests', default='JLineInputAdapterTest')
    args.add_argument('--label', default='green')
    args.add_argument('--pty-only', action='store_true')
    args.add_argument('--junit-only', action='store_true')
    args.add_argument('--fake', action='store_true', help='DumbTerminal pipes; no native PTY claims')
    options = args.parse_args()
    env = setup()
    if not options.pty_only:
        code = junit(options.tests, options.label, env)
        if code or options.junit_only:
            return code
    cases = {
        'utf8': [('PROOF:INPUT_READY', '/steer 完整中文🚀命令\r'.encode())],
        'keys': [('PROOF:INPUT_READY', '甲乙\x1b[D中\x1b[C\x1b[200~粘贴\n中文🚀\x1b[201~\r'.encode()),
                 ('PROOF:INPUT_READY', b'abc\x1b'), ('PROOF:ESC', '保留\r'.encode())],
        'approval-blocked': [('PROOF:INPUT_READY', '草稿中文🚀\x1d'.encode()),
                             ('PROOF:APPROVAL_PENDING', b'\x1d'),
                             ('PROOF:GATE_NO_WAKE', b'\x03')],
        'run-complete': [('PROOF:INPUT_READY', b'\x1d'), ('PROOF:RUN_NO_WAKE', b'\x04')],
        'eof': [('PROOF:INPUT_READY', b'\x04')],
        'ctrl-c': [('PROOF:INPUT_READY', '取消前草稿\x1d'.encode()), ('PROOF:CHECKPOINT', b'\x03')],
        'plan': [('PROOF:INPUT_READY', b'\x1b[A'), ('PROOF:CHECKPOINT', b'\x0f'),
                 ('PROOF:VIEW', b'I'), ('PROOF:SUPPLEMENT', '先检查中文🚀\r'.encode())],
        'plan-line': [('PROOF:INPUT_READY', '补充中文步骤\r'.encode())],
    }
    results = []
    for name, steps in cases.items():
        result = pty_case(name, steps, env, options.fake)
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    (BASE / ('fake-results.json' if options.fake else 'pty-results.json')).write_text(json.dumps(dict(results=results, wake_gate=False,
        main_wired=False, mac_manual='NOT_TESTED'), indent=2) + '\n')
    print('Gate: NOT PASSED (approval and run completion do not wake blocked readLine).')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
