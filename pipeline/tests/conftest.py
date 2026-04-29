"""
Shared fixtures and stubs for pipeline unit tests.

Stubs packages not installed in the test environment (tritonclient, tqdm,
qdrant_client) so tests can import pipeline scripts without those deps.
"""

import importlib.util
import sys
import types
from pathlib import Path
from unittest.mock import MagicMock

import pytest


def _install_stubs():
    # torch
    if "torch" not in sys.modules:
        torch_mod = types.ModuleType("torch")
        torch_nn = types.ModuleType("torch.nn")
        torch_onnx = types.ModuleType("torch.onnx")

        class _Module:
            def __init__(self): pass

        torch_nn.Module = _Module
        torch_onnx.export = MagicMock()
        torch_mod.nn = torch_nn
        torch_mod.onnx = torch_onnx
        torch_mod.zeros_like = MagicMock(return_value=MagicMock())
        sys.modules["torch"] = torch_mod
        sys.modules["torch.nn"] = torch_nn
        sys.modules["torch.onnx"] = torch_onnx

    # transformers
    if "transformers" not in sys.modules:
        transformers_mod = types.ModuleType("transformers")
        transformers_mod.AutoTokenizer = MagicMock
        transformers_mod.AutoModel = MagicMock
        sys.modules["transformers"] = transformers_mod

    # onnxruntime
    if "onnxruntime" not in sys.modules:
        ort_mod = types.ModuleType("onnxruntime")
        ort_mod.InferenceSession = MagicMock
        sys.modules["onnxruntime"] = ort_mod

    # qdrant_client
    if "qdrant_client" not in sys.modules:
        qdrant_client = types.ModuleType("qdrant_client")
        qdrant_http = types.ModuleType("qdrant_client.http")
        qdrant_http_models = types.ModuleType("qdrant_client.http.models")
        qdrant_models = types.ModuleType("qdrant_client.models")

        class Distance:
            COSINE = "Cosine"

        class VectorParams:
            def __init__(self, size, distance):
                self.size = size
                self.distance = distance

        qdrant_http_models.Distance = Distance
        qdrant_http_models.VectorParams = VectorParams
        qdrant_http_models.PointStruct = MagicMock

        qdrant_client.QdrantClient = MagicMock
        qdrant_client.http = qdrant_http
        qdrant_http.models = qdrant_http_models

        sys.modules["qdrant_client"] = qdrant_client
        sys.modules["qdrant_client.http"] = qdrant_http
        sys.modules["qdrant_client.http.models"] = qdrant_http_models
        sys.modules["qdrant_client.models"] = qdrant_models

    # tritonclient
    if "tritonclient" not in sys.modules:
        tritonclient = types.ModuleType("tritonclient")
        tritonclient_grpc = types.ModuleType("tritonclient.grpc")
        tritonclient_grpc.InferenceServerClient = MagicMock
        tritonclient_grpc.InferInput = MagicMock
        tritonclient_grpc.InferRequestedOutput = MagicMock
        sys.modules["tritonclient"] = tritonclient
        sys.modules["tritonclient.grpc"] = tritonclient_grpc

    # tqdm
    if "tqdm" not in sys.modules:
        tqdm_mod = types.ModuleType("tqdm")
        tqdm_auto = types.ModuleType("tqdm.auto")

        class _FakeTqdm:
            def __init__(self, iterable=None, **kwargs):
                self._iterable = iterable or []
            def __enter__(self): return self
            def __exit__(self, *a): pass
            def __iter__(self): return iter(self._iterable)
            def update(self, n=1): pass

        tqdm_mod.tqdm = _FakeTqdm
        tqdm_auto.tqdm = _FakeTqdm
        sys.modules["tqdm"] = tqdm_mod
        sys.modules["tqdm.auto"] = tqdm_auto


_install_stubs()


@pytest.fixture(scope="session")
def pipeline_loader():
    """Load a pipeline script by filename and return it as a module."""
    scripts_dir = Path(__file__).parent.parent

    def _load(filename):
        path = scripts_dir / filename
        spec = importlib.util.spec_from_file_location(filename, path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    return _load
