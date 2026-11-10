#!/usr/bin/env python3
"""Banc EnterpriseRAG-Bench (onyx-dot-app) pour Spectra.

Produit le fichier de réponses attendu par l'évaluateur officiel
(https://github.com/onyx-dot-app/EnterpriseRAG-Bench, ``metrics_based_eval``) et par le
leaderboard https://huggingface.co/spaces/onyx-dot-app/EnterpriseRAG-Bench-Leaderboard :

    {"question_id": "qst_0001", "answer": "...", "document_ids": ["dsid_..."]}

Étapes (chacune reprenable) :

    prepare  télécharge le corpus (parquet HF) et écrit un fichier ``<doc_id>.txt`` par document
             (``--subset N`` : documents de référence + N distracteurs, pour un essai rapide) ;
    ingest   envoie ces fichiers à ``POST /api/ingest`` par lots et suit chaque tâche ;
    answer   pose les 500 questions à ``POST /api/query`` et écrit ``answers.jsonl`` ;
    recall   mesure hors-ligne le rappel documentaire (sans LLM juge) ;
    retrieve recherche seule (``POST /api/query/retrieve``, sans génération) : qualité du retrieval en
             quelques secondes par question, pour comparer rerankers, seuils, multi-query, MMR…

Le doc_id est porté par le nom de fichier : Spectra le restitue dans ``sources[].sourceFile``,
d'où les ``document_ids`` sont extraits. Il est aussi écrit en tête du contenu, ce qui rend
chaque fichier unique (l'ingestion déduplique par SHA-256 : deux quasi-doublons au contenu
identique ne seraient sinon indexés qu'une fois, sous un seul doc_id).

Dépendances : bibliothèque standard ; ``pyarrow`` pour ``prepare`` depuis le parquet
(``prepare --source-dir`` lit le corpus brut d'un clone GitHub, sans pyarrow).
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator

HF_BASE = "https://huggingface.co/datasets/onyx-dot-app/EnterpriseRAG-Bench/resolve/main"
DOCUMENTS_URL = f"{HF_BASE}/data/documents/test.parquet"
QUESTIONS_URL = "https://raw.githubusercontent.com/onyx-dot-app/EnterpriseRAG-Bench/main/questions.jsonl"

DOC_ID_PATTERN = re.compile(r"dsid_[0-9a-fA-F]{8,}")
# Types sans document de référence : exclus du rappel (comme dans l'évaluateur officiel).
NO_GOLD_TYPES = frozenset({"high_level", "info_not_found"})
TERMINAL_STATUSES = frozenset({"COMPLETED", "FAILED", "CANCELLED"})

# Limites Spring : 50 Mo par fichier, 100 Mo par requête (application.yml). Marge pour l'en-tête multipart.
MAX_BATCH_BYTES = 40 * 1024 * 1024
# Tomcat refuse au-delà de 50 parties multipart (server.tomcat.max-part-count) : HTTP 500
# « Maximum upload size exceeded », quelle que soit la taille réelle du lot.
MAX_BATCH_FILES = 40


# ── Données ───────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Question:
    question_id: str
    question_type: str
    question: str
    expected_doc_ids: tuple[str, ...]


def load_questions(path: Path) -> list[Question]:
    questions = []
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            row = json.loads(line)
            questions.append(Question(
                question_id=row["question_id"],
                question_type=row.get("question_type", "unknown"),
                question=row["question"],
                expected_doc_ids=tuple(row.get("expected_doc_ids") or ()),
            ))
    return questions


def extract_doc_ids(sources: Iterable[dict], limit: int | None = None) -> list[str]:
    """doc_ids distincts, dans l'ordre de classement des sources renvoyées par Spectra."""
    seen: dict[str, None] = {}
    for source in sources:
        for match in DOC_ID_PATTERN.findall(source.get("sourceFile") or ""):
            seen.setdefault(match.lower(), None)
    ids = list(seen)
    return ids[:limit] if limit else ids


CITATION_PATTERN = re.compile(r"\[(\d+(?:\s*[,;]\s*\d+)*)\]")


