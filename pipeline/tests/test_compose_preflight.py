import json
from pathlib import Path
from unittest.mock import MagicMock, call, patch

import pytest

from pipeline.tests.compose_preflight import PreflightError, preflight_check


def _make_run(returncode=0, stdout="", stderr=""):
    result = MagicMock()
    result.returncode = returncode
    result.stdout = stdout
    result.stderr = stderr
    return result


def _ps_output(states: dict) -> str:
    return json.dumps([{"Service": svc, "State": state} for svc, state in states.items()])


ALL_RUNNING = _ps_output({"triton": "running", "qdrant": "running", "api": "running"})


@patch("pipeline.tests.compose_preflight.time.sleep")
@patch("pipeline.tests.compose_preflight.time.monotonic")
@patch("pipeline.tests.compose_preflight.subprocess.run")
def test_all_running_on_first_poll(mock_run, mock_monotonic, mock_sleep, tmp_path):
    up_result = _make_run(returncode=0)
    ps_result = _make_run(returncode=0, stdout=ALL_RUNNING)
    mock_run.side_effect = [up_result, ps_result]
    mock_monotonic.return_value = 0.0

    preflight_check(60, tmp_path)

    assert mock_run.call_count == 2
    mock_sleep.assert_not_called()


@patch("pipeline.tests.compose_preflight.time.sleep")
@patch("pipeline.tests.compose_preflight.time.monotonic")
@patch("pipeline.tests.compose_preflight.subprocess.run")
def test_up_failure_raises_preflight_error(mock_run, mock_monotonic, mock_sleep, tmp_path):
    up_result = _make_run(returncode=1, stdout="some output\n", stderr="error detail\n")
    mock_run.return_value = up_result

    with pytest.raises(PreflightError) as exc_info:
        preflight_check(60, tmp_path)

    msg = str(exc_info.value)
    assert "exit 1" in msg
    assert "some output" in msg
    assert "error detail" in msg
    assert mock_run.call_count == 1
    mock_sleep.assert_not_called()


@patch("pipeline.tests.compose_preflight.time.sleep")
@patch("pipeline.tests.compose_preflight.time.monotonic")
@patch("pipeline.tests.compose_preflight.subprocess.run")
def test_running_on_second_poll(mock_run, mock_monotonic, mock_sleep, tmp_path):
    not_yet = _ps_output({"triton": "starting", "qdrant": "running", "api": "running"})
    up_result = _make_run(returncode=0)
    ps_not_yet = _make_run(returncode=0, stdout=not_yet)
    ps_ready = _make_run(returncode=0, stdout=ALL_RUNNING)
    mock_run.side_effect = [up_result, ps_not_yet, ps_ready]
    mock_monotonic.side_effect = [0.0, 1.0, 3.0]

    preflight_check(60, tmp_path)

    assert mock_run.call_count == 3
    mock_sleep.assert_called_once_with(2)


@patch("pipeline.tests.compose_preflight.time.sleep")
@patch("pipeline.tests.compose_preflight.time.monotonic")
@patch("pipeline.tests.compose_preflight.subprocess.run")
def test_timeout_with_one_service_stuck(mock_run, mock_monotonic, mock_sleep, tmp_path):
    stuck = _ps_output({"triton": "starting", "qdrant": "running", "api": "running"})
    up_result = _make_run(returncode=0)
    ps_stuck = _make_run(returncode=0, stdout=stuck)
    mock_run.side_effect = [up_result, ps_stuck]
    # deadline = 0.0 + 10 = 10.0; first monotonic for deadline, second exceeds it
    mock_monotonic.side_effect = [0.0, 11.0]

    with pytest.raises(PreflightError) as exc_info:
        preflight_check(10, tmp_path)

    msg = str(exc_info.value)
    assert "timed out" in msg
    assert "triton" in msg


@patch("pipeline.tests.compose_preflight.time.sleep")
@patch("pipeline.tests.compose_preflight.time.monotonic")
@patch("pipeline.tests.compose_preflight.subprocess.run")
def test_timeout_with_multiple_services_stuck(mock_run, mock_monotonic, mock_sleep, tmp_path):
    stuck = _ps_output({"triton": "starting", "qdrant": "starting", "api": "running"})
    up_result = _make_run(returncode=0)
    ps_stuck = _make_run(returncode=0, stdout=stuck)
    mock_run.side_effect = [up_result, ps_stuck]
    mock_monotonic.side_effect = [0.0, 11.0]

    with pytest.raises(PreflightError) as exc_info:
        preflight_check(10, tmp_path)

    msg = str(exc_info.value)
    assert "timed out" in msg
    assert "triton" in msg
    assert "qdrant" in msg


@patch("pipeline.tests.compose_preflight.time.sleep")
@patch("pipeline.tests.compose_preflight.time.monotonic")
@patch("pipeline.tests.compose_preflight.subprocess.run")
def test_empty_ps_output_treated_as_not_running(mock_run, mock_monotonic, mock_sleep, tmp_path):
    up_result = _make_run(returncode=0)
    ps_empty = _make_run(returncode=0, stdout="[]")
    ps_ready = _make_run(returncode=0, stdout=ALL_RUNNING)
    mock_run.side_effect = [up_result, ps_empty, ps_ready]
    mock_monotonic.side_effect = [0.0, 1.0, 3.0]

    preflight_check(60, tmp_path)

    assert mock_run.call_count == 3
    mock_sleep.assert_called_once_with(2)


@patch("pipeline.tests.compose_preflight.time.sleep")
@patch("pipeline.tests.compose_preflight.time.monotonic")
@patch("pipeline.tests.compose_preflight.subprocess.run")
def test_ps_failure_raises_preflight_error(mock_run, mock_monotonic, mock_sleep, tmp_path):
    up_result = _make_run(returncode=0)
    ps_fail = _make_run(returncode=1, stderr="connection refused\n")
    mock_run.side_effect = [up_result, ps_fail]
    mock_monotonic.return_value = 0.0

    with pytest.raises(PreflightError) as exc_info:
        preflight_check(60, tmp_path)

    msg = str(exc_info.value)
    assert "docker compose ps failed" in msg
    assert "connection refused" in msg


@patch("pipeline.tests.compose_preflight.time.sleep")
@patch("pipeline.tests.compose_preflight.time.monotonic")
@patch("pipeline.tests.compose_preflight.subprocess.run")
def test_sleep_called_between_polls(mock_run, mock_monotonic, mock_sleep, tmp_path):
    not_yet = _ps_output({"triton": "starting", "qdrant": "running", "api": "running"})
    up_result = _make_run(returncode=0)
    ps_not_yet = _make_run(returncode=0, stdout=not_yet)
    ps_ready = _make_run(returncode=0, stdout=ALL_RUNNING)
    mock_run.side_effect = [up_result, ps_not_yet, ps_not_yet, ps_ready]
    mock_monotonic.side_effect = [0.0, 1.0, 3.0, 5.0]

    preflight_check(60, tmp_path)

    assert mock_sleep.call_count >= 1
    mock_sleep.assert_called_with(2)
