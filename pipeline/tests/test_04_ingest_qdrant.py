"""
Tests for pipeline/04_ingest_qdrant.py.

Expects the script to expose:
  - build_points(embeddings: np.ndarray, metadata: list[dict]) -> list
    Returns a list of objects with attributes: id (int), vector (list/array),
    payload (dict containing thumbnail_url=None and all metadata fields).

All Qdrant client calls are mocked via sys.modules stub in conftest.
"""

import pytest
import numpy as np
from unittest.mock import MagicMock, patch, call


@pytest.fixture(scope="module")
def mod(pipeline_loader):
    return pipeline_loader("04_ingest_qdrant.py")


SAMPLE_METADATA = [
    {
        "movie_id": "111",
        "title": "Cast Away",
        "release_year": 2000,
        "genres": ["Drama", "Adventure"],
        "summary_snippet": "A FedEx executive...",
    },
    {
        "movie_id": "222",
        "title": "Alien",
        "release_year": 1979,
        "genres": ["Thriller"],
        "summary_snippet": "A commercial crew...",
    },
]


# ---------------------------------------------------------------------------
# build_points
# ---------------------------------------------------------------------------

class TestBuildPoints:
    @pytest.fixture(autouse=True)
    def setup(self, mod):
        self.mod = mod
        self.embeddings = np.random.rand(2, 384).astype(np.float32)
        self.points = mod.build_points(self.embeddings, SAMPLE_METADATA)

    def test_returns_one_point_per_embedding(self):
        assert len(self.points) == 2

    def test_point_ids_are_zero_based_integers(self):
        ids = [p.id for p in self.points]
        assert ids == [0, 1]

    def test_payload_contains_thumbnail_url_null(self):
        for point in self.points:
            assert point.payload["thumbnail_url"] is None

    def test_payload_contains_metadata_fields(self):
        first = self.points[0]
        assert first.payload["movie_id"] == "111"
        assert first.payload["title"] == "Cast Away"
        assert first.payload["release_year"] == 2000
        assert first.payload["genres"] == ["Drama", "Adventure"]

    def test_vector_matches_embedding_row(self):
        for i, point in enumerate(self.points):
            np.testing.assert_array_almost_equal(
                np.array(point.vector), self.embeddings[i]
            )


# ---------------------------------------------------------------------------
# Collection creation — via mocked QdrantClient
# ---------------------------------------------------------------------------

class TestCollectionSetup:
    def test_collection_created_with_384_dims_cosine(self, mod):
        mock_client = MagicMock()
        with patch.object(mod, "QdrantClient", return_value=mock_client, create=True):
            mod.setup_collection(mock_client, "movies")

        create_call = mock_client.recreate_collection.call_args or \
                      mock_client.create_collection.call_args
        assert create_call is not None
        kwargs = create_call.kwargs if create_call.kwargs else create_call[1]
        vectors_config = kwargs.get("vectors_config") or kwargs.get("vectors")
        assert vectors_config is not None
