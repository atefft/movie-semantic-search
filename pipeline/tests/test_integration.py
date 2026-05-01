"""Integration tests for the full offline pipeline (steps 1–5)."""

import io
import json
import os
import subprocess
import sys
import tarfile
from unittest.mock import MagicMock, patch

import numpy as np
import pytest


@pytest.fixture(scope="module")
def mod_01(pipeline_loader): return pipeline_loader("01_download_corpus.py")

@pytest.fixture(scope="module")
def mod_02(pipeline_loader): return pipeline_loader("02_export_model.py")

@pytest.fixture(scope="module")
def mod_03(pipeline_loader): return pipeline_loader("03_embed_corpus.py")

@pytest.fixture(scope="module")
def mod_04(pipeline_loader): return pipeline_loader("04_ingest_qdrant.py")

@pytest.fixture(scope="module")
def mod_05(pipeline_loader): return pipeline_loader("05_enrich_tmdb.py")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_tar_gz(files: dict) -> bytes:
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for name, content in files.items():
            info = tarfile.TarInfo(name=f"MovieSummaries/{name}")
            info.size = len(content)
            tar.addfile(info, io.BytesIO(content))
    return buf.getvalue()


def _mock_urlopen(tar_bytes: bytes) -> MagicMock:
    resp = MagicMock()
    resp.__enter__ = lambda s: s
    resp.__exit__ = MagicMock(return_value=False)
    resp.read.side_effect = [tar_bytes, b""]
    resp.headers.get.return_value = str(len(tar_bytes))
    return resp


def _fake_tokenize(*args, **kwargs):
    texts = args[0] if args else ""
    B = len(texts) if isinstance(texts, list) else 1
    return {
        "input_ids": np.zeros((B, 128), dtype="int32"),
        "attention_mask": np.ones((B, 128), dtype="int32"),
    }


def _fake_infer_batch(grpcclient, client, input_ids, attention_mask, token_type_ids):
    B = input_ids.shape[0]
    return np.ones((B, 384), dtype="float32")


# ---------------------------------------------------------------------------
# Step 1: download_corpus
# ---------------------------------------------------------------------------

class TestStep1DownloadCorpus:
    def test_extracts_both_corpus_files(self, mod_01, tmp_path):
        tar_bytes = _make_tar_gz({
            "movie.metadata.tsv": b"111\tx\tCast Away\t2000-01-01\tx\tx\tx\tx\t{}\n",
            "plot_summaries.txt": b"111\tA survival story.\n",
        })
        with patch.object(mod_01.urllib.request, "urlopen", return_value=_mock_urlopen(tar_bytes)):
            mod_01.download_and_extract("http://fake", tmp_path)

        assert (tmp_path / "movie.metadata.tsv").exists()
        assert (tmp_path / "plot_summaries.txt").exists()
        assert (tmp_path / "movie.metadata.tsv").read_bytes() == b"111\tx\tCast Away\t2000-01-01\tx\tx\tx\tx\t{}\n"

    def test_idempotent_skips_when_already_downloaded(self, mod_01, tmp_path, monkeypatch):
        (tmp_path / "movie.metadata.tsv").write_text("data")
        (tmp_path / "plot_summaries.txt").write_text("data")
        monkeypatch.setattr(mod_01, "METADATA_PATH", tmp_path / "movie.metadata.tsv")
        monkeypatch.setattr(mod_01, "SUMMARIES_PATH", tmp_path / "plot_summaries.txt")

        with patch.object(mod_01, "download_and_extract") as mock_dl, \
             patch("sys.exit", side_effect=SystemExit(0)):
            with pytest.raises(SystemExit):
                mod_01.main()
        mock_dl.assert_not_called()


# ---------------------------------------------------------------------------
# Step 2: export_model (idempotency only)
# ---------------------------------------------------------------------------

