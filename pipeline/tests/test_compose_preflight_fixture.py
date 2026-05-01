"""Unit tests for the compose_preflight_fixture in conftest.py."""

from unittest.mock import patch

import pytest

from pipeline.tests.conftest import compose_preflight_fixture

_MODULE = "pipeline.tests.conftest"


def _call_fixture():
    """Call the fixture's underlying function directly, bypassing pytest's call guard."""
    fn = getattr(compose_preflight_fixture, "__wrapped__", None) or \
         getattr(compose_preflight_fixture, "__pytest_wrapped__", None)
    if fn is not None and hasattr(fn, "obj"):
        fn = fn.obj
    (fn or compose_preflight_fixture)()


def test_compose_preflight_unset(monkeypatch):
    """COMPOSE_PREFLIGHT unset: fixture returns without calling preflight_check."""
    monkeypatch.delenv("COMPOSE_PREFLIGHT", raising=False)
    with patch(f"{_MODULE}.preflight_check") as mock_check:
        _call_fixture()
    mock_check.assert_not_called()


def test_compose_preflight_empty_string(monkeypatch):
    """COMPOSE_PREFLIGHT empty string: fixture returns without calling preflight_check."""
    monkeypatch.setenv("COMPOSE_PREFLIGHT", "")
    with patch(f"{_MODULE}.preflight_check") as mock_check:
        _call_fixture()
    mock_check.assert_not_called()


def test_compose_preflight_success(monkeypatch):
    """COMPOSE_PREFLIGHT set, preflight succeeds: pytest.fail is never called."""
    monkeypatch.setenv("COMPOSE_PREFLIGHT", "1")
    monkeypatch.delenv("COMPOSE_STARTUP_TIMEOUT", raising=False)
    with patch(f"{_MODULE}.preflight_check") as mock_check, \
         patch("pytest.fail") as mock_fail:
        _call_fixture()
    mock_check.assert_called_once()
    mock_fail.assert_not_called()


def test_compose_preflight_error_raised(monkeypatch):
    """COMPOSE_PREFLIGHT set, PreflightError raised: pytest.fail called with error message."""
    monkeypatch.setenv("COMPOSE_PREFLIGHT", "1")
    monkeypatch.delenv("COMPOSE_STARTUP_TIMEOUT", raising=False)
    error_msg = "services not running: triton"
    from pipeline.tests.compose_preflight import PreflightError
    with patch(f"{_MODULE}.preflight_check", side_effect=PreflightError(error_msg)), \
         patch(f"{_MODULE}.pytest.fail", side_effect=Exception("fail called")) as mock_fail:
        with pytest.raises(Exception, match="fail called"):
            _call_fixture()
    mock_fail.assert_called_once_with(error_msg)


def test_default_timeout(monkeypatch):
    """COMPOSE_STARTUP_TIMEOUT unset: preflight_check called with timeout=60."""
    monkeypatch.setenv("COMPOSE_PREFLIGHT", "1")
    monkeypatch.delenv("COMPOSE_STARTUP_TIMEOUT", raising=False)
    with patch(f"{_MODULE}.preflight_check") as mock_check:
        _call_fixture()
    args = mock_check.call_args[0]
    assert args[0] == 60


def test_custom_timeout(monkeypatch):
    """COMPOSE_STARTUP_TIMEOUT=30: preflight_check called with timeout=30."""
    monkeypatch.setenv("COMPOSE_PREFLIGHT", "1")
    monkeypatch.setenv("COMPOSE_STARTUP_TIMEOUT", "30")
    with patch(f"{_MODULE}.preflight_check") as mock_check:
        _call_fixture()
    args = mock_check.call_args[0]
    assert args[0] == 30


def test_invalid_timeout(monkeypatch):
    """COMPOSE_STARTUP_TIMEOUT=abc: pytest.fail called with message containing var name and value."""
    monkeypatch.setenv("COMPOSE_PREFLIGHT", "1")
    monkeypatch.setenv("COMPOSE_STARTUP_TIMEOUT", "abc")
    with patch(f"{_MODULE}.pytest.fail", side_effect=Exception("fail called")) as mock_fail:
        with pytest.raises(Exception, match="fail called"):
            _call_fixture()
    assert mock_fail.called
    fail_msg = mock_fail.call_args[0][0]
    assert "COMPOSE_STARTUP_TIMEOUT" in fail_msg
    assert "abc" in fail_msg
