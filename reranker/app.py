"""
Spectra Reranker Service
Cross-Encoder re-ranking microservice for RAG post-retrieval.
"""
import os
import logging
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import CrossEncoder

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("reranker")

MODEL_NAME = os.environ.get("RERANKER_MODEL", "cross-encoder/ms-marco-MiniLM-L-6-v2")

log.info("Loading cross-encoder model: %s", MODEL_NAME)
model = CrossEncoder(MODEL_NAME)
log.info("Model loaded.")

app = FastAPI(title="Spectra Reranker", version="1.0.0")


class RerankRequest(BaseModel):
    query: str
    documents: list[str]
    top_n: int = 5


class RankedResult(BaseModel):
    index: int
    score: float


class RerankResponse(BaseModel):
    results: list[RankedResult]
    model: str


@app.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest):
    if not req.documents:
        return RerankResponse(results=[], model=MODEL_NAME)

    top_n = min(req.top_n, len(req.documents))
    pairs = [(req.query, doc) for doc in req.documents]

    try:
        scores = model.predict(pairs)
    except Exception as e:
        log.error("Cross-encoder prediction failed: %s", e)
        raise HTTPException(status_code=500, detail=f"Model prediction error: {e}")

    indexed = sorted(enumerate(scores), key=lambda x: x[1], reverse=True)
    top = indexed[:top_n]

    log.info("Reranked %d docs → top %d | best score=%.4f",
             len(req.documents), top_n, float(top[0][1]) if top else 0.0)

    return RerankResponse(
        results=[RankedResult(index=int(i), score=float(s)) for i, s in top],
        model=MODEL_NAME
    )


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME}
