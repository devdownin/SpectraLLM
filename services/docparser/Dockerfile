# syntax=docker/dockerfile:1
FROM python:3.11-slim

WORKDIR /app

# Install dependencies (cached layer)
COPY requirements.txt .
# Cache pip hors des couches : la taille de l'image est inchangée, mais une
# reconstruction ne retélécharge plus les dépendances déjà résolues.
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install -r requirements.txt

# Copy application
COPY app.py .

# Docling is heavy (~500 MB of models) and optional.
# Set USE_DOCLING=true in your environment to enable it.
# When disabled, only pymupdf4llm is used (already installed above).
ARG USE_DOCLING=false
ENV USE_DOCLING=${USE_DOCLING}

# Borne majeure sur docling : c'est la seule dépendance non bornée de ce service (tout
# services/docparser/requirements.txt est épinglé au patch près). Sans elle, activer USE_DOCLING
# à six mois d'intervalle n'installe pas le même extracteur — et la qualité d'extraction change
# sans que rien ne le signale. Même raisonnement que pour services/reranker/requirements.txt.
RUN if [ "$USE_DOCLING" = "true" ]; then \
      pip install --no-cache-dir "docling>=2.0.0,<3" && \
      python -c "from docling.document_converter import DocumentConverter; DocumentConverter()"; \
    fi

EXPOSE 8001

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8001"]
