"""Tests for pipeline/03_embed_corpus.py."""

import pytest


@pytest.fixture(scope="module")
def mod(pipeline_loader):
    return pipeline_loader("03_embed_corpus.py")


# ---------------------------------------------------------------------------
# parse_release_year
# ---------------------------------------------------------------------------

class TestParseReleaseYear:
    def test_valid_yyyy_mm_dd(self, mod):
        assert mod.parse_release_year("2000-11-22") == 2000

    def test_bare_year_returns_none(self, mod):
        assert mod.parse_release_year("1979") is None

    def test_empty_string_returns_none(self, mod):
        assert mod.parse_release_year("") is None

    def test_unknown_string_returns_none(self, mod):
        assert mod.parse_release_year("unknown") is None

    def test_yyyy_mm_returns_none(self, mod):
        assert mod.parse_release_year("2005-03") is None


# ---------------------------------------------------------------------------
# extract_genres
# ---------------------------------------------------------------------------

class TestExtractGenres:
    def test_returns_genre_values(self, mod):
        result = mod.extract_genres('{"k": "Drama", "k2": "Adventure"}')
        assert sorted(result) == ["Adventure", "Drama"]

    def test_empty_object_returns_empty_list(self, mod):
        assert mod.extract_genres("{}") == []


# ---------------------------------------------------------------------------
# load_metadata
# ---------------------------------------------------------------------------

class TestLoadMetadata:
    @pytest.fixture
    def metadata_file(self, tmp_path):
        # 9 tab-separated columns: id, _, title, release_date, _, _, _, _, genres
        rows = [
            "111\tx\tCast Away\t2000-11-22\tx\tx\tx\tx\t{}",
            "222\tx\tAlien\t1979\tx\tx\tx\tx\t{}",
            "333\tx\tUnknown\t\tx\tx\tx\tx\t{}",
            "555\tx\tOrphan\t2001-05-01\tx\tx\tx\tx\t{}",
        ]
        p = tmp_path / "movie.metadata.tsv"
        p.write_text("\n".join(rows) + "\n", encoding="utf-8")
        return p

    def test_keyed_by_movie_id(self, mod, metadata_file):
        result = mod.load_metadata(metadata_file)
        assert "111" in result

    def test_title_value(self, mod, metadata_file):
        result = mod.load_metadata(metadata_file)
        assert result["111"]["title"] == "Cast Away"

    def test_row_count(self, mod, metadata_file):
        result = mod.load_metadata(metadata_file)
        assert len(result) == 4


# ---------------------------------------------------------------------------
# load_summaries
# ---------------------------------------------------------------------------

class TestLoadSummaries:
    @pytest.fixture
    def summaries_file(self, tmp_path):
        rows = [
            "111\tChuck Noland works for FedEx and crash-lands on an island.",
            "222\tIn space, no one can hear you scream.",
            "333\tA short summary.",
        ]
        p = tmp_path / "plot_summaries.txt"
        p.write_text("\n".join(rows) + "\n", encoding="utf-8")
        return p

    def test_keyed_by_movie_id(self, mod, summaries_file):
        result = mod.load_summaries(summaries_file)
        assert "111" in result

    def test_summary_contains_expected_text(self, mod, summaries_file):
        result = mod.load_summaries(summaries_file)
        assert "FedEx" in result["111"]


# ---------------------------------------------------------------------------
# join_data
# ---------------------------------------------------------------------------

class TestJoinData:
    @pytest.fixture
    def metadata_map(self):
        return {
            "111": {"title": "Cast Away", "movie_release_date": "2000-11-22", "movie_genres": '{"k": "Drama"}'},
            "222": {"title": "Alien", "movie_release_date": "1979", "movie_genres": "{}"},
            "333": {"title": "No Summary Here", "movie_release_date": "2001-01-01", "movie_genres": "{}"},
        }

    @pytest.fixture
    def summaries_map(self):
        return {
            "111": "Chuck Noland works for FedEx.",
            "222": "In space, no one can hear you scream.",
            "555": "Orphan entry with no metadata.",
        }

    def test_includes_matched_records(self, mod, metadata_map, summaries_map):
        result = mod.join_data(metadata_map, summaries_map)
        ids = [r["movie_id"] for r in result]
        assert "111" in ids
        assert "222" in ids

    def test_excludes_record_without_summary(self, mod, metadata_map, summaries_map):
        result = mod.join_data(metadata_map, summaries_map)
        ids = [r["movie_id"] for r in result]
        assert "333" not in ids

    def test_excludes_summary_without_metadata(self, mod, metadata_map, summaries_map):
        result = mod.join_data(metadata_map, summaries_map)
        ids = [r["movie_id"] for r in result]
        assert "555" not in ids

    def test_record_has_required_fields(self, mod, metadata_map, summaries_map):
        result = mod.join_data(metadata_map, summaries_map)
        rec = next(r for r in result if r["movie_id"] == "111")
        assert rec["title"] == "Cast Away"
        assert isinstance(rec["genres"], list)
        assert isinstance(rec["summary_snippet"], str)

    def test_release_year_parsed_from_date(self, mod, metadata_map, summaries_map):
        result = mod.join_data(metadata_map, summaries_map)
        rec = next(r for r in result if r["movie_id"] == "111")
        assert rec["release_year"] == 2000

    def test_release_year_none_for_invalid_date(self, mod, metadata_map, summaries_map):
        result = mod.join_data(metadata_map, summaries_map)
        rec = next(r for r in result if r["movie_id"] == "222")
        assert rec["release_year"] is None

    def test_summary_snippet_capped_at_300_chars(self, mod, metadata_map):
        long_summaries = {"111": "x" * 400, "222": "short"}
        result = mod.join_data(metadata_map, long_summaries)
        rec = next(r for r in result if r["movie_id"] == "111")
        assert len(rec["summary_snippet"]) == 300

    def test_short_summary_kept_as_is(self, mod, metadata_map, summaries_map):
        result = mod.join_data(metadata_map, summaries_map)
        rec = next(r for r in result if r["movie_id"] == "222")
        assert rec["summary_snippet"] == "In space, no one can hear you scream."