class TestStep2ExportModel:
    def test_skips_export_when_onnx_exists(self, mod_02, tmp_path, monkeypatch):
        onnx_path = tmp_path / "model.onnx"
        onnx_path.write_bytes(b"fake")
        monkeypatch.setattr(mod_02, "ONNX_PATH", onnx_path)

        with patch("sys.exit", side_effect=SystemExit(0)):
            with pytest.raises(SystemExit) as exc_info:
                mod_02.main()
        assert exc_info.value.code == 0


# ---------------------------------------------------------------------------
# Step 3: embed_corpus
# ---------------------------------------------------------------------------

_METADATA_TSV = (
    "111\tx\tCast Away\t2000-01-01\tx\tx\tx\tx\t{}\n"
    "222\tx\tAlien\t1979-05-25\tx\tx\tx\tx\t{\"1\": \"Sci-Fi\"}\n"
)
_SUMMARIES_TXT = (
    "111\tA survival story.\n"
    "222\tA deadly alien creature.\n"
)


class TestStep3EmbedCorpus:
    def test_writes_embeddings_npy_and_metadata_json(self, mod_03, tmp_path, monkeypatch):
        meta_path = tmp_path / "movie.metadata.tsv"
        summ_path = tmp_path / "plot_summaries.txt"
        meta_path.write_text(_METADATA_TSV)
        summ_path.write_text(_SUMMARIES_TXT)

        emb_dir = tmp_path / "embeddings"
        emb_path = emb_dir / "embeddings.npy"
        meta_out = emb_dir / "metadata.json"

        monkeypatch.setattr(mod_03, "METADATA_PATH", meta_path)
        monkeypatch.setattr(mod_03, "SUMMARIES_PATH", summ_path)
        monkeypatch.setattr(mod_03, "EMBEDDINGS_DIR", emb_dir)
        monkeypatch.setattr(mod_03, "EMBEDDINGS_PATH", emb_path)
        monkeypatch.setattr(mod_03, "METADATA_OUT_PATH", meta_out)
        monkeypatch.setattr(mod_03, "_infer_batch", _fake_infer_batch)
        monkeypatch.setattr(sys, "argv", ["03_embed_corpus.py"])

        fake_tokenizer = MagicMock(side_effect=_fake_tokenize)
        mock_auto_tokenizer = MagicMock()
        mock_auto_tokenizer.from_pretrained.return_value = fake_tokenizer
        monkeypatch.setattr(sys.modules["transformers"], "AutoTokenizer", mock_auto_tokenizer)

        mod_03.main()

        assert emb_path.exists()
        assert meta_out.exists()
        embeddings = np.load(str(emb_path))
        assert embeddings.shape == (2, 384)
        records = json.loads(meta_out.read_text())
        assert len(records) == 2
        assert all("title" in r and "movie_id" in r for r in records)

    def test_idempotent_skips_when_outputs_exist(self, mod_03, tmp_path, monkeypatch):
        emb_path = tmp_path / "embeddings.npy"
        meta_out = tmp_path / "metadata.json"
        np.save(str(emb_path), np.zeros((1, 384), dtype="float32"))
        meta_out.write_text("[]")

        monkeypatch.setattr(mod_03, "EMBEDDINGS_PATH", emb_path)
        monkeypatch.setattr(mod_03, "METADATA_OUT_PATH", meta_out)
        monkeypatch.setattr(sys, "argv", ["03_embed_corpus.py"])

        with patch("sys.exit", side_effect=SystemExit(0)):
            with pytest.raises(SystemExit) as exc_info:
                mod_03.main()
        assert exc_info.value.code == 0


# ---------------------------------------------------------------------------
# Step 4: ingest_qdrant
# ---------------------------------------------------------------------------

def _write_ingest_fixtures(tmp_path):
    embeddings = np.array([[0.1] * 384, [0.2] * 384], dtype="float32")
    metadata = [
        {"movie_id": "111", "title": "Cast Away", "release_year": 2000, "genres": []},
        {"movie_id": "222", "title": "Alien", "release_year": 1979, "genres": []},
    ]
    data_dir = tmp_path / "data" / "embeddings"
    data_dir.mkdir(parents=True)
    np.save(str(data_dir / "embeddings.npy"), embeddings)
    (data_dir / "metadata.json").write_text(json.dumps(metadata))
    return embeddings, metadata


