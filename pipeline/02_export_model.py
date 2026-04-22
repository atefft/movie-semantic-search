#!/usr/bin/env python3
"""Export sentence-transformers/all-MiniLM-L6-v2 to ONNX for Triton."""

import os
import sys
import pathlib

import numpy as np
import torch
from transformers import AutoTokenizer, AutoModel
import onnxruntime


MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
OUTPUT_DIR = pathlib.Path("model-repository/all-minilm-l6-v2/1")
ONNX_PATH = OUTPUT_DIR / "model.onnx"


class _ModelWrapper(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids, attention_mask, token_type_ids):
        return self.model(
            input_ids=input_ids,
            attention_mask=attention_mask,
            token_type_ids=token_type_ids,
        ).last_hidden_state


def main():
    if ONNX_PATH.exists():
        print("Model already exported, skipping.", flush=True)
        sys.exit(0)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    print("Loading tokenizer...", flush=True)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)

    print("Loading model...", flush=True)
    model = AutoModel.from_pretrained(MODEL_NAME)
    model.eval()

    wrapper = _ModelWrapper(model)

    print("Preparing dummy inputs...", flush=True)
    dummy = tokenizer(
        "hello world",
        return_tensors="pt",
        padding="max_length",
        max_length=128,
        truncation=True,
    )
    input_ids = dummy["input_ids"]
    attention_mask = dummy["attention_mask"]
    token_type_ids = dummy.get("token_type_ids", torch.zeros_like(input_ids))

    print("Exporting ONNX model...", flush=True)
    torch.onnx.export(
        wrapper,
        (input_ids, attention_mask, token_type_ids),
        str(ONNX_PATH),
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["token_embeddings"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "sequence"},
            "attention_mask": {0: "batch", 1: "sequence"},
            "token_type_ids": {0: "batch", 1: "sequence"},
            "token_embeddings": {0: "batch", 1: "sequence"},
        },
        opset_version=14,
    )

    print("Saving tokenizer files...", flush=True)
    tokenizer.save_pretrained(str(OUTPUT_DIR))

    print("Verifying ONNX model...", flush=True)
    session = onnxruntime.InferenceSession(str(ONNX_PATH))
    feeds = {
        "input_ids": input_ids.numpy(),
        "attention_mask": attention_mask.numpy(),
        "token_type_ids": token_type_ids.numpy(),
    }
    token_embeddings = session.run(["token_embeddings"], feeds)[0]  # [1, seq, 384]
    mask = attention_mask.numpy().astype(float)
    pooled = (token_embeddings * mask[:, :, None]).sum(axis=1) / mask.sum(axis=1, keepdims=True)
    assert pooled.shape == (1, 384), f"Unexpected shape: {pooled.shape}"

    print("Done. model.onnx written.", flush=True)


if __name__ == "__main__":
    main()
