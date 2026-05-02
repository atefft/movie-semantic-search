#!/usr/bin/env python3
"""Embed movie plot summaries via Triton gRPC and write embeddings."""

import argparse
import json
import pathlib
import re
import sys

import numpy as np


MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
TRITON_MODEL = "all-minilm-l6-v2"
MAX_LENGTH = 128
EMBEDDING_DIM = 384

METADATA_PATH = pathlib.Path("data/raw/movie.metadata.tsv")
SUMMARIES_PATH = pathlib.Path("data/raw/plot_summaries.txt")
EMBEDDINGS_DIR = pathlib.Path("data/embeddings")
EMBEDDINGS_PATH = EMBEDDINGS_DIR / "embeddings.npy"
METADATA_OUT_PATH = EMBEDDINGS_DIR / "metadata.json"


def parse_release_year(raw: str) -> int | None:
    if not raw:
        return None
    m = re.fullmatch(r"(\d{4})-(\d{2})-(\d{2})", raw)
    if m is None:
        return None
    return int(m.group(1))


def extract_genres(genres_json: str) -> list[str]:
    return list(json.loads(genres_json).values())


def load_metadata(path) -> dict[str, dict]:
    result = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            cols = line.rstrip("\n").split("\t")
            result[cols[0]] = {
                "title": cols[2],
                "movie_release_date": cols[3],
                "movie_genres": cols[8],
            }
    return result


def load_summaries(path) -> dict[str, str]:
    result = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            cols = line.rstrip("\n").split("\t", 1)
            result[cols[0]] = cols[1] if len(cols) > 1 else ""
    return result


def join_data(metadata_map, summaries_map) -> list[dict]:
    records = []
    for movie_id, meta in metadata_map.items():
        if movie_id not in summaries_map:
            continue
        summary = summaries_map[movie_id]
        records.append({
            "movie_id": movie_id,
            "title": meta["title"],
            "release_year": parse_release_year(meta["movie_release_date"]),
            "genres": extract_genres(meta["movie_genres"]),
            "summary_snippet": summary[:300],
        })
    return records


def _infer_batch(grpcclient, client, input_ids, attention_mask, token_type_ids):
    B = input_ids.shape[0]
    inputs = [
        grpcclient.InferInput("input_ids", [B, MAX_LENGTH], "INT64"),
        grpcclient.InferInput("attention_mask", [B, MAX_LENGTH], "INT64"),
        grpcclient.InferInput("token_type_ids", [B, MAX_LENGTH], "INT64"),
    ]
    inputs[0].set_data_from_numpy(input_ids)
    inputs[1].set_data_from_numpy(attention_mask)
    inputs[2].set_data_from_numpy(token_type_ids)
    outputs = [grpcclient.InferRequestedOutput("token_embeddings")]
    result = client.infer(model_name=TRITON_MODEL, inputs=inputs, outputs=outputs)
    token_embeddings = result.as_numpy("token_embeddings")  # [B, seq, 384]
    mask = attention_mask[:, :, None].astype(float)
    pooled = (token_embeddings * mask).sum(axis=1) / mask.sum(axis=1)
    return pooled.astype("float32")


def main():
    parser = argparse.ArgumentParser(description="Embed movie corpus via Triton gRPC.")
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--triton-host", default="localhost")
    parser.add_argument("--triton-port", type=int, default=8001)
    args = parser.parse_args()

    if EMBEDDINGS_PATH.exists() and METADATA_OUT_PATH.exists():
        print("Output files already exist, skipping.", flush=True)
        sys.exit(0)

    from transformers import AutoTokenizer
    from tqdm.auto import tqdm
    import tritonclient.grpc as grpcclient

    records = join_data(load_metadata(METADATA_PATH), load_summaries(SUMMARIES_PATH))

    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    client = grpcclient.InferenceServerClient(url=f"{args.triton_host}:{args.triton_port}")

    dummy_enc = tokenizer(
        "warmup",
        return_tensors="np",
        padding="max_length",
        max_length=MAX_LENGTH,
        truncation=True,
    )
    _infer_batch(
        grpcclient,
        client,
        dummy_enc["input_ids"].astype("int64"),
        dummy_enc["attention_mask"].astype("int64"),
        dummy_enc.get("token_type_ids", np.zeros_like(dummy_enc["input_ids"])).astype("int64"),
    )

    all_embeddings = []
    texts = [r["summary_snippet"] for r in records]
    for i in tqdm(range(0, len(texts), args.batch_size), desc="Embedding"):
        batch = texts[i : i + args.batch_size]
        enc = tokenizer(
            batch,
            return_tensors="np",
            padding="max_length",
            max_length=MAX_LENGTH,
            truncation=True,
        )
        input_ids = enc["input_ids"].astype("int64")
        attention_mask = enc["attention_mask"].astype("int64")
        token_type_ids = enc.get("token_type_ids", np.zeros_like(input_ids)).astype("int64")
        all_embeddings.append(_infer_batch(grpcclient, client, input_ids, attention_mask, token_type_ids))

    embeddings = np.concatenate(all_embeddings, axis=0).astype("float32")

    EMBEDDINGS_DIR.mkdir(parents=True, exist_ok=True)
    np.save(str(EMBEDDINGS_PATH), embeddings)
    with open(METADATA_OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(records, f)

    print(f"Wrote {embeddings.shape[0]} embeddings to {EMBEDDINGS_PATH}.", flush=True)


if __name__ == "__main__":
    main()