def _mock_qdrant(monkeypatch, mock_client):
    """Replace QdrantClient in the stub module with a callable returning mock_client."""
    mock_cls = MagicMock(return_value=mock_client)
    monkeypatch.setattr(sys.modules["qdrant_client"], "QdrantClient", mock_cls)


class TestStep4IngestQdrant:
    def test_setup_collection_then_upsert(self, mod_04, tmp_path, monkeypatch):
        _write_ingest_fixtures(tmp_path)
        mock_client = MagicMock()
        _mock_qdrant(monkeypatch, mock_client)

        monkeypatch.setattr(sys, "argv", ["04_ingest_qdrant.py"])
        monkeypatch.chdir(tmp_path)

        mod_04.main()

        assert mock_client.recreate_collection.called
        assert mock_client.upsert.called
        call_names = [str(c) for c in mock_client.method_calls]
        setup_idx = next(i for i, n in enumerate(call_names) if "recreate_collection" in n)
        upsert_idx = next(i for i, n in enumerate(call_names) if "upsert" in n)
        assert setup_idx < upsert_idx

    def test_upsert_receives_all_points(self, mod_04, tmp_path, monkeypatch):
        _write_ingest_fixtures(tmp_path)
        mock_client = MagicMock()
        _mock_qdrant(monkeypatch, mock_client)

        monkeypatch.setattr(sys, "argv", ["04_ingest_qdrant.py"])
        monkeypatch.chdir(tmp_path)

        mod_04.main()

        all_points = []
        for call in mock_client.upsert.call_args_list:
            pts = call.kwargs.get("points") or (call.args[1] if len(call.args) > 1 else [])
            all_points.extend(pts)
        assert len(all_points) == 2

    def test_idempotent_reruns_without_error(self, mod_04, tmp_path, monkeypatch):
        _write_ingest_fixtures(tmp_path)
        _mock_qdrant(monkeypatch, MagicMock())

        monkeypatch.setattr(sys, "argv", ["04_ingest_qdrant.py"])
        monkeypatch.chdir(tmp_path)

        mod_04.main()
        mod_04.main()


# ---------------------------------------------------------------------------
# Step 5: enrich_tmdb
# ---------------------------------------------------------------------------

def _patch_qdrant_models():
    qdrant_models = sys.modules.get("qdrant_client.models")
    if qdrant_models is not None:
        qdrant_models.Filter = MagicMock
        qdrant_models.FieldCondition = MagicMock
        qdrant_models.MatchValue = MagicMock


