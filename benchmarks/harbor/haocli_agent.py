"""Harbor adapter for Hao CLI.

Runs the Hao CLI jar in headless mode inside each task container:

    harbor run -d terminal-bench@2.0 -a haocli_agent:HaoCliAgent -m deepseek/deepseek-flash

Use ``benchmarks/harbor/run.sh`` instead of calling this directly: it builds the
jar, downloads the JREs once and sets PYTHONPATH / env.

The jar and a Linux JRE are uploaded from the host (no downloads inside the
container), so the task's own network policy does not matter for installing the
agent. The model API key is passed as an environment variable of the exec call,
never on the command line.
"""

import json
import os
import shlex
from pathlib import Path
from typing import override

from harbor.agents.installed.base import BaseInstalledAgent, with_prompt_template
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext

AGENT_DIR = "/installed-agent/haocli"
JAR_REMOTE = f"{AGENT_DIR}/haocli.jar"
JRE_REMOTE_DIR = f"{AGENT_DIR}/jre"
LOG_FILE = "haocli.jsonl"
STDERR_FILE = "haocli.stderr.txt"

# Forwarded from the host into the agent process when set.
FORWARDED_ENV = ("DEEPSEEK_API_KEY", "GLM_API_KEY", "ZHIPU_API_KEY", "HAOCLI_LOG_LEVEL")


def _host_path(env_name: str) -> Path:
    value = os.environ.get(env_name)
    if not value:
        raise RuntimeError(f"{env_name} is not set; run benchmarks/harbor/run.sh")
    path = Path(value)
    if not path.exists():
        raise RuntimeError(f"{env_name} points to a missing path: {path}")
    return path


class HaoCliAgent(BaseInstalledAgent):
    """Hao CLI (Java) in headless sandbox mode."""

    @staticmethod
    @override
    def name() -> str:
        return "haocli"

    @override
    def get_version_command(self) -> str | None:
        return None

    @override
    async def install(self, environment: BaseEnvironment) -> None:
        arch_result = await self.exec_as_root(environment, command="uname -m")
        arch = (getattr(arch_result, "stdout", "") or "").strip()
        jre_env = "HAOCLI_JRE_AARCH64" if arch in ("aarch64", "arm64") else "HAOCLI_JRE_X64"
        jre_tar = _host_path(jre_env)

        await self.exec_as_root(environment, command=f"mkdir -p {JRE_REMOTE_DIR}")
        await environment.upload_file(jre_tar, f"{AGENT_DIR}/jre.tar.gz")
        await self.exec_as_root(
            environment,
            command=(
                f"tar -xzf {AGENT_DIR}/jre.tar.gz -C {JRE_REMOTE_DIR} --strip-components=1 "
                f"&& rm -f {AGENT_DIR}/jre.tar.gz "
                f"&& {JRE_REMOTE_DIR}/bin/java -version"
            ),
        )

    @override
    async def configure(self, environment: BaseEnvironment) -> None:
        # The jar changes with every commit, so upload it on every trial.
        await environment.upload_file(_host_path("HAOCLI_JAR"), JAR_REMOTE)
        await self.exec_as_root(environment, command=f"chmod -R a+rX {AGENT_DIR}")

    @override
    def populate_context_post_run(self, context: AgentContext) -> None:
        log = self.logs_dir / LOG_FILE
        if not log.exists():
            return
        result = None
        for line in log.read_text(errors="replace").splitlines():
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("type") == "result":
                result = event
        if result is None:
            return
        usage = result.get("usage") or {}
        context.n_input_tokens = usage.get("input_tokens")
        context.n_output_tokens = usage.get("output_tokens")
        context.n_cache_tokens = usage.get("cached_input_tokens")
        context.metadata = {
            "haocli_status": result.get("status"),
            "model_requests": result.get("model_requests"),
            "provider": result.get("provider"),
            "model": result.get("model"),
            "duration_ms": result.get("duration_ms"),
        }

    @override
    @with_prompt_template
    async def run(self, instruction: str, environment: BaseEnvironment, context: AgentContext) -> None:
        env = {name: os.environ[name] for name in FORWARDED_ENV if os.environ.get(name)}
        if self.model_name:
            provider, _, model = self.model_name.partition("/")
            if provider and model:
                env[f"{provider.upper()}_MODEL"] = model

        extra = os.environ.get("HAOCLI_EXTRA_ARGS", "").strip()
        command = (
            f"{JRE_REMOTE_DIR}/bin/java -jar {JAR_REMOTE} "
            f"-p {shlex.quote(instruction)} "
            "--permission-mode sandbox --output-format stream-json "
            f"{extra} "
            f"2> /logs/agent/{STDERR_FILE} | tee /logs/agent/{LOG_FILE}"
        )
        # Hao CLI picks the provider from whichever API key is present (DeepSeek first).
        await self.exec_as_agent(environment, command=command, env=env)
