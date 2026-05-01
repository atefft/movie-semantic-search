.PHONY: up down pipeline clean

up:
	docker compose up -d

down:
	docker compose down

pipeline:
	docker compose run --rm load-model \
	  || (echo "[pipeline] load-model phase failed — aborting" && exit 1)
	docker compose up -d \
	  || (echo "[pipeline] services-up phase failed — aborting" && exit 1)
	docker compose run --rm load-data \
	  || (echo "[pipeline] load-data phase failed — aborting" && exit 1)

clean:
	rm -rf data/raw/ data/embeddings/ \
	  model-repository/all-minilm-l6-v2/1/model.onnx \
	  model-repository/all-minilm-l6-v2/1/tokenizer.json \
	  model-repository/all-minilm-l6-v2/1/tokenizer_config.json \
	  model-repository/all-minilm-l6-v2/1/vocab.txt \
	  model-repository/all-minilm-l6-v2/1/special_tokens_map.json
	docker compose down -v
