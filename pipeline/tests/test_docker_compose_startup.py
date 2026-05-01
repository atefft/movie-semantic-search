"""Integration tests for CI Docker Compose startup check (feature #159)."""

import subprocess
from pathlib import Path

import pytest
import yaml

_REPO_ROOT = Path(__file__).parent.parent.parent
_CI_COMPOSE = _REPO_ROOT / "docker-compose.ci.yml"
_COMPOSE = _REPO_ROOT / "docker-compose.yml"
_WORKFLOW = _REPO_ROOT / ".github" / "workflows" / "docker-compose-check.yml"


class TestDockerComposeCiFile:
    def test_ci_compose_exists(self):
        assert _CI_COMPOSE.exists()

    def test_ci_compose_is_valid_yaml(self):
        data = yaml.safe_load(_CI_COMPOSE.read_text())
        assert data is not None

    def test_docker_compose_config_exits_0(self):
        result = subprocess.run(
            ["docker", "compose", "-f", str(_COMPOSE), "-f", str(_CI_COMPOSE), "config"],
            capture_output=True,
            text=True,
            cwd=str(_REPO_ROOT),
        )
        assert result.returncode == 0, result.stderr

    def test_merged_config_triton_uses_stub_image(self):
        result = subprocess.run(
            ["docker", "compose", "-f", str(_COMPOSE), "-f", str(_CI_COMPOSE), "config"],
            capture_output=True,
            text=True,
            cwd=str(_REPO_ROOT),
        )
        assert result.returncode == 0, result.stderr
        config = yaml.safe_load(result.stdout)
        triton_image = config["services"]["triton"]["image"]
        assert triton_image == "python:3.12-alpine"


class TestDockerComposeCheckWorkflow:
    @pytest.fixture(scope="class")
    def workflow(self):
        assert _WORKFLOW.exists(), f"{_WORKFLOW} not found"
        return yaml.safe_load(_WORKFLOW.read_text())

    def _on(self, workflow):
        # PyYAML 1.1 parses unquoted `on:` as boolean True
        return workflow.get("on") or workflow.get(True) or {}

    def test_workflow_exists(self):
        assert _WORKFLOW.exists()

    def test_workflow_is_valid_yaml(self):
        data = yaml.safe_load(_WORKFLOW.read_text())
        assert data is not None

    def test_on_trigger_includes_pull_request_main(self, workflow):
        branches = self._on(workflow)["pull_request"]["branches"]
        assert "main" in branches

    def test_on_trigger_includes_push_main(self, workflow):
        branches = self._on(workflow)["push"]["branches"]
        assert "main" in branches

    def test_teardown_step_has_if_always(self, workflow):
        steps = workflow["jobs"]["startup-check"]["steps"]
        always_steps = [s for s in steps if s.get("if") == "always()"]
        assert any(
            "docker compose" in s.get("run", "") and "down" in s.get("run", "")
            for s in always_steps
        ), "No step with if: always() running docker compose ... down found"

    def test_startup_timeout_env_var_present(self, workflow):
        env = workflow.get("env", {})
        assert "STARTUP_TIMEOUT" in env

    def test_startup_timeout_default_value(self, workflow):
        env = workflow.get("env", {})
        assert int(env.get("STARTUP_TIMEOUT")) >= 120
