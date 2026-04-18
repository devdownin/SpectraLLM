"""
Spectra DocParser Service
Layout-aware PDF parsing microservice.
Extracts text as structured Markdown, preserving headings, tables, and column layout.

Default parser: pymupdf4llm (lightweight, no AI models required)
Optional: Docling (IBM research, higher accuracy) via USE_DOCLING=true
"""
import os
import re
import logging
import tempfile
from pathlib import Path

import pymupdf
import pymupdf4llm
from fastapi import FastAPI, UploadFile, File, HTTPException

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("docparser")

USE_DOCLING = os.getenv("USE_DOCLING", "false").lower() == "true"
_docling_converter = None

if USE_DOCLING:
    try:
        from docling.document_converter import DocumentConverter
        log.info("Loading Docling converter...")
        _docling_converter = DocumentConverter()
        log.info("Docling converter ready.")
    except ImportError as e:
        log.warning("Docling not installed, falling back to pymupdf4llm: %s", e)
        USE_DOCLING = False

ACTIVE_PARSER = "docling" if USE_DOCLING else "pymupdf4llm"
log.info("Active parser: %s", ACTIVE_PARSER)

# Patterns d'artefacts générés par pymupdf4llm à éliminer
_ARTIFACT_PATTERNS = [
    re.compile(r'^\s*[\.]{5,}\s*$'),           # lignes de points : ...........
    re.compile(r'^\s*[-_]{5,}\s*$'),            # lignes de tirets/underscores
    re.compile(r'^\s*Page\s+\d+\s*/\s*\d+'),    # en-têtes de page : Page 3 / 47
    re.compile(r'^\s*\d+\s*$'),                  # numéros de page seuls
]


def clean_markdown(text: str) -> str:
    """Supprime les artefacts courants du Markdown généré par pymupdf4llm."""
    lines = text.split('\n')
    cleaned = [line for line in lines
               if not any(p.match(line) for p in _ARTIFACT_PATTERNS)]
    # Réduire les blocs de lignes vides consécutives (> 2) à deux sauts de ligne
    return re.sub(r'\n{3,}', '\n\n', '\n'.join(cleaned))


app = FastAPI(title="Spectra DocParser", version="1.0.0")


@app.post("/parse")
async def parse_document(file: UploadFile = File(...)):
    """
    Parse a PDF file and return structured Markdown text.

    Returns:
        text       — Markdown with headings (#, ##), tables (| col |), code blocks
        page_count — number of pages
        metadata   — title, author, subject, creationDate extracted from PDF metadata
        parser     — which parser was used
    """
    filename = file.filename or "document.pdf"
    suffix = Path(filename).suffix.lower()
    if suffix not in (".pdf",):
        raise HTTPException(status_code=400, detail=f"Unsupported file type: {suffix}. Only PDF is supported.")

    content = await file.read()
    if not content:
        raise HTTPException(status_code=400, detail="Empty file")

    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(content)
            tmp_path = tmp.name

        # --- Extract text as Markdown ---
        if USE_DOCLING and _docling_converter is not None:
            try:
                result = _docling_converter.convert(tmp_path)
                text = result.document.export_to_markdown()
                parser_used = "docling"
            except Exception as e:
                log.warning("Docling failed for '%s', falling back to pymupdf4llm: %s", filename, e)
                text = clean_markdown(pymupdf4llm.to_markdown(tmp_path))
                parser_used = "pymupdf4llm-fallback"
        else:
            text = clean_markdown(pymupdf4llm.to_markdown(tmp_path))
            parser_used = "pymupdf4llm"

        # --- Extract PDF metadata via PyMuPDF ---
        doc = pymupdf.open(tmp_path)
        page_count = len(doc)
        raw_meta = doc.metadata or {}
        doc.close()

        metadata = {}
        for key in ("title", "author", "subject", "creationDate"):
            val = raw_meta.get(key, "")
            if val:
                metadata[key] = str(val).strip()

        log.info("Parsed '%s': %d pages, %d chars, parser=%s",
                 filename, page_count, len(text), parser_used)

        return {
            "text": text,
            "page_count": page_count,
            "metadata": metadata,
            "parser": parser_used,
        }

    except HTTPException:
        raise
    except Exception as e:
        log.error("Parsing error for '%s': %s", filename, e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Parsing error: {e}")
    finally:
        if tmp_path and os.path.exists(tmp_path):
            os.unlink(tmp_path)


@app.get("/health")
def health():
    return {"status": "ok", "parser": ACTIVE_PARSER}
