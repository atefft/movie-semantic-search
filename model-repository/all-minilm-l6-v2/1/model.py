import numpy as np
import triton_python_backend_utils as pb_utils
from transformers import AutoTokenizer
import onnxruntime as ort
import os

class TritonPythonModel:
    def initialize(self, args):
        model_dir = os.path.join(args["model_repository"], args["model_version"])
        self.tokenizer = AutoTokenizer.from_pretrained(model_dir)
        self.session = ort.InferenceSession(os.path.join(model_dir, "model.onnx"))

    def execute(self, requests):
        responses = []
        for request in requests:
            text_tensor = pb_utils.get_input_tensor_by_name(request, "TEXT")
            text = text_tensor.as_numpy()[0][0].decode("utf-8")
            inputs = self.tokenizer(
                text, return_tensors="np", max_length=128,
                padding="max_length", truncation=True
            )
            ort_inputs = {
                "input_ids": inputs["input_ids"].astype(np.int64),
                "attention_mask": inputs["attention_mask"].astype(np.int64),
            }
            if "token_type_ids" in [inp.name for inp in self.session.get_inputs()]:
                ort_inputs["token_type_ids"] = inputs["token_type_ids"].astype(np.int64)
            outputs = self.session.run(None, ort_inputs)
            # Mean pooling over token dimension
            token_embeddings = outputs[0]  # shape [1, seq_len, 384]
            mask = inputs["attention_mask"][..., np.newaxis].astype(np.float32)
            embedding = (token_embeddings * mask).sum(axis=1) / mask.sum(axis=1)
            # L2 normalize
            norm = np.linalg.norm(embedding, axis=1, keepdims=True)
            embedding = embedding / np.maximum(norm, 1e-9)
            out_tensor = pb_utils.Tensor("sentence_embedding", embedding.astype(np.float32))
            responses.append(pb_utils.InferenceResponse(output_tensors=[out_tensor]))
        return responses
