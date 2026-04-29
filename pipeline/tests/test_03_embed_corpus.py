"""
Tests for pipeline/03_embed_corpus.py.

Expects the script to expose these module-level functions:
  - parse_release_year(date_str: str) -> int | None
  - extract_genres(genres_json: str) -> list[str]
  - load_metadata(path) -> dict[str, dict]   keys: title, movie_release_date, movie_genres
  - load_summaries(path) -> dict[str, str]
  - join_data(metadata_map, summaries_map) -> list[dict]
    each record: movie_id, title, release_year, genres (list), summary_snippet (str)

All external calls (Triton gRPC, file I/O beyond fixtures) are mocked.
"""

import json
import pathlib
import pytest
from unittest.mock import MagicMock, patch


@pytest.fixture(scope="module")
def mod(pipeline_loader):
    return pipeline_loader("03_embed_corpus.py")


# ---------------------------------------------------------------------------
# parse_release_year
# ---------------------------------------------------------------------------

class TestParseReleaseYear:
    def test_full_iso_date(self, mod):
        assert mod.parse_release_year("2000-11-22") == 2000

    def test_bare_year_returns_none(self, mod):
        assert mod.parse_release_year("1979") is None

    def test_empty_string_returns_none(self, mod):
        assert mod.parse_release_year("") is None

    def test_unparseable_string_returns_none(self, mod):
        assert mod.parse_release_year("unknown") is None

    def test_partial_date_returns_none(self, mod):
        assert mod.parse_release_year("2005-03") is None


# ---------------------------------------------------------------------------
# extract_genres
# ---------------------------------------------------------------------------

class TestExtractGenres:
    def test_multiple_genres(self, mod):
        genres_json = json.dumps({"/m/02l7c8": "Drama", "/m/0lsxr": "Adventure"})
        assert sorted(mod.extract_genres(genres_json)) == ["Adventure", "Drama"]

    def test_empty_object_returns_empty_list(self, mod):
        assert mod.extract_genres("{}") == []

    def test_single_genre(self, mod):
        genres_json = json.dumps({"/m/01jfsb": "Thriller"})
        assert mod.extract_genres(genres_json) == ["Thriller"]


# ---------------------------------------------------------------------------
# load_metadata / load_summaries
# ---------------------------------------------------------------------------

class TestDataLoading:
    def test_load_metadata_keyed_by_movie_id(self, mod, data_dir):
        path = data_dir / "data" / "raw" / "movie.metadata.tsv"
        result = mod.load_metadata(path)
        assert "111" in result
        assert result["111"]["title"] == "Cast Away"

    def test_load_summaries_keyed_by_movie_id(self, mod, data_dir):
        path = data_dir / "data" / "raw" / "plot_summaries.txt"
        result = mod.load_summaries(path)
        assert "111" in result
        assert "FedEx" in result["111"]

    def test_load_metadata_includes_all_rows(self, mod, data_dir):
        path = data_dir / "data" / "raw" / "movie.metadata.tsv"
        result = mod.load_metadata(path)
        assert len(result) == 4  # 111, 222, 333, 444


# ---------------------------------------------------------------------------
# join_data — inner join semantics
# ---------------------------------------------------------------------------

class TestJoinData:
    @pytest.fixture(autouse=True)
    def setup(self, mod, data_dir):
        meta_path = data_dir / "data" / "raw" / "movie.metadata.tsv"
        summ_path = data_dir / "data" / "raw" / "plot_summaries.txt"
        self.metadata = mod.load_metadata(meta_path)
        self.summaries = mod.load_summaries(summ_path)
        self.mod = mod

    def test_excludes_movies_without_summary(self):
        joined = self.mod.join_data(self.metadata, self.summaries)
        ids = {r["movie_id"] for r in joined}
        assert "333" not in ids  # metadata entry, no summary

    def test_excludes_summaries_without_metadata(self):
        joined = self.mod.join_data(self.metadata, self.summaries)
        ids = {r["movie_id"] for r in joined}
        assert "555" not in ids  # summary, no metadata

    def test_includes_matching_movies(self):
        joined = self.mod.join_data(self.metadata, self.summaries)
        ids = {r["movie_id"] for r in joined}
        assert "111" in ids
        assert "222" in ids

    def test_record_has_required_fields(self):
        joined = self.mod.join_data(self.metadata, self.summaries)
        for record in joined:
            assert "movie_id" in record
            assert "title" in record
            assert "release_year" in record
            assert "genres" in record
            assert isinstance(record["genres"], list)
            assert "summary_snippet" in record

    def test_release_year_parsed(self):
        joined = self.mod.join_data(self.metadata, self.summaries)
        cast_away = next(r for r in joined if r["movie_id"] == "111")
        assert cast_away["release_year"] == 2000

    def test_release_year_none_for_bad_date(self):
        meta = {"999": {"title": "X", "movie_release_date": "", "movie_genres": "{}"}}
        summ = {"999": "A summary."}
        joined = self.mod.join_data(meta, summ)
        assert joined[0]["release_year"] is None

    def test_summary_snippet_capped_at_300_chars(self):
        meta = {"999": {"title": "X", "movie_release_date": "2000-01-01", "movie_genres": "{}"}}
        summ = {"999": "Z" * 500}
        joined = self.mod.join_data(meta, summ)
        assert joined[0]["summary_snippet"] == "Z" * 300

    def test_short_summary_kept_as_is(self):
        meta = {"999": {"title": "X", "movie_release_date": "2000-01-01", "movie_genres": "{}"}}
        summ = {"999": "Short plot."}
        joined = self.mod.join_data(meta, summ)
        assert joined[0]["summary_snippet"] == "Short plot."