def cited_source_numbers(answer: str) -> list[int]:
    """Numéros ``[n]`` cités dans la réponse (``[3]``, ``[1, 2]``), distincts, dans l'ordre d'apparition."""
    seen: dict[int, None] = {}
    for group in CITATION_PATTERN.findall(answer or ""):
        for number in re.split(r"\s*[,;]\s*", group):
            seen.setdefault(int(number), None)
    return list(seen)


def cited_doc_ids(answer: str, sources: list[dict], limit: int | None = None) -> list[str]:
    """doc_ids des seuls passages cités. Les ``[n]`` sont 1-basés sur l'ordre de ``sources``.

    Repli sur tous les documents récupérés quand la réponse ne cite aucun passage valide : une
    réponse sans citation (refus, réponse de mémoire) ne doit pas perdre son rappel.
    """
    picked = [sources[n - 1] for n in cited_source_numbers(answer) if 1 <= n <= len(sources)]
    return extract_doc_ids(picked or sources, limit)


def render_document(doc_id: str, source_type: str, title: str, content: str) -> str:
    header = f"[{doc_id}] [{source_type}] {title}".strip()
    return f"{header}\n\n{content or ''}\n"


def select_subset(all_ids: Iterable[str], gold_ids: set[str], distractors: int, seed: int,
                  already: Iterable[str] = ()) -> set[str]:
    """Documents de référence + ``distractors`` documents tirés au hasard (reproductible).

    ``already`` : distracteurs déjà préparés (et indexés). Ils sont conservés, puis complétés jusqu'à
    ``distractors`` : un sous-corpus plus grand contient le précédent, rien n'est à ré-indexer.
    """
    others = sorted(i for i in all_ids if i not in gold_ids)
    kept = sorted(set(already) & set(others))[:distractors]
    rest = sorted(set(others) - set(kept))
    rng = random.Random(seed)
    picked = rng.sample(rest, min(distractors - len(kept), len(rest)))
    return set(gold_ids) | set(kept) | set(picked)


def sample_per_type(questions: list[Question], per_type: int) -> list[Question]:
    """Les ``per_type`` premières questions de chaque catégorie (ordre du fichier) : échantillon stable."""
    counts: dict[str, int] = defaultdict(int)
    picked = []
    for q in questions:
        if counts[q.question_type] < per_type:
            counts[q.question_type] += 1
            picked.append(q)
    return picked


def plan_batches(files: list[Path], max_files: int, max_bytes: int = MAX_BATCH_BYTES) -> list[list[Path]]:
    """Lots déterministes (fichiers triés) : un index de lot reste stable d'une reprise à l'autre."""
    batches: list[list[Path]] = []
    current: list[Path] = []
    size = 0
    for path in sorted(files):
        file_size = path.stat().st_size
        if current and (len(current) >= max_files or size + file_size > max_bytes):
            batches.append(current)
            current, size = [], 0
        current.append(path)
        size += file_size
    if current:
        batches.append(current)
    return batches


def recall_report(questions: list[Question], answers: dict[str, list[str]]) -> dict:
    per_type: dict[str, list[float]] = defaultdict(list)
    extras: dict[str, list[int]] = defaultdict(list)
    for q in questions:
        # Une question absente du fichier de réponses n'est pas un échec de rappel : elle n'a pas été posée.
        if q.question_type in NO_GOLD_TYPES or not q.expected_doc_ids or q.question_id not in answers:
            continue
        retrieved = {d.lower() for d in answers.get(q.question_id, [])}
        gold = {d.lower() for d in q.expected_doc_ids}
        per_type[q.question_type].append(len(gold & retrieved) / len(gold))
        extras[q.question_type].append(len(retrieved - gold))
    flat = [r for values in per_type.values() for r in values]
    return {
        "questions": len(flat),
        "recall": sum(flat) / len(flat) if flat else 0.0,
        "by_type": {
            t: {"n": len(v), "recall": sum(v) / len(v), "mean_extra_docs": sum(extras[t]) / len(extras[t])}
            for t, v in sorted(per_type.items())
        },
    }


# ── HTTP ──────────────────────────────────────────────────────────────────────


