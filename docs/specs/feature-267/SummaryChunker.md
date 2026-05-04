# SummaryChunker Spec

**Feature:** #267 — Multi-vector ingestion and mean-pooled search for full movie summaries
**Component:** `SummaryChunker` (`pipeline/03_embed_corpus.py`)

## Overview

`SummaryChunker` is a pure function module added to `03_embed_corpus.py` that splits a full movie summary string into overlapping fixed-token windows and applies a configurable chunk cap. It accepts a pre-initialized HuggingFace tokenizer so that token boundaries match exactly what the embedding model sees. An empty or whitespace-only summary produces an empty list; the caller is responsible for skipping that movie.

## Data Contract

| Property | Type | Description | Behavior |
|----------|------|-------------|----------|
| `text` | `str` | Full summary text to chunk | May be empty string; whitespace-only treated as empty |
| `tokenizer` | `AutoTokenizer` | Pre-initialized HuggingFace tokenizer | Must be the same tokenizer used for embedding |
| `window_size` | `int` | Token count per chunk | Default 512; must be > 0 |
| `overlap` | `int` | Token overlap between consecutive windows | Default 64; must be < window_size |
| `max_chunks` | `int` | Maximum number of chunks to return | Default 10; must be > 0 |
| **return** | `list[str]` | Decoded chunk strings | Empty list if text is empty/whitespace; at most `max_chunks` entries |

## Dependencies

| Dependency | Interface / Type | Injected As |
|------------|-----------------|-------------|
| `tokenizer` | `transformers.AutoTokenizer` instance | Parameter |

### Dependency Mock Behaviors

#### tokenizer

| Scenario | Mock Setup | Notes |
|----------|------------|-------|
| Happy path | `tokenizer(text, add_special_tokens=False)["input_ids"]` returns list of ints | Normal operation |
| Short text (fits in one window) | Returns fewer tokens than `window_size` | Produces exactly one chunk |
| Empty / whitespace text | Early-exit before calling tokenizer | Tokenizer is never called |

**Mock data structures:**
```json
// Happy path — long summary producing 3 chunks (window=10, overlap=2, cap=10)
// Token IDs: [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20]
// Chunk 0: tokens [1..10] → decoded "first ten tokens"
// Chunk 1: tokens [9..18] → decoded "overlapping middle"
// Chunk 2: tokens [17..20] → decoded "final tail"

// Short text — single chunk
// Token IDs: [1,2,3] → one chunk decoded as "short summary"

// Empty string
// Returns []
```

## Edge Cases

| # | Input | Expected Output | Description | Mock Setup |
|---|-------|----------------|-------------|------------|
| 1 | `text=""` | `[]` | Empty string produces no chunks | Tokenizer not called |
| 2 | `text="   "` | `[]` | Whitespace-only treated as empty | Tokenizer not called |
| 3 | Text that tokenizes to ≤ `window_size` tokens | `["<full text decoded>"]` | Single chunk, no truncation | Tokenizer returns short ID list |
| 4 | Text that tokenizes to exactly `window_size` tokens | `["<full text decoded>"]` | Boundary: exactly fills one window | Tokenizer returns ID list of length == window_size |
| 5 | Text producing > `max_chunks` chunks | List of exactly `max_chunks` strings | Cap is enforced; later chunks discarded | Tokenizer returns long ID list |
| 6 | `overlap` ≥ `window_size` | `ValueError` raised | Invalid configuration | N/A |
| 7 | `max_chunks` < 1 | `ValueError` raised | Invalid configuration | N/A |

## Unit Test Checklist

- [ ] Happy path: 3-chunk summary returns 3 decoded strings with correct token boundaries
- [ ] Empty string returns `[]` without calling tokenizer
- [ ] Whitespace-only string returns `[]` without calling tokenizer
- [ ] Text fitting in one window returns list of length 1
- [ ] Text exactly filling one window returns list of length 1
- [ ] Text producing 15 chunks with `max_chunks=10` returns exactly 10 strings
- [ ] Overlap is applied: second chunk starts `window_size - overlap` tokens into the first chunk's token sequence
- [ ] `overlap >= window_size` raises `ValueError`
- [ ] `max_chunks < 1` raises `ValueError`
- [ ] Returned strings are decoded text (not token IDs)
