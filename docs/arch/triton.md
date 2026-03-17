# Triton Inference Server Component

Serves `all-MiniLM-L6-v2` over gRPC. Accepts raw text strings, returns 384-dimensional float32 embedding vectors.

```mermaid
flowchart LR
  IN["Input\nTYPE_BYTES\ndims: [1]\nraw text string"]
  TOK["Tokenize\nHuggingFace tokenizer\nmax_length=128\npadding + truncation"]
  ENC["Encode\nONNX Runtime\nall-MiniLM-L6-v2"]
  POOL["Mean Pool\nattention-mask weighted\naverage of token embeddings"]
  NORM["L2 Normalize\nunit vector"]
  OUT["Output\nTYPE_FP32\ndims: [384]\nsentence_embedding"]

  IN --> TOK --> ENC --> POOL --> NORM --> OUT
```

## `config.pbtxt`

```protobuf
name: "all-minilm-l6-v2"
backend: "python"

input [
  {
    name: "TEXT"
    data_type: TYPE_BYTES
    dims: [1]
  }
]

output [
  {
    name: "sentence_embedding"
    data_type: TYPE_FP32
    dims: [384]
  }
]

dynamic_batching {
  preferred_batch_size: [8, 16]
  max_queue_delay_microseconds: 5000
}
```

## `model.py` Interface

```python
class TritonPythonModel:
    def initialize(self, args):
        # Load tokenizer from model directory
        # Load ONNX session via onnxruntime.InferenceSession

    def execute(self, requests):
        # For each request:
        #   1. Extract TEXT bytes input
        #   2. Tokenize with HuggingFace tokenizer (padding, truncation, max_length=128)
        #   3. Run ONNX session: input_ids + attention_mask → token_embeddings
        #   4. Mean pool over token dimension, weighted by attention_mask
        #   5. L2-normalize the result
        #   6. Return as InferenceResponse with sentence_embedding output
```

## Docker Configuration

- **Image:** `nvcr.io/nvidia/tritonserver:24.01-py3`
- **Ports:**
  - `:8000` — HTTP (health + metrics)
  - `:8001` — gRPC (inference — used by pipeline and API)
  - `:8002` — Prometheus metrics
- **Startup flags:** `--model-repository=/models --log-verbose=0`
- **Volume mount:** `./model-repository:/models`

## Key Design Decisions

**Python backend vs raw ONNX backend**

The raw ONNX backend accepts pre-tokenized tensors (input_ids, attention_mask) and returns raw token logits. The caller would need to handle tokenization and mean pooling. The Python backend accepts raw text and returns the final embedding — keeping the gRPC interface simple (text in, vector out) and tokenization co-located with the model.

Tradeoff: Python backend has slightly higher per-request overhead than native backends. Acceptable for this workload.

**CPU vs GPU**

TensorRT (GPU optimization) is excluded because no NVIDIA GPU is available in the development environment. The ONNX Runtime CPU backend is used. `all-MiniLM-L6-v2` is fast enough on CPU for a portfolio demo (sub-100ms per embedding).
