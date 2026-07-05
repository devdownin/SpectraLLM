"""
Test fixtures for the docparser service.

The production module imports the native PDF libraries (``pymupdf``,
``pymupdf4llm``) at import time. To keep the tests fast and dependency-free we
inject lightweight fakes into ``sys.modules`` before ``app`` is imported:

  * ``pymupdf4llm.to_markdown`` returns a canned Markdown string.
  * ``pymupdf.open`` returns a fake document with a page count and metadata.

This exercises the real request handling, validation and Markdown-cleaning logic
without needing a real PDF engine.
"""
import os
import sys
import types

import pytest

# app.py lives in the service root, one level up from this tests/ directory.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


class _FakeDoc:
    """Stand-in for a PyMuPDF document."""

    def __init__(self, page_count=3, metadata=None):
        self._page_count = page_count
        self.metadata = metadata if metadata is not None else {
            "title": "Sample", "author": "Tester", "subject": "", "creationDate": "D:2024"
        }

    def __len__(self):
        return self._page_count

    def close(self):
        pass


def _make_fake_pymupdf():
    mod = types.ModuleType("pymupdf")
    mod.open = lambda *_args, **_kwargs: _FakeDoc()
    return mod


def _make_fake_pymupdf4llm():
    mod = types.ModuleType("pymupdf4llm")
    # Return content with artefacts so clean_markdown has something to strip.
    mod.to_markdown = lambda *_args, **_kwargs: (
        "# Title\n\n"
        "Real content line.\n"
        "........\n"          # dotted artefact → stripped
        "Page 2 / 47\n"       # page header artefact → stripped
        "42\n"                # bare page number → stripped (default)
    )
    return mod


@pytest.fixture(scope="session", autouse=True)
def _stub_pdf_libs():
    sys.modules["pymupdf"] = _make_fake_pymupdf()
    sys.modules["pymupdf4llm"] = _make_fake_pymupdf4llm()
    yield


@pytest.fixture()
def app_module():
    import importlib

    import app as module
    importlib.reload(module)
    return module


@pytest.fixture()
def client(app_module):
    from fastapi.testclient import TestClient
    return TestClient(app_module.app)