class TestStep5EnrichTmdb:
    def test_sets_payload_only_for_points_with_poster(self, mod_05, monkeypatch):
        _patch_qdrant_models()

        point_with_poster = MagicMock()
        point_with_poster.id = 1
        point_with_poster.payload = {"title": "Cast Away", "release_year": 2000}

        point_no_poster = MagicMock()
        point_no_poster.id = 2
        point_no_poster.payload = {"title": "Unknown", "release_year": None}

        mock_client = MagicMock()
        mock_client.scroll.side_effect = [
            ([point_with_poster, point_no_poster], None),
            ([], None),
        ]
        _mock_qdrant(monkeypatch, mock_client)

        def _fake_get(url, params=None, **kwargs):
            resp = MagicMock()
            if (params or {}).get("query") == "Cast Away":
                resp.json.return_value = {"results": [{"poster_path": "/abc.jpg"}]}
            else:
                resp.json.return_value = {"results": []}
            return resp

        mock_session = MagicMock()
        mock_session.get.side_effect = _fake_get

        monkeypatch.setenv("TMDB_API_KEY", "fake_key")
        monkeypatch.setattr(sys, "argv", ["05_enrich_tmdb.py"])

        with patch("requests.Session", return_value=mock_session), \
             patch("time.sleep"):
            mod_05.main()

        assert mock_client.set_payload.call_count == 1
        call_kw = mock_client.set_payload.call_args.kwargs
        assert call_kw["payload"]["thumbnail_url"] == "/abc.jpg"
        assert call_kw["points"] == [1]

    def test_does_not_set_payload_when_no_poster(self, mod_05, monkeypatch):
        _patch_qdrant_models()

        point = MagicMock()
        point.id = 3
        point.payload = {"title": "No Poster Film", "release_year": None}

        mock_client = MagicMock()
        mock_client.scroll.side_effect = [([point], None), ([], None)]
        _mock_qdrant(monkeypatch, mock_client)

        mock_session = MagicMock()
        mock_session.get.return_value.json.return_value = {"results": []}

        monkeypatch.setenv("TMDB_API_KEY", "fake_key")
        monkeypatch.setattr(sys, "argv", ["05_enrich_tmdb.py"])

        with patch("requests.Session", return_value=mock_session), \
             patch("time.sleep"):
            mod_05.main()

        mock_client.set_payload.assert_not_called()

    def test_idempotent_no_error_on_rerun(self, mod_05, monkeypatch):
        _patch_qdrant_models()

        mock_client_1 = MagicMock()
        mock_client_1.scroll.return_value = ([], None)
        mock_client_2 = MagicMock()
        mock_client_2.scroll.return_value = ([], None)
        mock_cls = MagicMock(side_effect=[mock_client_1, mock_client_2])
        monkeypatch.setattr(sys.modules["qdrant_client"], "QdrantClient", mock_cls)

        mock_session = MagicMock()
        mock_session.get.return_value.json.return_value = {"results": []}

        monkeypatch.setenv("TMDB_API_KEY", "fake_key")
        monkeypatch.setattr(sys, "argv", ["05_enrich_tmdb.py"])

        with patch("requests.Session", return_value=mock_session), \
             patch("time.sleep"):
            mod_05.main()
            mod_05.main()


# ---------------------------------------------------------------------------
# Docker image smoke tests (integration-test label)
# ---------------------------------------------------------------------------

IMAGE = "pipeline-integration-test"


def setup_module(module):
    result = subprocess.run(
        ["docker", "build", "-t", IMAGE, "pipeline/"],
        capture_output=True, text=True
    )
    assert result.returncode == 0, result.stderr


def teardown_module(module):
    subprocess.run(["docker", "rmi", "-f", IMAGE], capture_output=True)


def test_all_deps_importable():
    result = subprocess.run(
        ["docker", "run", "--rm", IMAGE,
         "python", "-c",
         "import transformers, torch, onnxruntime, tritonclient.grpc, tqdm, qdrant_client, requests"],
        capture_output=True, text=True
    )
    assert result.returncode == 0, result.stderr


def test_scripts_present():
    scripts = ["01_download_corpus.py", "02_export_model.py", "03_embed_corpus.py",
               "04_ingest_qdrant.py", "05_enrich_tmdb.py"]
    result = subprocess.run(
        ["docker", "run", "--rm", IMAGE, "ls"] + scripts,
        capture_output=True, text=True
    )
    assert result.returncode == 0, result.stderr


def test_workdir():
    result = subprocess.run(
        ["docker", "inspect", IMAGE, "--format", "{{.Config.WorkingDir}}"],
        capture_output=True, text=True
    )
    assert result.stdout.strip() == "/app"


def test_no_entrypoint():
    result = subprocess.run(
        ["docker", "inspect", IMAGE, "--format", "{{.Config.Entrypoint}}"],
        capture_output=True, text=True
    )
    assert result.stdout.strip() == "[]"


def test_no_cmd():
    result = subprocess.run(
        ["docker", "inspect", IMAGE, "--format", "{{.Config.Cmd}}"],
        capture_output=True, text=True
    )
    assert result.stdout.strip() == "[]"


def test_wrong_context_fails():
    result = subprocess.run(
        ["docker", "build", "-f", "pipeline/Dockerfile", "."],
        capture_output=True, text=True
    )
    assert result.returncode != 0
    assert "COPY" in result.stderr or "not found" in result.stderr.lower()
