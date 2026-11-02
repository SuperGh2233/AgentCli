#!/usr/bin/env python3
"""Run the opt-in Phase3B-3A ReAct/HITL regression suite offline.

This runner compiles into target/phase3b3/build so it does not overwrite the
repository's existing target/classes or target/test-classes. It uses a
whitelisted environment and never forwards provider API keys or tokens.
No PaiCLI Main, live provider, network, Docker, or Git mutation is performed.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / "target" / "phase3b3"
RUN = BASE / f"run-{time.time_ns()}"
JDK_DEFAULT = Path(
    "/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home"
)
MAVEN_DEFAULTS = (
    Path("/opt/homebrew/opt/maven/libexec/bin/mvn"),
    Path("/usr/local/opt/maven/libexec/bin/mvn"),
)
SETTINGS = ROOT / "target" / "publication-check" / "settings.xml"
TESTS = ",".join((
    "JLineInputAdapterTest",
    "InputCoordinatorTest",
    "TerminalInputGatewayTest",
    "TerminalInputGatewayWakeupTest",
    "InlineApprovalGatewayTest",
    "SteeringRunLoopTest",
    "AgentSteeringSafePointTest",
    "AgentSteeringBudgetTest",
    "AgentSteeringToolCancelTest",
    "AgentBudgetFinalizationTest",
    "ToolRegistryToolStartGateTest",
    "TurnInputManagerTest",
))


def is_jdk21(candidate):
    if candidate is None or not (candidate / "bin" / "java").is_file():
        return False
    result = subprocess.run(
        [str(candidate / "bin" / "java"), "-version"],
        capture_output=True, text=True, check=False, timeout=10,
    )
    return 'version "21' in (result.stdout + result.stderr)


def find_java_home():
    configured = os.environ.get("JAVA_HOME")
    if configured and is_jdk21(Path(configured)):
        return Path(configured)
    if is_jdk21(JDK_DEFAULT):
        return JDK_DEFAULT
    java_home = Path("/usr/libexec/java_home")
    if not java_home.is_file():
        located = shutil.which("java_home")
        java_home = Path(located) if located else java_home
    if java_home.is_file():
        result = subprocess.run(
            [str(java_home), "-v", "21"], capture_output=True, text=True,
            check=False, timeout=10,
        )
        candidate = Path(result.stdout.strip()) if result.returncode == 0 else None
        if is_jdk21(candidate):
            return candidate
    raise RuntimeError("未找到 JDK 21；请设置 JAVA_HOME 后重试。")


def find_maven():
    configured = shutil.which("mvn")
    if configured:
        return Path(configured)
    for candidate in MAVEN_DEFAULTS:
        if candidate.is_file():
            return candidate
    raise RuntimeError("未找到 Maven；请安装 Maven 或将 mvn 加入 PATH。")


def set_child(parent, namespace, tag, value):
    child = parent.find(f"{{{namespace}}}{tag}")
    if child is None:
        child = ET.SubElement(parent, f"{{{namespace}}}{tag}")
    child.text = str(value)
    return child


def isolated_pom(destination, build_dir):
    namespace = "http://maven.apache.org/POM/4.0.0"
    ET.register_namespace("", namespace)
    tree = ET.parse(ROOT / "pom.xml")
    build = tree.getroot().find(f"{{{namespace}}}build")
    if build is None:
        build = ET.SubElement(tree.getroot(), f"{{{namespace}}}build")

    set_child(build, namespace, "directory", build_dir)
    set_child(build, namespace, "sourceDirectory", ROOT / "src" / "main" / "java")
    set_child(build, namespace, "testSourceDirectory", ROOT / "src" / "test" / "java")

    for parent_name, item_name, folder in (
        ("resources", "resource", "src/main/resources"),
        ("testResources", "testResource", "src/test/resources"),
    ):
        parent = build.find(f"{{{namespace}}}{parent_name}")
        if parent is None:
            parent = ET.SubElement(build, f"{{{namespace}}}{parent_name}")
        item = parent.find(f"{{{namespace}}}{item_name}")
        if item is None:
            item = ET.SubElement(parent, f"{{{namespace}}}{item_name}")
        set_child(item, namespace, "directory", ROOT / folder)

    tree.write(destination, encoding="utf-8", xml_declaration=True)


def summarize_reports(report_dir):
    summary = {"suites": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    if not report_dir.is_dir():
        return summary
    for report in report_dir.glob("TEST-*.xml"):
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError):
            continue
        summary["suites"] += 1
        for name in ("tests", "failures", "errors", "skipped"):
            summary[name] += int(root.attrib.get(name, "0"))
    return summary


def main():
    RUN.mkdir(parents=True, exist_ok=False)
    build_dir = RUN / "build"
    home_dir = RUN / "home"
    tmp_dir = RUN / "tmp"
    for directory in (build_dir, home_dir, tmp_dir):
        directory.mkdir(parents=True, exist_ok=True)

    result = {
        "stage": "Phase3B-3A offline ReAct/HITL regression",
        "run_dir": str(RUN.relative_to(ROOT)),
        "build_dir": str(build_dir.relative_to(ROOT)),
        "production_main_invoked": False,
        "provider_api_called": False,
        "network_used": False,
        "docker_used": False,
        "git_mutated": False,
        "status": "FAIL",
    }

    try:
        java_home = find_java_home()
        maven = find_maven()
        local_repo = Path.home() / ".m2" / "repository"
        if not local_repo.is_dir():
            raise RuntimeError(f"本地 Maven 仓库不存在：{local_repo}")
        if not SETTINGS.is_file():
            raise RuntimeError(f"离线 Maven settings 不存在：{SETTINGS}")

        env = {
            "PATH": os.pathsep.join((
                str(java_home / "bin"),
                str(maven.parent),
                "/opt/homebrew/bin",
                "/usr/bin",
                "/bin",
                "/usr/sbin",
                "/sbin",
            )),
            "JAVA_HOME": str(java_home),
            "HOME": str(home_dir),
            "TMPDIR": str(tmp_dir),
            "LANG": "en_US.UTF-8",
            "TERM": "dumb",
            "MAVEN_OPTS": f"-Duser.home={home_dir} -Djava.io.tmpdir={tmp_dir}",
        }

        # Keep the temporary POM beside the real pom.xml so Maven's project.basedir
        # remains the repository root for relative plugin/config/resource paths.
        isolated_file = ROOT / ".phase3b3-test-pom.xml"
        if isolated_file.exists():
            raise RuntimeError(f"临时 POM 已存在，拒绝覆盖：{isolated_file}")
        isolated_pom(isolated_file, build_dir)
        command = [
            str(maven), "-o", "-s", str(SETTINGS), "-f", str(isolated_file),
            f"-Dmaven.repo.local={local_repo}",
            f"-Duser.home={home_dir}",
            f"-Djava.io.tmpdir={tmp_dir}",
            "-DskipTests=false",
            f"-Dtest={TESTS}",
            "test",
        ]
        result["command"] = command
        result["java_home"] = str(java_home)
        result["maven"] = str(maven)
        result["local_maven_repo"] = str(local_repo)

        log_path = RUN / "maven.log"
        try:
            with log_path.open("w", encoding="utf-8") as log:
                completed = subprocess.run(
                    command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                    timeout=240, check=False,
                )
        finally:
            isolated_file.unlink(missing_ok=True)
        result["maven_exit_code"] = completed.returncode
        result["maven_log"] = str(log_path.relative_to(ROOT))
        result["tests"] = summarize_reports(build_dir / "surefire-reports")
        result["status"] = (
            "PASS" if completed.returncode == 0 and result["tests"]["tests"] > 0
            and result["tests"]["failures"] == 0 and result["tests"]["errors"] == 0
            else "FAIL"
        )
    except subprocess.TimeoutExpired:
        result["status"] = "TIMEOUT"
        result["error"] = "Maven test run exceeded 240 seconds."
    except Exception as exc:
        result["status"] = "RUNNER_ERROR"
        result["error"] = f"{type(exc).__name__}: {exc}"

    summary_path = RUN / "summary.json"
    summary_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "status": result["status"],
        "summary": str(summary_path.relative_to(ROOT)),
        "maven_log": result.get("maven_log"),
        "tests": result.get("tests"),
        "error": result.get("error"),
    }, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