class SpectraClient:
    def __init__(self, base_url: str, timeout: float):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def _send(self, request: urllib.request.Request) -> dict:
        if api_key := os.environ.get("SPECTRA_API_KEY"):
            request.add_unredirected_header("X-API-Key", api_key)
        with urllib.request.urlopen(request, timeout=self.timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def get(self, path: str) -> dict:
        return self._send(urllib.request.Request(self.base_url + path))

    def wait_ready(self, timeout: float, poll: float = 3.0) -> None:
        """Attend ``/actuator/health/readiness`` (HTTP 200). Lève ``TimeoutError`` au-delà de ``timeout`` secondes.

        Après un redémarrage, l'API répond avant que l'index BM25 soit rechargé : une requête « hybride » est
        alors vectorielle seule, sans erreur. Depuis que la disponibilité est fermée pendant ce rechargement,
        attendre ce point d'accès suffit ; un serveur plus ancien répond 200 d'emblée, ou 404 (sans actuator),
        ce qui compte comme prêt. 503 et erreurs réseau signifient « pas encore ».
        """
        deadline = time.monotonic() + timeout
        while True:
            try:
                self.get("/actuator/health/readiness")
                return
            except urllib.error.HTTPError as exc:
                if exc.code == 404:
                    return
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                pass
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"{self.base_url} n'est pas prêt après {timeout:.0f} s")
            time.sleep(min(poll, remaining))

    def post_json(self, path: str, payload: dict) -> dict:
        body = json.dumps(payload).encode("utf-8")
        return self._send(urllib.request.Request(
            self.base_url + path, data=body, method="POST",
            headers={"Content-Type": "application/json"}))

    def post_files(self, path: str, files: list[Path]) -> dict:
        body, content_type = encode_multipart(files)
        return self._send(urllib.request.Request(
            self.base_url + path, data=body, method="POST", headers={"Content-Type": content_type}))


