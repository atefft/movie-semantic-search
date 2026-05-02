#!/usr/bin/env python3
import argparse
import json
import numpy as np
from qdrant_client.http.models import PointStruct


def build_points(embeddings: np.ndarray, metadata: list) -> list:
    return [
        PointStruct(
            id=i,
            vector=embeddings[i].tolist(),
            payload={**metadata[i], "thumbnail_url": None},
        )
        for i in range(len(embeddings))
    ]


def setup_collection(client, collection_name: str):
    from qdrant_client.http.models import Distance, VectorParams

    client.recreate_collection(
        collection_name=collection_name,
        vectors_config=VectorParams(size=384, distance=Distance.COSINE),
    )


def main():
    parser = argparse.ArgumentParser(description="Ingest embeddings into Qdrant.")
    parser.add_argument("--qdrant-url", default="http://localhost:6333")
    args = parser.parse_args()

    from qdrant_client import QdrantClient
    from tqdm import tqdm

    embeddings = np.load("data/embeddings/embeddings.npy")
    with open("data/embeddings/metadata.json") as f:
        metadata = json.load(f)

    client = QdrantClient(url=args.qdrant_url)
    setup_collection(client, "movies")
    points = build_points(embeddings, metadata)

    batch_size = 256
    for i in tqdm(range(0, len(points), batch_size)):
        batch = points[i : i + batch_size]
        client.upsert(collection_name="movies", points=batch)


if __name__ == "__main__":
    main()
