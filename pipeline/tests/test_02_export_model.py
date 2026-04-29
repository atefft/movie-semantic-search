"""Tests for pipeline/02_export_model.py."""

from unittest.mock import MagicMock, patch

import pytest


@pytest.fixture(scope="module")
def mod(pipeline_loader):
    return pipeline_loader("02_export_model.py")


# ---------------------------------------------------------------------------
# _ModelWrapper
# ---------------------------------------------------------------------------

class TestModelWrapper:
    def test_forward_calls_model_with_correct_kwargs(self, mod):
        mock_model = MagicMock()
        wrapper = mod._ModelWrapper(mock_model)
        ids, mask, types = MagicMock(), MagicMock(), MagicMock()

        result = wrapper.forward(ids, mask, types)

        mock_model.assert_called_once_with(
            input_ids=ids,
            attention_mask=mask,
            token_type_ids=types,
        )
        assert result is mock_model.return_value.last_hidden_state

    def test_forward_returns_last_hidden_state(self, mod):
        mock_model = MagicMock()
        mock_model.return_value.last_hidden_state = "expected"
        wrapper = mod._ModelWrapper(mock_model)

        result = wrapper.forward(MagicMock(), MagicMock(), MagicMock())

        assert result == "expected"


# ---------------------------------------------------------------------------
# main — idempotency
# ---------------------------------------------------------------------------

class TestMainIdempotent:
    def test_exits_early_when_model_exists(self, mod, tmp_path, monkeypatch):
        onnx = tmp_path / "model.onnx"
        onnx.write_bytes(b"")
        monkeypatch.setattr(mod, "ONNX_PATH", onnx)
        with patch("sys.exit", side_effect=SystemExit(0)):
            with pytest.raises(SystemExit):
                mod.main()
