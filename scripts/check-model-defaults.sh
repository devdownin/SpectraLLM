#!/usr/bin/env bash
# Vérifie la cohérence des modèles GGUF par défaut entre TOUS les environnements.
#
# Pourquoi ce script existe
# -------------------------
# Le nom des fichiers GGUF est répété dans quatre langages de configuration —
# shell, batch, YAML (Compose et Kubernetes) et Java — qu'aucun mécanisme ne peut
# faire dériver d'une variable commune : un manifest Kubernetes ne « source » pas
# un fichier shell. La duplication est donc irréductible.
#
# Ce qui est évitable, en revanche, c'est qu'elle dérive en silence. Changer de
# modèle demande aujourd'hui une quinzaine d'éditions cohérentes ; en oublier une
# produit un bug qui ne se manifeste que sur un seul environnement — Docker
# fonctionne, Kubernetes non, ou l'inverse. Ce script transforme cette dérive en
# échec bruyant de CI.
#
# Deux règles :
#   1. Tout fichier .gguf nommé dans un fichier opérationnel est soit le modèle de
#      chat par défaut, soit celui d'embedding, soit un artefact amont explicitement
#      déclaré ci-dessous (le nom du fichier tel qu'il existe chez le publieur, avant
#      renommage local).
#   2. Un même modèle est téléchargé depuis la MÊME URL partout. Une divergence de
#      quantification entre environnements est particulièrement pernicieuse sur
#      l'embedding : les vecteurs produits diffèrent, donc un index construit dans un
#      environnement se dégrade lorsqu'il est interrogé depuis un autre.
#
# La source de vérité est .env.example : c'est le fichier que l'utilisateur copie.
#
# Usage : scripts/check-model-defaults.sh
set -euo pipefail

cd "$(dirname "$0")/.."

SOURCE_OF_TRUTH=".env.example"

# Fichiers opérationnels : ceux qui pilotent réellement un déploiement ou la CI.
# La documentation et les notes de version sont exclues — elles décrivent un état
# passé et n'ont pas vocation à être réécrites à chaque changement de modèle.
OPERATIONAL_FILES=(
  "deploy/docker/docker-compose.yml"
  "deploy/k8s/base/01-configmap.yaml"
  "deploy/k8s/seed/seed-models.yaml"
  ".github/workflows/ci.yml"
  "backend/src/main/java/fr/spectra/service/StartupOrchestrator.java"
  "scripts/benchmark.sh"
  "scripts/check-models.sh"
  "scripts/detect-env.sh"
  "scripts/detect-env.bat"
  "scripts/llm-chat-entrypoint.sh"
  "scripts/pipeline.sh"
  "scripts/pipeline.bat"
)

# Noms de GGUF légitimement différents des deux défauts. Tout ce qui n'est pas
# listé ici est signalé — c'est ainsi qu'une divergence de quantification entre
# environnements est détectée.
ALLOWED_OTHER_GGUF=(
  # Artefact amont : le téléchargement le renomme en embed.gguf.
  "nomic-embed-text-v1.5.Q4_K_M.gguf"
  # Exemples de commandes dans le texte d'aide de benchmark.sh (llama-quantize,
  # llama-perplexity) : des noms génériques, pas des défauts de déploiement.
  "model-f16.gguf"
  "model-q4.gguf"
  "model-iq3.gguf"
  "model.gguf"
)

errors=0

fail() {
  printf '  ✗ %s\n' "$1" >&2
  errors=$((errors + 1))
}

# Valeur d'une variable dans .env.example (première affectation non commentée).
read_default() {
  grep -E "^${1}=" "$SOURCE_OF_TRUTH" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'"
}

CHAT_FILE="$(read_default LLM_CHAT_MODEL_FILE)"
EMBED_FILE="$(read_default LLM_EMBED_MODEL_FILE)"

if [ -z "$CHAT_FILE" ] || [ -z "$EMBED_FILE" ]; then
  echo "✗ Impossible de lire LLM_CHAT_MODEL_FILE / LLM_EMBED_MODEL_FILE depuis $SOURCE_OF_TRUTH" >&2
  exit 1
fi

echo "Source de vérité : $SOURCE_OF_TRUTH"
echo "  chat      : $CHAT_FILE"
echo "  embedding : $EMBED_FILE"
echo

# ── Règle 1 — aucun nom de GGUF inattendu dans les fichiers opérationnels ─────

is_known_gguf() {
  local candidate="$1"
  [ "$candidate" = "$CHAT_FILE" ] && return 0
  [ "$candidate" = "$EMBED_FILE" ] && return 0
  local allowed
  for allowed in "${ALLOWED_OTHER_GGUF[@]}"; do
    [ "$candidate" = "$allowed" ] && return 0
  done
  return 1
}

echo "Règle 1 — noms de fichiers GGUF"
for file in "${OPERATIONAL_FILES[@]}"; do
  if [ ! -f "$file" ]; then
    fail "$file : fichier absent (liste OPERATIONAL_FILES à mettre à jour)"
    continue
  fi
  # sed retire le chemin éventuel, puis le tiret de la syntaxe shell ${VAR:-defaut}
  # qui serait sinon capturé comme premier caractère du nom.
  while IFS= read -r name; do
    [ -z "$name" ] && continue
    if ! is_known_gguf "$name"; then
      fail "$file : « $name » ne correspond ni au défaut chat ni au défaut embedding"
    fi
  done < <(grep -oE '[A-Za-z0-9._-]+\.gguf' "$file" | sed -e 's#.*/##' -e 's/^-*//' | sort -u)
done

# ── Règle 2 — une seule URL amont par modèle ─────────────────────────────────

echo "Règle 2 — URLs de téléchargement"
mapfile -t gguf_urls < <(
  grep -rhoE 'https://huggingface\.co/[A-Za-z0-9._/-]+\.gguf' "${OPERATIONAL_FILES[@]}" \
    2>/dev/null | sort -u
)

# Regroupe par famille de modèle (segment « /<org>/<repo>/ ») : deux URL de la même
# famille pointant vers des artefacts différents = dérive de quantification.
declare -A family_urls=()
for url in "${gguf_urls[@]:-}"; do
  [ -z "$url" ] && continue
  family="$(printf '%s' "$url" | cut -d/ -f4-5)"
  if [ -n "${family_urls[$family]:-}" ] && [ "${family_urls[$family]}" != "$url" ]; then
    fail "modèle « $family » téléchargé depuis deux artefacts différents :"
    fail "    ${family_urls[$family]}"
    fail "    $url"
  else
    family_urls[$family]="$url"
  fi
done

echo
if [ "$errors" -gt 0 ]; then
  echo "✗ $errors incohérence(s) détectée(s)." >&2
  echo "  Alignez toutes les valeurs sur $SOURCE_OF_TRUTH, ou déclarez le nom" >&2
  echo "  attendu dans ALLOWED_OTHER_GGUF si l'écart est volontaire." >&2
  exit 1
fi

echo "✓ Modèles par défaut cohérents sur tous les environnements."
