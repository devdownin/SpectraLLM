"""
Unit tests for the reranker HTTP contract.

The Cross-Encoder is stubbed (see conftest) with a deterministic scorer:
score(document) == len(document). Longer documents therefore rank first.
"""


def test_health_ok(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert "model" in body


def test_rerank_sorts_by_score_descending(client):
    # Scores == lengths → "medium length doc" (17) > "short" (5) > "x" (1).
    resp = client.post("/rerank", json={
        "query": "q",
        "documents": ["x", "short", "medium length doc"],
        "top_n": 3,
    })
    assert resp.status_code == 200
    results = resp.json()["results"]
    # Original indices, ordered best-first.
    assert [r["index"] for r in results] == [2, 1, 0]
    # Scores are strictly decreasing.
    scores = [r["score"] for r in results]
    assert scores == sorted(scores, reverse=True)


def test_rerank_top_n_clamps_to_document_count(client):
    resp = client.post("/rerank", json={
        "query": "q",
        "documents": ["a", "bb"],
        "top_n": 10,  # more than we have
    })
    assert resp.status_code == 200
    assert len(resp.json()["results"]) == 2


def test_rerank_returns_only_top_n(client):
    resp = client.post("/rerank", json={
        "query": "q",
        "documents": ["a", "bb", "ccc", "dddd"],
        "top_n": 2,
    })
    assert resp.status_code == 200
    results = resp.json()["results"]
    assert len(results) == 2
    # The two longest are "dddd" (idx 3) then "ccc" (idx 2).
    assert [r["index"] for r in results] == [3, 2]


def test_rerank_top_n_zero_is_rejected(client):
    # Regression: a non-positive top_n used to silently return a wrong subset
    # (indexed[:-1]) instead of an error. Field(ge=1) now rejects it with 422.
    resp = client.post("/rerank", json={
        "query": "q",
        "documents": ["a", "b"],
        "top_n": 0,
    })
    assert resp.status_code == 422


def test_rerank_negative_top_n_is_rejected(client):
    resp = client.post("/rerank", json={
        "query": "q",
        "documents": ["a", "b"],
        "top_n": -3,
    })
    assert resp.status_code == 422


def test_rerank_empty_documents_returns_empty(client):
    resp = client.post("/rerank", json={
        "query": "q",
        "documents": [],
    })
    assert resp.status_code == 200
    assert resp.json()["results"] == []


def test_rerank_defaults_top_n_to_five(client):
    docs = [str(i) * (i + 1) for i in range(8)]  # lengths 1..8
    resp = client.post("/rerank", json={"query": "q", "documents": docs})
    assert resp.status_code == 200
    # Default top_n == 5.
    assert len(resp.json()["results"]) == 5
