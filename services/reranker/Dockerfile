FROM python:3.11-slim

WORKDIR /app

# Install dependencies first (cache layer)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application
COPY app.py .

# Pre-download the model at build time so startup is instant
ARG RERANKER_MODEL=cross-encoder/ms-marco-MiniLM-L-6-v2
ENV RERANKER_MODEL=${RERANKER_MODEL}
RUN python -c "from sentence_transformers import CrossEncoder; CrossEncoder('${RERANKER_MODEL}')"

EXPOSE 8000

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
