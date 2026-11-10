#!/usr/bin/env python3
"""Évaluation fiable pour EnterpriseRAG-Bench : lancer le juge sans zéros silencieux, comparer sans bruit.

Deux pièges ont coûté des résultats entiers pendant les mesures de Spectra :

* **Un juge en panne ne fait pas échouer l'évaluateur.** Crédit épuisé (``credit_balance_exhausted``) ou
  quota gratuit atteint (``free-models-per-day``) : chaque appel échoue, l'évaluateur d'Onyx compte alors
  chaque question comme fausse avec 0 % de complétude, et finit « avec succès ». Le seul signe est une
  ligne ``[WARN] … citation stripping failed`` par question. Un 0,0 de score a été rapporté tel quel.
* **Une exécution sur 5 questions par catégorie ne départage rien.** Un écart de quelques points entre deux
  réglages est du bruit tant qu'on ne le mesure pas question par question.

Sous-commandes :

    judge    sonde l'API du juge, lance ``metrics_based_eval``, refuse un résultat dont des appels ont
             échoué (le fichier est renommé ``….INVALID-judge-failures.json``) ;
    compare  différence appariée par question entre deux résultats : moyenne, intervalle de confiance
             bootstrap à 95 %, victoires / défaites / égalités, détail par catégorie.

Bibliothèque standard uniquement. L'évaluateur lui-même est un dépôt externe
(https://github.com/onyx-dot-app/EnterpriseRAG-Bench) passé par ``--evaluator-dir``.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import subprocess
import sys
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path

EXIT_JUDGE_UNAVAILABLE = 3
EXIT_JUDGE_FAILURES = 4

# Lignes de l'évaluateur qui signalent un appel au juge perdu (et repli sur la valeur d'origine).
JUDGE_FAILURE_PATTERN = re.compile(r"\[WARN\].*(?:failed|error)", re.IGNORECASE)

METRICS = ("score", "correct", "completeness", "recall", "extra")

# Juge par défaut de chaque fournisseur pris en charge par l'évaluateur d'Onyx (src/llm/factory.py).
DEFAULT_JUDGE_MODELS = {"openai": "gpt-5.4", "anthropic": "claude-opus-5-5"}


class JudgeUnavailable(RuntimeError):
    """L'API du juge refuse les appels (clé, crédit ou quota) : inutile de lancer l'évaluateur."""


# ── Sonde du juge ─────────────────────────────────────────────────────────────


def classify_judge_error(code: int, body: str) -> str:
    lowered = body.lower()
    if "insufficient_quota" in lowered or "credit" in lowered or "billing" in lowered:
        return "crédit épuisé : rechargez le compte du juge"
    if "free-models-per-day" in lowered or "rate limit" in lowered or code == 429:
        return "quota ou débit atteint (HTTP 429) : attendez ou changez de modèle"
    if code in (401, 403):
        return f"clé refusée (HTTP {code})"
    return f"HTTP {code}"


def preflight_judge(base_url: str, api_key: str, model: str, timeout: float = 60.0) -> None:
    """Un appel minimal au modèle juge. Lève :class:`JudgeUnavailable` avec la cause lisible."""
    payload = json.dumps({"model": model, "max_completion_tokens": 8,
                          "messages": [{"role": "user", "content": "ok"}]}).encode("utf-8")
    request = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions", data=payload, method="POST",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise JudgeUnavailable(f"{classify_judge_error(exc.code, body)} — {base_url} ({model})") from exc
    except (urllib.error.URLError, TimeoutError, ConnectionError) as exc:
        raise JudgeUnavailable(f"API du juge injoignable : {exc}") from exc


# Sonde Anthropic exécutée par l'interpréteur de l'évaluateur, qui installe le SDK officiel (requirements
# d'Onyx) : ce script reste en bibliothèque standard. Code de sortie 3 et cause lisible sur stderr.
ANTHROPIC_PREFLIGHT = """
import os, sys
import anthropic

client = anthropic.Anthropic(api_key=os.environ["LLM_API_KEY"], max_retries=2)
try:
    response = client.messages.create(
        model=sys.argv[1], max_tokens=1024, output_config={"effort": "low"},
        messages=[{"role": "user", "content": "Reply with the single word: ok"}])
except anthropic.AuthenticationError:
    sys.exit("clé refusée (HTTP 401)")
except anthropic.PermissionDeniedError:
    sys.exit("accès refusé (HTTP 403)")
except anthropic.NotFoundError:
    sys.exit("modèle inconnu (HTTP 404)")
