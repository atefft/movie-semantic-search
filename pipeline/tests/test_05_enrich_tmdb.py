"""
Tests for pipeline/05_enrich_tmdb.py.

Expects the script to expose:
  - search_tmdb(session, title: str, year: int | None, api_key: str) -> str | None
    Returns poster_path from results[0] or None if no results / no poster.

The requests.Session is passed in for testability; tests provide a mock session.
"""

import pytest
from unittest.mock import MagicMock, patch


@pytest.fixture(scope="module")
def mod(pipeline_loader):
    return pipeline_loader("05_enrich_tmdb.py")


def _mock_session(results):
    """Return a mock requests.Session whose get().json() returns the given results list."""
    session = MagicMock()
    session.get.return_value.json.return_value = {"results": results}
    return session


# ---------------------------------------------------------------------------
# search_tmdb
# ---------------------------------------------------------------------------

class TestSearchTmdb:
    def test_returns_poster_path_from_first_result(self, mod):
        session = _mock_session([{"poster_path": "/abc123.jpg"}])
        result = mod.search_tmdb(session, "Cast Away", 2000, "fake_key")
        assert result == "/abc123.jpg"

    def test_returns_none_when_no_results(self, mod):
        session = _mock_session([])
        result = mod.search_tmdb(session, "Nonexistent Movie", 2000, "fake_key")
        assert result is None

    def test_returns_none_when_poster_path_is_null(self, mod):
        session = _mock_session([{"poster_path": None}])
        result = mod.search_tmdb(session, "Art Film", 2001, "fake_key")
        assert result is None

    def test_request_includes_title_in_query(self, mod):
        session = _mock_session([])
        mod.search_tmdb(session, "Cast Away", 2000, "fake_key")
        call_kwargs = session.get.call_args
        url = call_kwargs[0][0] if call_kwargs[0] else call_kwargs.kwargs.get("url", "")
        params = call_kwargs[1].get("params", {}) if call_kwargs[1] else {}
        query_value = params.get("query", "")
        assert "Cast Away" in url or "Cast Away" in str(query_value)

    def test_request_includes_api_key(self, mod):
        session = _mock_session([])
        mod.search_tmdb(session, "Alien", 1979, "my_api_key")
        call_kwargs = session.get.call_args
        all_args = str(call_kwargs)
        assert "my_api_key" in all_args

    def test_handles_none_year_without_error(self, mod):
        session = _mock_session([{"poster_path": "/x.jpg"}])
        result = mod.search_tmdb(session, "Unknown Year Film", None, "fake_key")
        assert result == "/x.jpg"


# ---------------------------------------------------------------------------
# Rate limiting — basic contract
# ---------------------------------------------------------------------------

class TestRateLimiting:
    def test_rate_limiter_sleeps_when_over_limit(self, mod):
        """RateLimiter (or equivalent) must enforce ≤40 req/s by sleeping."""
        import time
        limiter = mod.RateLimiter(max_per_second=40)
        calls = []

        with patch("time.sleep") as mock_sleep:
            for _ in range(50):
                limiter.wait()
            # At 40 req/s, 50 calls must trigger at least one sleep
            assert mock_sleep.called, "RateLimiter must call time.sleep to enforce rate limit"
