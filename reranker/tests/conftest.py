"""
Test fixtures for the reranker service.

The production module loads a heavy Cross-Encoder model (sentence-transformers +
torch, and a network model download) at *import time*:

    model = CrossEncoder(MODEL_NAME)

To keep the unit tests fast and offline, we inject a fake ``sentence_transformers``
module into ``sys.modules`` **before** ``app`` is imported. The fake ``CrossEncoder``
returns deterministic, monotonic scores so the ranking logic can be asserted exactly.
"""
import os
import sys
import types

import pytest

# app.py lives in the service root, one level up from this tests/ directory.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


class _FakeCrossEncoder:
    """Stand-in for sentence_transformers.CrossEncoder.

    ``predict`` returns one score per (query, document) pair. The score is the
    length of the document text, so longer documents rank higher — deterministic
    and independent of any model weights.
    """

    def __init__(self, model_name, *args, **kwargs):
        self.model_name = model_name

    def predict(self, pairs):
        return [float(len(doc)) for _query, doc in pairs]


@pytest.fixture(scope="session", autouse=True)
def _stub_sentence_transformers():
    fake = types.ModuleType("sentence_transformers")
    fake.CrossEncoder = _FakeCrossEncoder
    sys.modules["sentence_transformers"] = fake
    yield


@pytest.fixture()
def client():
    # Import lazily so the stub above is already in place.
    import importlib

    import app as app_module
    importlib.reload(app_module)  # ensure module-level CrossEncoder() uses the stub

    from fastapi.testclient import TestClient
    return TestClient(app_module.app)