except anthropic.RateLimitError:
    sys.exit("quota ou débit atteint (HTTP 429) : attendez ou changez de modèle")
except anthropic.APIStatusError as exc:
    sys.exit(f"HTTP {exc.status_code} : {exc.message}")
except anthropic.APIConnectionError as exc:
    sys.exit(f"API du juge injoignable : {exc}")
if response.stop_reason == "refusal":
    sys.exit("le juge refuse la requête de contrôle (stop_reason refusal)")
"""


def preflight_anthropic_judge(python: str, model: str, timeout: float = 120.0) -> None:
    """Un appel minimal au juge Anthropic via le SDK de l'évaluateur. Lève :class:`JudgeUnavailable`."""
    try:
        done = subprocess.run([python, "-c", ANTHROPIC_PREFLIGHT, model], capture_output=True, text=True,
                              timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise JudgeUnavailable(f"sonde du juge impossible : {exc}") from exc
    if done.returncode:
        cause = done.stderr.strip().splitlines()[-1] if done.stderr.strip() else f"code {done.returncode}"
        raise JudgeUnavailable(f"{cause} — Anthropic ({model})")


def count_judge_failures(log_text: str) -> int:
    return sum(1 for line in log_text.splitlines() if JUDGE_FAILURE_PATTERN.search(line))


def judge_provider() -> tuple[str, str]:
    """Fournisseur et modèle du juge, tels que l'évaluateur d'Onyx les lit (``LLM_PROVIDER``, ``LLM_MODEL_NAME``)."""
    provider = os.environ.get("LLM_PROVIDER", "openai").lower()
    if provider not in DEFAULT_JUDGE_MODELS:
        raise ValueError(f"LLM_PROVIDER inconnu de l'évaluateur : {provider} (openai ou anthropic)")
    return provider, os.environ.get("LLM_MODEL_NAME") or DEFAULT_JUDGE_MODELS[provider]


def cmd_judge(args: argparse.Namespace) -> int:
    base_url = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1")
    api_key = os.environ.get("LLM_API_KEY", "")
    try:
        provider, model = judge_provider()
    except ValueError as exc:
        print(f"[judge] {exc}", file=sys.stderr)
        return EXIT_JUDGE_UNAVAILABLE
    if not api_key:
        print("LLM_API_KEY est vide : exportez la clé du juge (ou une valeur factice si un proxy l'injecte).",
              file=sys.stderr)
        return EXIT_JUDGE_UNAVAILABLE
    if not args.skip_preflight:
        try:
            if provider == "anthropic":
                preflight_anthropic_judge(args.python, model)
            else:
                preflight_judge(base_url, api_key, model)
        except JudgeUnavailable as exc:
            print(f"[judge] juge indisponible : {exc}", file=sys.stderr)
            return EXIT_JUDGE_UNAVAILABLE

    evaluator = Path(args.evaluator_dir)
    command = [args.python, "-m", "src.scripts.answer_evaluation.metrics_based_eval",
               "--answers-file", args.answers_file, "--questions-file", args.questions_file,
               "--results-file", args.results_file, "--parallelism", str(args.parallelism), "--resume"]
    if not args.with_correction:
        command.append("--no-correction")
    env = {**os.environ, "LLM_PROVIDER": provider, "LLM_MODEL_NAME": model}
    done = subprocess.run(command, cwd=evaluator, env=env, capture_output=True, text=True, check=False)
    sys.stdout.write(done.stdout)
    sys.stderr.write(done.stderr)

    failures = count_judge_failures(done.stdout + "\n" + done.stderr)
    results = Path(args.results_file) if Path(args.results_file).is_absolute() else evaluator / args.results_file
    if failures and not args.allow_judge_failures:
        invalid = results.with_name(results.stem + ".INVALID-judge-failures" + results.suffix)
        if results.exists():
            results.replace(invalid)
        print(f"[judge] {failures} appel(s) du juge ont échoué : les notes sont fausses (zéros par défaut). "
              f"Résultat écarté ({invalid.name}). Corrigez le juge puis relancez : l'évaluateur reprend.",
              file=sys.stderr)
        return EXIT_JUDGE_FAILURES
    return done.returncode


# ── Comparaison appariée ──────────────────────────────────────────────────────


def load_metric(results_path: Path, metric: str) -> tuple[dict[str, float], dict[str, str]]:
    """question_id → valeur, et question_id → catégorie, depuis un résultat de ``metrics_based_eval``.

    ``score`` est le critère de classement : correct × complétude (en %). ``recall`` et ``extra``
    n'existent que pour les catégories qui ont des documents de référence.
    """
    if metric not in METRICS:
        raise ValueError(f"métrique inconnue : {metric} (attendu : {', '.join(METRICS)})")
    values: dict[str, float] = {}
    types: dict[str, str] = {}
    for q in json.loads(results_path.read_text(encoding="utf-8"))["questions"]:
        completeness = q.get("completeness_pct") or 0.0
        value = {
            "score": completeness if q.get("answer_correct") else 0.0,
            "correct": 100.0 if q.get("answer_correct") else 0.0,
            "completeness": completeness,
            "recall": q.get("document_recall_pct"),
            "extra": q.get("invalid_extra_docs"),
        }[metric]
        if value is None or isinstance(value, str):  # « N/A » hors catégories à documents de référence
            continue
        values[q["question_id"]] = float(value)
        types[q["question_id"]] = q.get("question_type", "?")
    return values, types


def paired_report(a: dict[str, float], b: dict[str, float], types: dict[str, str] | None = None,
                  resamples: int = 10_000, seed: int = 0) -> dict:
    """Différence ``b - a`` question par question, avec intervalle de confiance bootstrap à 95 %.

    L'appariement est ce qui rend la comparaison utile : une question difficile l'est pour les deux
    réglages, donc seule la différence compte, pas le niveau.
    """
    ids = sorted(set(a) & set(b))
    if not ids:
        raise ValueError("aucune question commune entre les deux résultats")
    diffs = [b[i] - a[i] for i in ids]
    n = len(diffs)
    rng = random.Random(seed)
    means = sorted(sum(rng.choices(diffs, k=n)) / n for _ in range(resamples))
    low, high = means[int(0.025 * resamples)], means[int(0.975 * resamples) - 1]
    report = {
        "n": n,
        "mean_a": sum(a[i] for i in ids) / n,
        "mean_b": sum(b[i] for i in ids) / n,
        "mean_diff": sum(diffs) / n,
        "ci95": [low, high],
        "significant": low > 0 or high < 0,
        "wins": sum(1 for d in diffs if d > 0),
        "losses": sum(1 for d in diffs if d < 0),
        "ties": sum(1 for d in diffs if d == 0),
    }
    if types:
        by_type: dict[str, list[float]] = defaultdict(list)
        for i, d in zip(ids, diffs):
            by_type[types.get(i, "?")].append(d)
        report["by_type"] = {t: {"n": len(v), "mean_diff": sum(v) / len(v)} for t, v in sorted(by_type.items())}
    return report


def cmd_compare(args: argparse.Namespace) -> int:
    a, types = load_metric(Path(args.baseline), args.metric)
    b, _ = load_metric(Path(args.candidate), args.metric)
    report = paired_report(a, b, types, resamples=args.resamples, seed=args.seed)
    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
        return 0
    lo, hi = report["ci95"]
    verdict = "écart distinct du bruit" if report["significant"] else "indiscernable du bruit"
    print(f"{args.metric} sur {report['n']} questions communes : "
          f"{report['mean_a']:.1f} → {report['mean_b']:.1f} ({report['mean_diff']:+.1f}), IC95 % [{lo:+.1f}, {hi:+.1f}] "
          f"— {verdict}")
    print(f"  mieux : {report['wins']}   moins bien : {report['losses']}   égal : {report['ties']}")
    for t, v in report.get("by_type", {}).items():
        print(f"  {t:26} n={v['n']:<3} {v['mean_diff']:+7.1f}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("judge", help="lancer l'évaluateur officiel, sans zéros silencieux")
    p.add_argument("--evaluator-dir", required=True, help="clone de EnterpriseRAG-Bench")
    p.add_argument("--answers-file", required=True)
    p.add_argument("--questions-file", required=True)
    p.add_argument("--results-file", required=True)
    p.add_argument("--python", default=sys.executable, help="interpréteur du venv de l'évaluateur")
    p.add_argument("--parallelism", type=int, default=4)
    p.add_argument("--with-correction", action="store_true",
                   help="réactive la correction du jeu de référence (à éviter sur un sous-corpus)")
    p.add_argument("--skip-preflight", action="store_true")
    p.add_argument("--allow-judge-failures", action="store_true")
    p.set_defaults(func=cmd_judge)

    p = sub.add_parser("compare", help="différence appariée entre deux résultats, avec intervalle de confiance")
    p.add_argument("baseline", help="results.json de référence")
    p.add_argument("candidate", help="results.json à comparer")
    p.add_argument("--metric", choices=METRICS, default="score")
    p.add_argument("--resamples", type=int, default=10_000)
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_compare)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
