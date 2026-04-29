import sys
from unittest.mock import MagicMock
import pytest
import importlib.util
import pathlib
import json

# Stub heavy native deps so tests run without them installed
for _stub in [
    "tritonclient",
    "tritonclient.grpc",
    "tritonclient.grpc.service_pb2",
    "tritonclient.utils",
    "qdrant_client",
    "qdrant_client.http",
    "qdrant_client.http.models",
    "qdrant_client.models",
    "tqdm",
    "tqdm.auto",
]:
    sys.modules.setdefault(_stub, MagicMock())

PIPELINE_DIR = pathlib.Path(__file__).parent.parent


def _load_pipeline_script(script_name: str):
    """Load a pipeline script as a module; skip the test if the file doesn't exist yet."""
    path = PIPELINE_DIR / script_name
    if not path.exists():
        pytest.skip(f"{script_name} not yet implemented", allow_module_level=True)
    spec = importlib.util.spec_from_file_location(script_name.replace(".py", ""), path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.fixture(scope="session")
def pipeline_loader():
    """Fixture that exposes _load_pipeline_script to all test files."""
    return _load_pipeline_script


# ---------------------------------------------------------------------------
# Shared sample data
# ---------------------------------------------------------------------------

METADATA_TSV = (
    "111\tf1\tCast Away\t2000-11-22\t1.2e8\t143.0\t{}\t{}\t"
    + json.dumps({"/m/02l7c8": "Drama", "/m/0lsxr": "Adventure"})
    + "\n"
    "222\tf2\tAlien\t1979\t\t\t{}\t{}\t"
    + json.dumps({"/m/01jfsb": "Thriller"})
    + "\n"
    "333\tf3\tMetaOnly\t2005-03-01\t\t\t{}\t{}\t"
    + json.dumps({"/m/02l7c8": "Drama"})
    + "\n"
    "444\tf4\tBadDate\t\t\t\t{}\t{}\t{}\n"
)

SUMMARIES_TXT = (
    "111\tA FedEx executive undergoes a physical and personal transformation "
    "after crash landing on a deserted island.\n"
    "222\tA commercial crew aboard the deep space towing vessel are woken from "
    "cryo-sleep to investigate an alien distress call.\n"
    "555\tThis movie has a summary but no corresponding metadata entry.\n"
)


@pytest.fixture
def data_dir(tmp_path):
    raw = tmp_path / "data" / "raw"
    raw.mkdir(parents=True)
    (raw / "movie.metadata.tsv").write_text(METADATA_TSV)
    (raw / "plot_summaries.txt").write_text(SUMMARIES_TXT)
    return tmp_path