def encode_multipart(files: list[Path], field: str = "files") -> tuple[bytes, str]:
    boundary = uuid.uuid4().hex
    parts = []
    for path in files:
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{path.name}"\r\n'
            f"Content-Type: text/plain\r\n\r\n".encode("utf-8"))
        parts.append(path.read_bytes())
        parts.append(b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def with_retry(call, attempts: int = 6, base_delay: float = 5.0, before_retry=None):
    """Réessaie sur 429 (contre-pression ``max-active-ingestions``), 5xx et erreurs réseau.

    ``before_retry`` est appelé avant chaque nouvel essai : on y attend que l'API soit de nouveau prête
    (un redémarrage coupe la requête en cours, et la relancer tout de suite échouerait de la même façon).
    """
    for attempt in range(attempts):
        try:
            return call()
        except urllib.error.HTTPError as exc:
            if exc.code != 429 and exc.code < 500 or attempt == attempts - 1:
                raise
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == attempts - 1:
                raise
        time.sleep(base_delay * 2 ** attempt)
        if before_retry is not None:
            before_retry()
    raise RuntimeError("unreachable")


def download(url: str, dest: Path) -> Path:
    if dest.exists():
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    partial = dest.with_suffix(dest.suffix + ".part")
    print(f"[prepare] téléchargement {url}", file=sys.stderr)
    with urllib.request.urlopen(url, timeout=120) as resp, partial.open("wb") as out:
        while chunk := resp.read(1 << 20):
            out.write(chunk)
    partial.rename(dest)
    return dest


# ── Commandes ─────────────────────────────────────────────────────────────────


def iter_parquet_rows(path: Path, columns: list[str]) -> Iterator[dict]:
    try:
        import pyarrow.parquet as pq
    except ImportError:
        sys.exit("pyarrow est requis pour 'prepare' : pip install pyarrow")
    # Lecture par lots : le corpus (1,4 Go compressé) ne tient pas confortablement en mémoire.
    for batch in pq.ParquetFile(path).iter_batches(columns=columns, batch_size=2048):
        yield from batch.to_pylist()


def flatten_field(value) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n\n".join(filter(None, map(flatten_field, value)))
    if isinstance(value, dict):
        return "\n".join(f"{k}: {flatten_field(v)}" for k, v in value.items())
    return str(value)


def source_document_row(raw: dict, source_type: str) -> dict:
    """Document brut de ``generated_data/sources`` → mêmes champs que le parquet HF (titre + champs de contenu)."""
    return {
        "doc_id": raw["dataset_doc_uuid"],
        "source_type": source_type,
        "title": flatten_field(raw.get(raw.get("title_field_name") or "title")),
        "content": "\n\n".join(filter(None, (flatten_field(raw.get(f)) for f in raw.get("content_field_names") or []))),
    }


class SourceDirCorpus:
    """Corpus lu depuis ``generated_data/`` du dépôt GitHub (alternative au parquet Hugging Face)."""

    def __init__(self, root: Path):
        self.root = root
        # uuid_index.json : doc_id → chemin relatif à sources/ (≈ 60 Mo, lu une fois).
        self.index: dict[str, str] = json.loads((root / "uuid_index.json").read_text(encoding="utf-8"))

    def ids(self) -> list[str]:
        return list(self.index)

    def rows(self, keep: set[str] | None) -> Iterator[dict]:
        for doc_id, rel in self.index.items():
            if keep is not None and doc_id not in keep:
                continue
            raw = json.loads((self.root / "sources" / rel).read_text(encoding="utf-8"))
            yield source_document_row(raw, rel.split("/", 1)[0])


class ParquetCorpus:
    def __init__(self, path: Path):
        self.path = path

    def ids(self) -> list[str]:
        return [row["doc_id"] for row in iter_parquet_rows(self.path, ["doc_id"])]

    def rows(self, keep: set[str] | None) -> Iterator[dict]:
        for row in iter_parquet_rows(self.path, ["doc_id", "source_type", "title", "content"]):
            if keep is None or row["doc_id"] in keep:
                yield row


def cmd_prepare(args: argparse.Namespace) -> None:
    work = Path(args.work_dir)
    questions_path = download(args.questions_url, work / "questions.jsonl")
    if args.source_dir:
        corpus = SourceDirCorpus(Path(args.source_dir))
    else:
        corpus = ParquetCorpus(Path(args.documents_parquet) if args.documents_parquet else download(
            args.documents_url, work / "documents.parquet"))

    keep: set[str] | None = None
    if args.subset is not None:
        gold = {d for q in load_questions(questions_path) for d in q.expected_doc_ids}
        existing = {p.stem for p in (work / "documents").glob("dsid_*.txt")} if args.extend else set()
        keep = select_subset(corpus.ids(), gold, args.subset, args.seed, already=existing)
        print(f"[prepare] sous-ensemble : {len(gold)} documents de référence + "
              f"{len(keep) - len(gold)} distracteurs (score NON comparable au leaderboard)", file=sys.stderr)

    docs_dir = work / "documents"
    docs_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    for row in corpus.rows(keep):
        target = docs_dir / f"{row['doc_id']}.txt"
        if not target.exists():
            target.write_text(render_document(row["doc_id"], row["source_type"] or "",
                                              row["title"] or "", row["content"]), encoding="utf-8")
        written += 1
    print(f"[prepare] {written} documents dans {docs_dir}", file=sys.stderr)


def wait_for_task(client: SpectraClient, task_id: str, poll: float) -> dict:
    while True:
        task = with_retry(lambda: client.get(f"/api/ingest/{task_id}"))
        if task.get("status") in TERMINAL_STATUSES:
            return task
        time.sleep(poll)


def cmd_ingest(args: argparse.Namespace) -> None:
    work = Path(args.work_dir)
    files = list((work / "documents").glob("dsid_*.txt"))
    if not files:
        sys.exit(f"aucun document dans {work / 'documents'} — lancer 'prepare' d'abord")
    state_path = work / "ingest_state.json"
    # L'état note les fichiers indexés (et non des index de lot, qui bougent dès qu'on ajoute des documents).
    done: set[str] = set(json.loads(state_path.read_text()).get("files", [])) if state_path.exists() else set()
    errors_path = work / "ingest_errors.log"
    client = SpectraClient(args.api, args.timeout)

    batches = plan_batches([f for f in files if f.name not in done], args.batch_size)
    print(f"[ingest] {len(files)} fichiers, {len(done)} déjà indexés, {len(batches)} lots à envoyer", file=sys.stderr)
    for index in range(len(batches)):
        submitted = with_retry(lambda b=batches[index]: client.post_files("/api/ingest", b))
        task = wait_for_task(client, submitted["taskId"], args.poll)
        if task["status"] != "COMPLETED":
            sys.exit(f"[ingest] lot {index} : {task['status']} — {task.get('error')}")
        if task.get("fileErrors"):
            with errors_path.open("a", encoding="utf-8") as fh:
                fh.writelines(f"{e}\n" for e in task["fileErrors"])
        done.update(path.name for path in batches[index])
        state_path.write_text(json.dumps({"files": sorted(done)}))
        print(f"[ingest] lot {index + 1}/{len(batches)} : {task.get('chunksCreated', 0)} chunks", file=sys.stderr)


def build_query(question: str, args: argparse.Namespace) -> dict:
    payload = {
        "question": f"{question}{args.question_suffix}",
        "maxContextChunks": args.max_context_chunks,
        "topCandidates": args.top_candidates,
        "temperature": args.temperature,
    }
    if args.collection:
        payload["collection"] = args.collection
    # Surcharges par requête (RagOverrides) : permettent un A/B sur la même pile, sans la redémarrer.
    overrides = {}
    if args.hybrid != "default":
        overrides["hybrid"] = args.hybrid == "on"
    if getattr(args, "rerank", "default") != "default":
        overrides["rerank"] = args.rerank == "on"
    if getattr(args, "self_rag", "default") != "default":
        overrides["selfRag"] = args.self_rag == "on"
    if args.neighbor_window is not None:
        overrides["neighborWindow"] = args.neighbor_window
    if args.multi_query != "default":
        overrides["multiQuery"] = args.multi_query == "on"
    if args.dedup != "default":
        overrides["dedupStrategy"] = args.dedup
    if args.rerank_min_score is not None:
        overrides["rerankMinScore"] = args.rerank_min_score
    if overrides:
        payload["overrides"] = overrides
    return payload


def recover(client: SpectraClient, args: argparse.Namespace) -> None:
    """Après une coupure, attend que l'API soit prête avant de rejouer la requête (sans effet si ``--wait-ready 0``)."""
    if args.wait_ready > 0:
        client.wait_ready(args.wait_ready)


def answer_one(client: SpectraClient, q: Question, args: argparse.Namespace) -> dict:
    response = with_retry(lambda: client.post_json("/api/query", build_query(q.question, args)), attempts=3,
                          before_retry=lambda: recover(client, args))
    answer = response.get("answer") or ""
    sources = response.get("sources") or []
    retrieved = extract_doc_ids(sources, args.max_docs)
    cited = cited_doc_ids(answer, sources, args.max_docs)
    return {
        "question_id": q.question_id,
        "answer": answer,
        "document_ids": cited if args.sources == "cited" else retrieved,
        # Les deux listes sont conservées : le choix cited/all se rejoue hors-ligne.
        "spectra": {"ragStrategy": response.get("ragStrategy"), "durationMs": response.get("durationMs"),
                    "contextChars": response.get("contextChars"),
                    "selfRagApplied": response.get("selfRagApplied"),
                    "rerankApplied": response.get("rerankApplied"),
                    "hybridSearchApplied": response.get("hybridSearchApplied"),
                    "multiQueryApplied": response.get("multiQueryApplied"),
                    "sourceScores": [{"docIds": extract_doc_ids([s]), "rerankScore": s.get("rerankScore")}
                                     for s in sources],
                    "agenticIterations": response.get("agenticIterations"),
                    "agenticStopReason": response.get("agenticStopReason"),
                    "retrievedDocumentIds": retrieved, "citedDocumentIds": cited},
    }


def load_answered(path: Path) -> set[str]:
    if not path.exists():
        return set()
    with path.open(encoding="utf-8") as fh:
        return {json.loads(line)["question_id"] for line in fh if line.strip()}


def cmd_answer(args: argparse.Namespace) -> None:
    work = Path(args.work_dir)
    questions = load_questions(Path(args.questions) if args.questions else work / "questions.jsonl")
    if args.question_type:
        questions = [q for q in questions if q.question_type in args.question_type]
    if args.per_type:
        questions = sample_per_type(questions, args.per_type)
    out = Path(args.output) if args.output else work / "answers.jsonl"
    answered = load_answered(out)
    todo = [q for q in questions if q.question_id not in answered][: args.limit]
    print(f"[answer] {len(todo)} questions à traiter ({len(answered)} déjà dans {out})", file=sys.stderr)

    client = SpectraClient(args.api, args.timeout)
    failures = 0
    # Écriture depuis le seul thread principal : pas d'entrelacement de lignes JSONL.
    with out.open("a", encoding="utf-8") as fh, ThreadPoolExecutor(max_workers=args.parallelism) as pool:
        futures = {pool.submit(answer_one, client, q, args): q for q in todo}
        for count, future in enumerate(as_completed(futures), 1):
            q = futures[future]
            try:
                record = future.result()
            except Exception as exc:  # noqa: BLE001 — une question en échec ne stoppe pas le banc
                failures += 1
                print(f"[answer] {q.question_id} échec : {exc}", file=sys.stderr)
                continue
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
            fh.flush()
            print(f"[answer] {count}/{len(todo)} {q.question_id} "
                  f"({len(record['document_ids'])} docs, {record['spectra']['durationMs']} ms)", file=sys.stderr)
    if failures:
        sys.exit(f"[answer] {failures} échec(s) — relancer la commande pour les reprendre")


RETRIEVAL_KS = (1, 3, 5, 10)


def retrieval_report(questions: list[Question], rankings: dict[str, list[str]],
                     ks: tuple[int, ...] = RETRIEVAL_KS) -> dict:
    """Qualité du classement de documents (sans juge) : rappel@k, hit@k, MRR, documents hors référence.

    ``rankings`` : question_id → doc_ids distincts, dans l'ordre de classement. Seules les questions
    avec documents de référence et un classement présent comptent. « Hors référence » = documents
    absents de la référence : une approximation des « invalid extra docs » du banc, qui écarte en
    plus ceux que ses juges tiennent pour valides.
    """
    per_type: dict[str, list[dict]] = defaultdict(list)
    for q in questions:
        if q.question_type in NO_GOLD_TYPES or not q.expected_doc_ids or q.question_id not in rankings:
            continue
        gold = {d.lower() for d in q.expected_doc_ids}
        ranking = [d.lower() for d in rankings[q.question_id]]
        row = {"mrr": next((1.0 / (i + 1) for i, d in enumerate(ranking) if d in gold), 0.0)}
        for k in ks:
            top = set(ranking[:k])
            row[f"recall@{k}"] = len(gold & top) / len(gold)
            row[f"hit@{k}"] = 1.0 if gold & top else 0.0
            row[f"extra@{k}"] = float(len(top - gold))
        per_type[q.question_type].append(row)

    def mean(rows: list[dict]) -> dict:
        return {key: sum(r[key] for r in rows) / len(rows) for key in rows[0]} | {"n": len(rows)}

    flat = [r for rows in per_type.values() for r in rows]
    return {"overall": mean(flat) if flat else {"n": 0},
            "by_type": {t: mean(rows) for t, rows in sorted(per_type.items())}}


def retrieve_one(client: SpectraClient, q: Question, args: argparse.Namespace) -> dict:
    payload = build_query(q.question, args)
    started = time.time()
    response = with_retry(lambda: client.post_json("/api/query/retrieve", payload), attempts=3,
                          before_retry=lambda: recover(client, args))
    sources = response.get("sources") or []
    return {
        "question_id": q.question_id,
        "document_ids": extract_doc_ids(sources),
        "sourceScores": [{"docIds": extract_doc_ids([s]), "rerankScore": s.get("rerankScore")} for s in sources],
        "rerankApplied": response.get("rerankApplied"),
        "hybridSearchApplied": response.get("hybridSearchApplied"),
        "multiQueryApplied": response.get("multiQueryApplied"),
        "durationMs": response.get("durationMs"),
        "clientMs": round((time.time() - started) * 1000),
    }


def cmd_retrieve(args: argparse.Namespace) -> None:
    work = Path(args.work_dir)
    questions = load_questions(Path(args.questions) if args.questions else work / "questions.jsonl")
    if args.question_type:
        questions = [q for q in questions if q.question_type in args.question_type]
    if args.per_type:
        questions = sample_per_type(questions, args.per_type)
    out = Path(args.output) if args.output else work / "retrieval.jsonl"
    done = load_answered(out)
    todo = [q for q in questions if q.question_id not in done][: args.limit]
    print(f"[retrieve] {len(todo)} questions à traiter ({len(done)} déjà dans {out})", file=sys.stderr)

    client = SpectraClient(args.api, args.timeout)
    with out.open("a", encoding="utf-8") as fh, ThreadPoolExecutor(max_workers=args.parallelism) as pool:
        futures = {pool.submit(retrieve_one, client, q, args): q for q in todo}
        for count, future in enumerate(as_completed(futures), 1):
            q = futures[future]
            try:
                fh.write(json.dumps(future.result(), ensure_ascii=False) + "\n")
                fh.flush()
            except Exception as exc:  # noqa: BLE001 — une question en échec ne stoppe pas la mesure
                print(f"[retrieve] {q.question_id} échec : {exc}", file=sys.stderr)
            if count % 25 == 0:
                print(f"[retrieve] {count}/{len(todo)}", file=sys.stderr)

    with out.open(encoding="utf-8") as fh:
        rankings = {r["question_id"]: r["document_ids"] for r in map(json.loads, filter(str.strip, fh))}
    print(json.dumps(retrieval_report(questions, rankings), indent=2))


def cmd_recall(args: argparse.Namespace) -> None:
    work = Path(args.work_dir)
    questions = load_questions(Path(args.questions) if args.questions else work / "questions.jsonl")
    answers_path = Path(args.answers) if args.answers else work / "answers.jsonl"
    with answers_path.open(encoding="utf-8") as fh:
        answers = {r["question_id"]: r.get("document_ids", []) for r in map(json.loads, filter(str.strip, fh))}
    print(json.dumps(recall_report(questions, answers), indent=2))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--work-dir", default="data/enterprise-rag-bench")
    parser.add_argument("--api", default="http://localhost:8080")
    parser.add_argument("--timeout", type=float, default=600.0, help="délai HTTP (s) ; un LLM local est lent")
    parser.add_argument("--wait-ready", type=float, default=600.0, metavar="SECONDES",
                        help="attend la disponibilité de l'API avant ingest/answer/retrieve, et entre deux essais "
                             "après une coupure (0 = ne pas attendre)")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("prepare", help="télécharger et matérialiser le corpus")
    p.add_argument("--subset", type=int, metavar="N", help="références + N distracteurs aléatoires")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--extend", action="store_true",
                   help="avec --subset : conserve les documents déjà préparés et ajoute des distracteurs jusqu'à N")
    p.add_argument("--documents-parquet", help="parquet déjà téléchargé")
    p.add_argument("--source-dir", help="generated_data/ d'un clone GitHub du banc (sans Hugging Face ni pyarrow)")
    p.add_argument("--documents-url", default=DOCUMENTS_URL)
    p.add_argument("--questions-url", default=QUESTIONS_URL)
    p.set_defaults(func=cmd_prepare)

    p = sub.add_parser("ingest", help="indexer le corpus dans Spectra")
    p.add_argument("--batch-size", type=int, default=MAX_BATCH_FILES)
    p.add_argument("--poll", type=float, default=5.0)
    p.set_defaults(func=cmd_ingest)

    p = sub.add_parser("answer", help="poser les questions et écrire answers.jsonl")
    p.add_argument("--questions")
    p.add_argument("--output")
    p.add_argument("--collection")
    p.add_argument("--max-context-chunks", type=int, default=10)
    p.add_argument("--top-candidates", type=int, default=50)
    p.add_argument("--self-rag", choices=("default", "on", "off"), default="default",
                   help="réflexion Self-RAG (exige SPECTRA_SELF_RAG_ENABLED côté serveur)")
    p.add_argument("--max-docs", type=int, default=10, help="document_ids gardés (les extras sont pénalisés)")
    p.add_argument("--sources", choices=("all", "cited"), default="all",
                   help="document_ids rendus : tous les documents récupérés, ou les seuls cités [n] dans la "
                        "réponse (repli sur tous si aucune citation). N'agit que sur recall et invalid extra "
                        "docs, pas sur correctness × completeness.")
    p.add_argument("--hybrid", choices=("default", "on", "off"), default="default",
                   help="force la recherche hybride pour cette requête (A/B sans redémarrage)")
    p.add_argument("--multi-query", choices=("default", "on", "off"), default="default",
                   help="reformulations de la question (exige SPECTRA_MULTI_QUERY_ENABLED côté serveur)")
    p.add_argument("--dedup", choices=("default", "none", "jaccard", "mmr"), default="default",
                   help="déduplication des passages (exige SPECTRA_SEMANTIC_DEDUP_ENABLED côté serveur)")
    p.add_argument("--rerank-min-score", type=float, metavar="S",
                   help="écarte les passages dont le score du reranker (sigmoïde, 0-1) est sous S")
    p.add_argument("--neighbor-window", type=int, metavar="N",
                   help="chunks voisins (± N, 0-5) ajoutés à chaque passage : approche le « document entier »")
    p.add_argument("--temperature", type=float, default=0.0)
    p.add_argument("--question-suffix", default="\n\nAnswer in English.",
                   help="persona et prompts Spectra sont en français ; le juge attend de l'anglais")
    p.add_argument("--question-type", action="append", help="filtrer par type (répétable)")
    p.add_argument("--per-type", type=int, metavar="N", help="échantillon : N premières questions par catégorie")
    p.add_argument("--limit", type=int)
    p.add_argument("--parallelism", type=int, default=1)
    p.set_defaults(func=cmd_answer)

    p = sub.add_parser("retrieve", help="recherche seule, sans génération : rappel@k et MRR")
    p.add_argument("--questions")
    p.add_argument("--output")
    p.add_argument("--collection")
    p.add_argument("--max-context-chunks", type=int, default=10)
    p.add_argument("--top-candidates", type=int, default=50)
    p.add_argument("--temperature", type=float, default=0.0)
    # Pas de suffixe de langue : il n'a de sens que pour la génération et fausserait le classement.
    p.add_argument("--question-suffix", default="")
    p.add_argument("--hybrid", choices=("default", "on", "off"), default="default")
    p.add_argument("--multi-query", choices=("default", "on", "off"), default="default")
    p.add_argument("--dedup", choices=("default", "none", "jaccard", "mmr"), default="default")
    p.add_argument("--rerank-min-score", type=float, metavar="S")
    p.add_argument("--neighbor-window", type=int, metavar="N")
    p.add_argument("--question-type", action="append")
    p.add_argument("--per-type", type=int, metavar="N")
    p.add_argument("--limit", type=int)
    p.add_argument("--parallelism", type=int, default=1)
    p.add_argument("--rerank", choices=("default", "on", "off"), default="default",
                   help="désactiver le reranking pour mesurer les candidats avant reclassement")
    p.set_defaults(func=cmd_retrieve)

    p = sub.add_parser("recall", help="rappel documentaire hors-ligne")
    p.add_argument("--questions")
    p.add_argument("--answers")
    p.set_defaults(func=cmd_recall)
    return parser


API_COMMANDS = {"ingest", "answer", "retrieve"}


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    if args.command in API_COMMANDS and args.wait_ready > 0:
        try:
            SpectraClient(args.api, args.timeout).wait_ready(args.wait_ready)
        except TimeoutError as exc:
            sys.exit(f"[{args.command}] {exc}")
    args.func(args)


if __name__ == "__main__":
    main()
