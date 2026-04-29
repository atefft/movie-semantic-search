"""Tests for pipeline/01_download_corpus.py."""

import io
import tarfile
from unittest.mock import MagicMock, patch

import pytest


@pytest.fixture(scope="module")
def mod(pipeline_loader):
    return pipeline_loader("01_download_corpus.py")


# ---------------------------------------------------------------------------
# is_already_downloaded
# ---------------------------------------------------------------------------

class TestIsAlreadyDownloaded:
    def test_true_when_both_files_exist(self, mod, tmp_path, monkeypatch):
        metadata = tmp_path / "movie.metadata.tsv"
        summaries = tmp_path / "plot_summaries.txt"
        metadata.write_text("data")
        summaries.write_text("data")
        monkeypatch.setattr(mod, "METADATA_PATH", metadata)
        monkeypatch.setattr(mod, "SUMMARIES_PATH", summaries)
        assert mod.is_already_downloaded() is True

    def test_false_when_metadata_missing(self, mod, tmp_path, monkeypatch):
        metadata = tmp_path / "movie.metadata.tsv"
        summaries = tmp_path / "plot_summaries.txt"
        summaries.write_text("data")
        monkeypatch.setattr(mod, "METADATA_PATH", metadata)
        monkeypatch.setattr(mod, "SUMMARIES_PATH", summaries)
        assert mod.is_already_downloaded() is False

    def test_false_when_summaries_missing(self, mod, tmp_path, monkeypatch):
        metadata = tmp_path / "movie.metadata.tsv"
        summaries = tmp_path / "plot_summaries.txt"
        metadata.write_text("data")
        monkeypatch.setattr(mod, "METADATA_PATH", metadata)
        monkeypatch.setattr(mod, "SUMMARIES_PATH", summaries)
        assert mod.is_already_downloaded() is False

    def test_false_when_both_missing(self, mod, tmp_path, monkeypatch):
        monkeypatch.setattr(mod, "METADATA_PATH", tmp_path / "movie.metadata.tsv")
        monkeypatch.setattr(mod, "SUMMARIES_PATH", tmp_path / "plot_summaries.txt")
        assert mod.is_already_downloaded() is False


# ---------------------------------------------------------------------------
# download_and_extract
# ---------------------------------------------------------------------------

def _make_tar_gz(files: dict) -> bytes:
    """Build an in-memory .tar.gz with filename → bytes content."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for name, content in files.items():
            info = tarfile.TarInfo(name=f"MovieSummaries/{name}")
            info.size = len(content)
            tar.addfile(info, io.BytesIO(content))
    return buf.getvalue()


def _mock_response(tar_bytes: bytes) -> MagicMock:
    mock_resp = MagicMock()
    mock_resp.__enter__ = lambda s: s
    mock_resp.__exit__ = MagicMock(return_value=False)
    mock_resp.read.side_effect = [tar_bytes, b""]
    mock_resp.headers.get.return_value = str(len(tar_bytes))
    return mock_resp


class TestDownloadAndExtract:
    def test_extracts_metadata_and_summaries(self, mod, tmp_path):
        tar_bytes = _make_tar_gz({
            "movie.metadata.tsv": b"111\tx\tCast Away\n",
            "plot_summaries.txt": b"111\tSummary text\n",
        })
        with patch.object(mod.urllib.request, "urlopen", return_value=_mock_response(tar_bytes)):
            mod.download_and_extract("http://fake-url", tmp_path)

        assert (tmp_path / "movie.metadata.tsv").read_bytes() == b"111\tx\tCast Away\n"
        assert (tmp_path / "plot_summaries.txt").read_bytes() == b"111\tSummary text\n"

    def test_archive_removed_after_extraction(self, mod, tmp_path):
        tar_bytes = _make_tar_gz({
            "movie.metadata.tsv": b"data\n",
            "plot_summaries.txt": b"data\n",
        })
        with patch.object(mod.urllib.request, "urlopen", return_value=_mock_response(tar_bytes)):
            mod.download_and_extract("http://fake-url", tmp_path)

        assert not (tmp_path / "MovieSummaries.tar.gz").exists()


# ---------------------------------------------------------------------------
# main — idempotency
# ---------------------------------------------------------------------------

class TestMainIdempotent:
    def test_exits_early_when_already_downloaded(self, mod):
        with patch.object(mod, "is_already_downloaded", return_value=True), \
             patch.object(mod, "download_and_extract") as mock_dl, \
             patch("sys.exit", side_effect=SystemExit(0)):
            with pytest.raises(SystemExit):
                mod.main()
        mock_dl.assert_not_called()

    def test_calls_download_when_not_present(self, mod):
        with patch.object(mod, "is_already_downloaded", return_value=False), \
             patch.object(mod, "download_and_extract") as mock_dl:
            mod.main()
        mock_dl.assert_called_once()
