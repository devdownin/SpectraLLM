#!/usr/bin/env python3
"""Copy a frozen Chroma collection into a new, isolated Qdrant pilot alias."""
import argparse
import json
import os
import re
import uuid
from urllib.parse import quote
from urllib.request import Request, urlopen

CHROMA = "/api/v2/tenants/default_tenant/databases/default_database/collections"


def request(base, method, path, body=None, api_key=None):
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["api-key"] = api_key
    data = None if body is None else json.dumps(body, allow_nan=False).encode()
    with urlopen(Request(base.rstrip("/") + path, data, headers, method=method), timeout=60) as response:
        return json.load(response)


def copy(args):
    def chroma(method, path, body=None):
        return request(args.chroma, method, path, body)

    def qdrant(method, path, body=None):
        return request(args.qdrant, method, path, body, os.environ.get("SPECTRA_QDRANT_API_KEY"))["result"]

    collection = chroma("GET", f"{CHROMA}/{quote(args.source, safe='')}")
    source_id = uuid.UUID(collection["id"])
    source = f"{CHROMA}/{source_id}"
    config = collection.get("configuration_json") or {}
    hnsw = config.get("hnsw") or config.get("hnsw_configuration") or {}
    if hnsw.get("space") != "cosine":
        raise ValueError("The source collection must explicitly use cosine distance")
    expected = chroma("GET", source + "/count")
    if expected <= 0:
        raise ValueError("The source collection is empty")

    if qdrant("GET", f"/collections/{args.target}/exists")["exists"]:
        raise ValueError("Choose an unused pilot alias")
    staging_name = "spectra_snapshot_" + uuid.uuid4().hex
    staging = "/collections/" + staging_name
    seen = set()
    dimensions = None
    for offset in range(0, expected, args.batch):
        limit = min(args.batch, expected - offset)
        page = chroma("POST", source + "/get", {
            "limit": limit, "offset": offset, "include": ["documents", "metadatas", "embeddings"],
        })
        if len(page["ids"]) != limit:
            raise ValueError("Unexpected page size. Freeze all source writes and retry.")
        points = []
        for chunk_id, text, metadata, vector in zip(
                page["ids"], page["documents"], page["metadatas"], page["embeddings"], strict=True):
            if chunk_id in seen:
                raise ValueError("Duplicate Chroma ID. Freeze all source writes and retry.")
            if not isinstance(text, str) or not vector or (dimensions and len(vector) != dimensions):
                raise ValueError("Source contains an invalid document or embedding")
            if dimensions is None:
                dimensions = len(vector)
                qdrant("PUT", staging, {"vectors": {"size": dimensions, "distance": "Cosine"}})
                print(f"Staging collection: {staging_name}", flush=True)
                for field in ("metadata.sha256", "metadata.sourceFile"):
                    qdrant("PUT", staging + "/index?wait=true", {"field_name": field, "field_schema": "keyword"})
            seen.add(chunk_id)
            points.append({
                "id": str(uuid.uuid5(source_id, chunk_id)),
                "vector": vector,
                "payload": {
                    "sourceCollectionId": str(source_id),
                    "chunkId": chunk_id,
                    "document": text,
                    "metadata": metadata or {},
                },
            })
        qdrant("PUT", staging + "/points?wait=true", {"points": points})

    copied = qdrant("POST", staging + "/points/count", {"exact": True})["count"]
    if copied != expected or chroma("GET", source + "/count") != expected:
        raise ValueError(f"Count mismatch, expected {expected}, Qdrant contains {copied}")
    qdrant("POST", "/collections/aliases", {"actions": [
        {"create_alias": {"collection_name": staging_name, "alias_name": args.target}},
    ]})
    print(f"Copied {copied} chunks with {dimensions} dimensions")
    print(f"SPECTRA_QDRANT_SOURCE_ID={source_id}\nSPECTRA_QDRANT_TARGET={args.target}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chroma", required=True)
    parser.add_argument("--qdrant", default="http://127.0.0.1:16333")
    parser.add_argument("--source", required=True, help="Chroma collection name")
    parser.add_argument("--target", required=True, help="New Qdrant alias starting with spectra_pilot_")
    parser.add_argument("--batch", type=int, default=128)
    options = parser.parse_args()
    if not re.fullmatch(r"spectra_pilot_[a-zA-Z0-9_-]+", options.target) or not 1 <= options.batch <= 1024:
        parser.error("Invalid pilot alias or batch size")
    copy(options)
