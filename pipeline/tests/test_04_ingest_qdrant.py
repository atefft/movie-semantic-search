"""Tests for pipeline/04_ingest_qdrant.py."""

import numpy as np
import pytest
from unittest.mock import MagicMock


@pytest.fixture(scope="module")
def mod(pipeline_loader):
    return pipeline_loader("04_ingest_qdrant.py")


@pytest.fixture
def sample_embeddings():
    return np.array([[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]], dtype="float32")


@pytest.fixture
def sample_metadata():
    return [
        {"movie_id": "111", "title": "Cast Away", "release_year": 2000, "genres": ["Drama"]},
        {"movie_id": "222", "title": "Alien", "release_year": None, "genres": []},
    ]


# ---------------------------------------------------------------------------
# build_points
# ---------------------------------------------------------------------------

class TestBuildPoints:
    def test_returns_one_point_per_embedding(self, mod, sample_embeddings, sample_metadata):
        points = mod.build_points(sample_embeddings, sample_metadata)
        assert len(points) == 2

    def test_ids_are_zero_based_integers(self, mod, sample_embeddings, sample_metadata):
        points = mod.build_points(sample_embeddings, sample_metadata)
        assert [p.id for p in points] == [0, 1]

    def test_thumbnail_url_is_none(self, mod, sample_embeddings, sample_metadata):
        points = mod.build_points(sample_embeddings, sample_metadata)
        for p in points:
            assert p.payload["thumbnail_url"] is None

    def test_payload_contains_metadata_fields(self, mod, sample_embeddings, sample_metadata):
        points = mod.build_points(sample_embeddings, sample_metadata)
        p = points[0]
        assert p.payload["movie_id"] == "111"
        assert p.payload["title"] == "Cast Away"
        assert p.payload["release_year"] == 2000
        assert p.payload["genres"] == ["Drama"]

    def test_vector_matches_embedding_row(self, mod, sample_embeddings, sample_metadata):
        points = mod.build_points(sample_embeddings, sample_metadata)
        np.testing.assert_allclose(points[0].vector, sample_embeddings[0].tolist())
        np.testing.assert_allclose(points[1].vector, sample_embeddings[1].tolist())


# ---------------------------------------------------------------------------
# setup_collection
# ---------------------------------------------------------------------------

class TestCollectionSetup:
    def test_collection_created_with_384_dims_cosine(self, mod):
        mock_client = MagicMock()
        mod.setup_collection(mock_client, "movies")

        called = mock_client.recreate_collection.called or mock_client.create_collection.called
        assert called, "Expected recreate_collection or create_collection to be called"

        call_args = (
            mock_client.recreate_collection.call_args
            if mock_client.recreate_collection.called
            else mock_client.create_collection.call_args
        )

        vectors_config = call_args.kwargs.get("vectors_config") or call_args.kwargs.get("vectors")
        assert vectors_config is not None, "vectors_config not passed to collection call"
        assert vectors_config.size == 384
        assert vectors_config.distance == "Cosine"
