"""
Unit tests for the docparser service: Markdown cleaning + the /parse HTTP contract.

The native PDF libraries are stubbed (see conftest) so these run offline and fast.
"""
import io
import importlib


# ── clean_markdown ────────────────────────────────────────────────────────────

def test_clean_markdown_strips_dotted_lines(app_module):
    text = "Real line\n..........\nAnother line"
    out = app_module.clean_markdown(text)
    assert ".........." not in out
    assert "Real line" in out
    assert "Another line" in out


def test_clean_markdown_strips_page_headers(app_module):
    out = app_module.clean_markdown("Intro\nPage 3 / 47\nBody")
    assert "Page 3 / 47" not in out
    assert "Intro" in out and "Body" in out


def test_clean_markdown_collapses_blank_runs(app_module):
    out = app_module.clean_markdown("A\n\n\n\n\nB")
    # Runs of 3+ newlines are reduced to exactly two.
    assert "\n\n\n" not in out
    assert "A\n\nB" == out


def test_clean_markdown_keeps_normal_text(app_module):
    text = "# Heading\n\nA paragraph with real content."
    assert app_module.clean_markdown(text) == text


def test_clean_markdown_preserves_numeric_facts_by_default(app_module):
    text = "Montant total\n2500\nEUR\nAnnée\n2026\nQuantité\n42\nRéférence\n12345"
    assert app_module.clean_markdown(text) == text


def test_bare_page_number_stripping_requires_explicit_opt_in(app_module, monkeypatch):
    monkeypatch.setenv("DOCPARSER_STRIP_PAGE_NUMBERS", "true")
    module = importlib.reload(app_module)
    text = "Intro\n42\n2026\n12345\nBody"
    assert module.clean_markdown(text) == "Intro\n12345\nBody"


# ── /parse validation ─────────────────────────────────────────────────────────

def test_parse_rejects_non_pdf(client):
    resp = client.post(
        "/parse",
        files={"file": ("notes.txt", io.BytesIO(b"hello"), "text/plain")},
    )
    assert resp.status_code == 400
    assert "Unsupported file type" in resp.json()["detail"]


def test_parse_rejects_empty_file(client):
    resp = client.post(
        "/parse",
        files={"file": ("empty.pdf", io.BytesIO(b""), "application/pdf")},
    )
    assert resp.status_code == 400
    assert "Empty file" in resp.json()["detail"]


# ── /parse success path (stubbed engine) ──────────────────────────────────────

def test_parse_returns_cleaned_markdown_and_metadata(client):
    resp = client.post(
        "/parse",
        files={"file": ("doc.pdf", io.BytesIO(b"%PDF-1.4 fake"), "application/pdf")},
    )
    assert resp.status_code == 200
    body = resp.json()

    # Cleaned text: real content kept, artefacts removed.
    assert "Real content line." in body["text"]
    assert "........" not in body["text"]
    assert "Page 2 / 47" not in body["text"]
    assert "42" in body["text"].splitlines()

    assert body["page_count"] == 3
    assert body["parser"] == "pymupdf4llm"
    # Empty metadata values are dropped; present ones are kept.
    assert body["metadata"]["title"] == "Sample"
    assert "subject" not in body["metadata"]


def test_health_reports_active_parser(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["parser"] == "pymupdf4llm"
