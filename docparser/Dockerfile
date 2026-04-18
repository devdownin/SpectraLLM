FROM python:3.11-slim

WORKDIR /app

# Install dependencies (cached layer)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application
COPY app.py .

# Docling is heavy (~500 MB of models) and optional.
# Set USE_DOCLING=true in your environment to enable it.
# When disabled, only pymupdf4llm is used (already installed above).
ARG USE_DOCLING=false
ENV USE_DOCLING=${USE_DOCLING}

RUN if [ "$USE_DOCLING" = "true" ]; then \
      pip install --no-cache-dir "docling>=2.0.0" && \
      python -c "from docling.document_converter import DocumentConverter; DocumentConverter()"; \
    fi

EXPOSE 8001

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8001"]
