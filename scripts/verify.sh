#!/usr/bin/env bash
# =============================================================================
# verify.sh — Rejoue localement les contrôles de l'intégration continue.
#
# POURQUOI CE FICHIER EXISTE
# La CI exécute huit commandes réparties sur quatre écosystèmes (Maven, npm, pytest,
# shell). Sans point d'entrée commun, savoir si un changement passe supposait d'ouvrir
# .github/workflows/ci.yml et de rejouer les commandes à la main — avec le risque d'en
# oublier une, ou d'ignorer que l'outil manque localement et n'a donc jamais rien vérifié.
#
# Un contrôle sauté est signalé COMME TEL, jamais compté comme réussi : un vert obtenu
# en n'exécutant rien est pire que pas de vert du tout.
#
# Usage :
#   ./scripts/verify.sh                  tout
#   ./scripts/verify.sh backend shell    seulement ces sections
#   ./scripts/verify.sh --list           liste les sections
#
# Sections : backend, spotbugs, models, python, services, shell, frontend, compose
# =============================================================================
set -uo pipefail   # pas -e : on veut exécuter TOUS les contrôles, puis conclure

cd "$(dirname "$0")/.." || exit 1

SECTIONS=(backend spotbugs models python services shell frontend compose)

if [ "${1:-}" = "--list" ]; then
  printf '%s\n' "${SECTIONS[@]}"
  exit 0
fi

# Sections demandées (toutes par défaut).
REQUESTED=("$@")
[ ${#REQUESTED[@]} -eq 0 ] && REQUESTED=("${SECTIONS[@]}")

wanted() {
  local s
  for s in "${REQUESTED[@]}"; do [ "$s" = "$1" ] && return 0; done
  return 1
}

# ── Comptes rendus ────────────────────────────────────────────────────────────
RESULTS=()
FAILED=0
SKIPPED=0

bold()  { printf '\n\033[1m── %s ──\033[0m\n' "$1"; }
pass()  { RESULTS+=("  \033[32mOK\033[0m       $1"); }
fail()  { RESULTS+=("  \033[31mÉCHEC\033[0m    $1"); FAILED=$((FAILED + 1)); }
skip()  { RESULTS+=("  \033[33mSAUTÉ\033[0m    $1 — $2"); SKIPPED=$((SKIPPED + 1)); }

# Exécute une commande, enregistre le verdict sous `label`.
run() {
  local label="$1"; shift
  bold "$label"
  if "$@"; then pass "$label"; else fail "$label"; fi
}

# Vérifie qu'un outil existe ; sinon marque la section sautée et rend faux.
need() {
  local tool="$1" label="$2"
  if command -v "$tool" >/dev/null 2>&1; then return 0; fi
  skip "$label" "$tool introuvable"
  return 1
}

# ── backend : `mvn package`, comme le job « Backend (Maven) » ─────────────────
if wanted backend && need mvn "backend (tests Maven)"; then
  run "backend (tests Maven)" mvn -B package -f backend/pom.xml
fi

# ── spotbugs : profil dédié, hors du build par défaut ─────────────────────────
if wanted spotbugs && need mvn "spotbugs"; then
  run "spotbugs" mvn -B -Pstatic-analysis -DskipTests compile spotbugs:check -f backend/pom.xml
fi

# ── models : cohérence des GGUF par défaut (aucune dépendance) ────────────────
if wanted models; then
  run "cohérence des modèles par défaut" ./scripts/check-model-defaults.sh
fi

# ── python : tests des scripts d'entraînement ─────────────────────────────────
if wanted python; then
  if python3 -m pytest --version >/dev/null 2>&1; then
    run "tests pytest (scripts/)" python3 -m pytest scripts/tests -q
  else
    skip "tests pytest (scripts/)" "pytest non installé (pip install pytest)"
  fi
fi

# ── services : ruff + pytest sur docparser et reranker ───────────────────────
# Ces deux suites tournaient en CI sans contrepartie locale : le script prétendait
# rejouer les contrôles de la CI tout en en ignorant deux. scripts/tests/test_verify_covers_ci.py
# empêche désormais ce type d'oubli de se reproduire.
if wanted services; then
  # Les dépendances de test (fastapi, httpx…) sont volontairement légères : les tests
  # bouchonnent torch et pymupdf. Absentes, la suite ne peut pas seulement échouer —
  # elle ne peut pas s'exécuter du tout. C'est un contrôle SAUTÉ, pas en échec :
  # confondre les deux ferait passer une dépendance manquante pour un bug du code.
  services_deps_missing=""
  python3 -c "import fastapi, httpx" >/dev/null 2>&1 || services_deps_missing=1
  python3 -m pytest --version >/dev/null 2>&1 || services_deps_missing=1

  for service in docparser reranker; do
    label="services/$service (ruff + pytest)"
    if [ -n "$services_deps_missing" ]; then
      skip "$label" "dépendances absentes (pip install -r services/requirements-test.txt)"
      continue
    fi
    bold "$label"
    ok=1
    if command -v ruff >/dev/null 2>&1; then
      ruff check "services/$service" || ok=0
    else
      echo "  (ruff absent — lint ignoré, les tests suivent)"
    fi
    (cd "services/$service" && python3 -m pytest tests/ -q) || ok=0
    [ "$ok" -eq 1 ] && pass "$label" || fail "$label"
  done
fi

# ── shell : shellcheck au seuil de la CI ──────────────────────────────────────
# C'est la section la plus facile à croire vérifiée sans l'être : shellcheck est
# rarement installé, et son absence passait jusqu'ici totalement inaperçue.
if wanted shell && need shellcheck "shellcheck"; then
  run "shellcheck" shellcheck -S error scripts/*.sh scripts/lib/*.sh
fi

# ── frontend : lint, tests, build ─────────────────────────────────────────────
if wanted frontend && need npm "frontend (lint + tests + build)"; then
  bold "frontend (lint + tests + build)"
  if [ ! -d frontend/node_modules ]; then
    echo "  installation des dépendances…"
    (cd frontend && npm install --no-audit --no-fund --silent)
  fi
  if (cd frontend && npm run lint && npm run test:coverage && npm run build); then
    pass "frontend (lint + tests + build)"
  else
    fail "frontend (lint + tests + build)"
  fi
fi

# ── compose : le fichier se résout-il ? ───────────────────────────────────────
if wanted compose; then
  if docker compose version >/dev/null 2>&1; then
    run "docker compose config" \
      docker compose --project-directory . -f deploy/docker/docker-compose.yml config -q
  else
    skip "docker compose config" "docker compose indisponible"
  fi
fi

# ── Verdict ───────────────────────────────────────────────────────────────────
printf '\n\033[1m══ Récapitulatif ══\033[0m\n'
for line in "${RESULTS[@]}"; do printf '%b\n' "$line"; done

printf '\n'
if [ "$FAILED" -gt 0 ]; then
  printf '\033[31m%d contrôle(s) en échec.\033[0m' "$FAILED"
  [ "$SKIPPED" -gt 0 ] && printf ' \033[33m%d sauté(s).\033[0m' "$SKIPPED"
  printf '\n'
  exit 1
fi

if [ "$SKIPPED" -gt 0 ]; then
  [ "$SKIPPED" -eq 1 ] && s='a été sauté' || s='ont été sautés'
  printf '\033[32mTous les contrôles exécutés passent\033[0m, mais \033[33m%d %s\033[0m —\n' "$SKIPPED" "$s"
  printf 'la CI, elle, les exécutera. Installez les outils manquants pour une vérification complète.\n'
  exit 0
fi

printf '\033[32mTous les contrôles passent.\033[0m\n'
