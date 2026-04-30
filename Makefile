.PHONY: up down pipeline clean

up:
	docker compose up -d

down:
	docker compose down

pipeline:
	cd pipeline && pip install -r requirements.txt && \
	python 01_download_corpus.py && \
	python 02_export_model.py && \
	python 03_embed_corpus.py && \
	python 04_ingest_qdrant.py && \
	python 05_enrich_tmdb.py

clean:
	rm -rf data/raw/ data/embeddings/ \
	  model-repository/all-minilm-l6-v2/1/model.onnx \
	  model-repository/all-minilm-l6-v2/1/tokenizer.json \
	  model-repository/all-minilm-l6-v2/1/tokenizer_config.json \
	  model-repository/all-minilm-l6-v2/1/vocab.txt \
	  model-repository/all-minilm-l6-v2/1/special_tokens_map.json
	docker compose down -v
