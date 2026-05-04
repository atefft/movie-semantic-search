"""Tests for pipeline/03_embed_corpus.py."""

from unittest.mock import MagicMock

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


# ---------------------------------------------------------------------------
# chunk_summary
# ---------------------------------------------------------------------------

class TestChunkSummary:
    def _make_tokenizer(self, token_ids):
        tok = MagicMock()
        tok.return_value = {"input_ids": token_ids}
        tok.decode.return_value = "decoded"
        return tok

    def test_empty_string_returns_empty_without_calling_tokenizer(self, mod):
        tok = self._make_tokenizer([1, 2, 3])
        assert mod.chunk_summary("", tok) == []
        tok.assert_not_called()

    def test_whitespace_returns_empty_without_calling_tokenizer(self, mod):
        tok = self._make_tokenizer([1, 2, 3])
        assert mod.chunk_summary("   ", tok) == []
        tok.assert_not_called()

    def test_short_text_returns_single_chunk(self, mod):
        tok = self._make_tokenizer([1, 2, 3])
        tok.decode.return_value = "short summary"
        result = mod.chunk_summary("short text", tok, window_size=512, overlap=64)
        assert result == ["short summary"]

    def test_text_exactly_filling_window_returns_single_chunk(self, mod):
        tok = self._make_tokenizer(list(range(10)))
        result = mod.chunk_summary("text", tok, window_size=10, overlap=2)
        assert len(result) == 1

    def test_happy_path_three_chunks(self, mod):
        # 20 tokens, window=10, overlap=2, step=8 → 3 chunks
        tok = self._make_tokenizer(list(range(1, 21)))
        tok.decode.side_effect = ["first ten tokens", "overlapping middle", "final tail"]
        result = mod.chunk_summary("long summary text", tok, window_size=10, overlap=2, max_chunks=10)
        assert result == ["first ten tokens", "overlapping middle", "final tail"]
        assert tok.decode.call_count == 3

    def test_max_chunks_cap_enforced(self, mod):
        # 50 tokens, window=10, overlap=2, step=8 → 6 chunks, capped at 3
        tok = self._make_tokenizer(list(range(50)))
        result = mod.chunk_summary("text", tok, window_size=10, overlap=2, max_chunks=3)
        assert len(result) == 3

    def test_15_chunks_with_max_10_returns_exactly_10(self, mod):
        # 120 tokens, window=10, overlap=2, step=8 → 15 chunks, capped at 10
        tok = self._make_tokenizer(list(range(120)))
        result = mod.chunk_summary("text", tok, window_size=10, overlap=2, max_chunks=10)
        assert len(result) == 10

    def test_overlap_boundary_second_chunk_starts_at_step(self, mod):
        # step = window_size - overlap = 10 - 2 = 8
        tok = self._make_tokenizer(list(range(20)))
        mod.chunk_summary("text", tok, window_size=10, overlap=2, max_chunks=10)
        calls = tok.decode.call_args_list
        assert list(calls[0][0][0]) == list(range(0, 10))
        assert list(calls[1][0][0]) == list(range(8, 18))

    def test_overlap_ge_window_raises_valueerror(self, mod):
        tok = self._make_tokenizer([])
        with pytest.raises(ValueError):
            mod.chunk_summary("text", tok, window_size=10, overlap=10)

    def test_overlap_gt_window_raises_valueerror(self, mod):
        tok = self._make_tokenizer([])
        with pytest.raises(ValueError):
            mod.chunk_summary("text", tok, window_size=10, overlap=11)

    def test_max_chunks_zero_raises_valueerror(self, mod):
        tok = self._make_tokenizer([])
        with pytest.raises(ValueError):
            mod.chunk_summary("text", tok, max_chunks=0)

    def test_max_chunks_negative_raises_valueerror(self, mod):
        tok = self._make_tokenizer([])
        with pytest.raises(ValueError):
            mod.chunk_summary("text", tok, max_chunks=-1)

    def test_returns_decoded_strings(self, mod):
        tok = self._make_tokenizer([1, 2, 3])
        tok.decode.return_value = "decoded string"
        result = mod.chunk_summary("some text", tok)
        assert result == ["decoded string"]
        assert all(isinstance(s, str) for s in result)
