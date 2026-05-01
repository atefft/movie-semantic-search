import subprocess
import time
from pathlib import Path

REQUIRED_SERVICES = {"triton", "qdrant", "api"}


class PreflightError(Exception):
    pass


def preflight_check(timeout: int, project_dir: Path) -> None:
    result = subprocess.run(
        ["docker", "compose", "up", "-d"],
        cwd=project_dir,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise PreflightError(
            f"docker compose up -d failed (exit {result.returncode}):\n{result.stdout}{result.stderr}"
        )

    deadline = time.monotonic() + timeout
    while True:
        ps_result = subprocess.run(
            ["docker", "compose", "ps", "--format", "json"],
            cwd=project_dir,
            capture_output=True,
            text=True,
        )
        if ps_result.returncode != 0:
            raise PreflightError(f"docker compose ps failed: {ps_result.stderr}")

        import json
        services = json.loads(ps_result.stdout) if ps_result.stdout.strip() else []
        running = {s["Service"] for s in services if s.get("State") == "running"}
        not_running = REQUIRED_SERVICES - running

        if not not_running:
            return

        if time.monotonic() >= deadline:
            raise PreflightError(
                f"timed out after {timeout}s; services not running: {', '.join(sorted(not_running))}"
            )

        time.sleep(2)
