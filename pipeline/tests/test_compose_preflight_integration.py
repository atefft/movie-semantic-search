import os
import subprocess
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).parent.parent.parent
_TEST_TARGET = "pipeline/tests/test_01_download_corpus.py"


def _run_pytest(tmp_path, extra_env=None):
    env = os.environ.copy()
    env.pop("COMPOSE_PREFLIGHT", None)
    env.pop("COMPOSE_STARTUP_TIMEOUT", None)
    if extra_env:
        env.update(extra_env)
    return subprocess.run(
        [sys.executable, "-m", "pytest", _TEST_TARGET, "-v"],
        cwd=_REPO_ROOT,
        capture_output=True,
        text=True,
        env=env,
    )


def test_no_preflight_existing_tests_pass(tmp_path):
    result = _run_pytest(tmp_path)
    assert result.returncode == 0


def test_preflight_compose_up_fails_suite_aborts(tmp_path):
    fake_docker = tmp_path / "docker"
    fake_docker.write_text("#!/usr/bin/env bash\nexit 1\n")
    fake_docker.chmod(0o755)

    result = _run_pytest(tmp_path, extra_env={
        "COMPOSE_PREFLIGHT": "1",
        "PATH": f"{tmp_path}:{os.environ['PATH']}",
    })

    combined = result.stdout + result.stderr
    assert result.returncode != 0
    assert "docker compose up -d failed" in combined


def test_preflight_invalid_timeout_aborts_with_message(tmp_path):
    fake_docker = tmp_path / "docker"
    fake_docker.write_text(
        '#!/usr/bin/env bash\n'
        'if [[ "$*" == *"up"* ]]; then exit 0; fi\n'
        'if [[ "$*" == *"ps"* ]]; then echo "[]"; exit 0; fi\n'
        'exit 0\n'
    )
    fake_docker.chmod(0o755)

    result = _run_pytest(tmp_path, extra_env={
        "COMPOSE_PREFLIGHT": "1",
        "COMPOSE_STARTUP_TIMEOUT": "notanint",
        "PATH": f"{tmp_path}:{os.environ['PATH']}",
    })

    combined = result.stdout + result.stderr
    assert "COMPOSE_STARTUP_TIMEOUT" in combined
    assert "notanint" in combined
