#!/bin/sh
# Vérifie que les modèles GGUF requis sont présents dans /models.
# Echoue avec exit 1 si un fichier est absent ou trop petit (<1 Mo).
set -e

CHAT="${LLM_CHAT_MODEL_FILE:-Phi-4-mini-reasoning-UD-IQ1_S.gguf}"
EMBED="${LLM_EMBED_MODEL_FILE:-embed.gguf}"
OK=1

echo "=== Spectra — Vérification des modèles ==="

# Fonction générique pour vérifier, télécharger et valider un modèle
# check_and_download <nom_fichier> <url> <expected_sha256> <taille_min_octets> <libellé>
check_and_download() {
  local file=$1
  local url=$2
  local expected_hash=$3
  local min_size=$4
  local label=$5
  local filepath="/models/${file}"

  local size=$(stat -c %s "${filepath}" 2>/dev/null || echo 0)
  local needs_download=0

  if [ ! -f "${filepath}" ] || [ "${size}" -lt "${min_size}" ]; then
    needs_download=1
  elif [ -n "${expected_hash}" ]; then
    echo "  Vérification de l'intégrité de ${file}..."
    local current_hash=$(sha256sum "${filepath}" | awk '{print $1}')
    if [ "${current_hash}" != "${expected_hash}" ]; then
      echo "  [ERREUR] Checksum invalide pour ${file}. Attendu: ${expected_hash}, Obtenu: ${current_hash}"
      needs_download=1
    fi
  fi

  if [ ${needs_download} -eq 1 ]; then
    echo ""
    echo "[MANQUANT/INVALIDE] ${label} : ${file} absent, incomplet ou corrompu."
    echo "Téléchargement automatique en cours..."
    wget -q --show-progress -O "${filepath}" "${url}" || { echo "[ERREUR] Échec du téléchargement"; OK=0; return 1; }

    if [ -n "${expected_hash}" ]; then
      echo "  Vérification post-téléchargement..."
      local post_hash=$(sha256sum "${filepath}" | awk '{print $1}')
      if [ "${post_hash}" != "${expected_hash}" ]; then
        echo "  [ERREUR] Le fichier téléchargé est corrompu (checksum mismatch)."
        OK=0
        return 1
      fi
    fi
    size=$(stat -c %s "${filepath}" 2>/dev/null || echo 0)
  fi

  echo "[OK] ${label} : ${file} ($(( size / 1048576 )) Mo)"
}

# ── Chat model ──────────────────────────────────────────────────────────────
SIZE_CHAT=$(stat -c %s "/models/${CHAT}" 2>/dev/null || echo 0)
if [ ! -f "/models/${CHAT}" ] || [ "${SIZE_CHAT}" -lt 1048576 ]; then
  echo ""
  echo "[MANQUANT] Modèle de chat : ${CHAT} absent ou incomplet."
  echo "Téléchargement automatique en cours..."
  wget -q --show-progress -O "/models/${CHAT}" "https://huggingface.co/unsloth/Phi-4-mini-reasoning-GGUF/resolve/main/Phi-4-mini-reasoning-UD-IQ1_S.gguf" || { echo "[ERREUR] Échec du téléchargement"; OK=0; }
else
  echo "[OK] Chat  : ${CHAT} ($(( SIZE_CHAT / 1048576 )) Mo)"
fi
check_and_download "${CHAT}" "${CHAT_URL}" "${CHAT_SHA256}" 1048576 "Chat"

# ── Embedding model ─────────────────────────────────────────────────────────
SIZE_EMBED=$(stat -c %s "/models/${EMBED}" 2>/dev/null || echo 0)
if [ ! -f "/models/${EMBED}" ] || [ "${SIZE_EMBED}" -lt 1048576 ]; then
  echo ""
  echo "[MANQUANT] Modèle d'embedding : ${EMBED} absent ou incomplet."
  echo "Téléchargement automatique en cours..."
  wget -q --show-progress -O "/models/${EMBED}" "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_K_M.gguf" || { echo "[ERREUR] Échec du téléchargement"; OK=0; }
else
  echo "[OK] Embed : ${EMBED} ($(( SIZE_EMBED / 1048576 )) Mo)"
fi
check_and_download "${EMBED}" "${EMBED_URL}" "${EMBED_SHA256}" 1048576 "Embed"


# ── Cohérence avec le registre local (registry.json) ────────────────────────
# Le registre est écrit par spectra-api dans le même volume que les modèles.
# Absent au premier démarrage (créé par l'API) : dans ce cas on saute ce contrôle.
# Chaque modèle enregistré avec une source .gguf doit avoir son fichier présent —
# sinon son activation échouera. Non bloquant (avertissement) : un modèle
# supprimé du disque ne doit pas empêcher les serveurs LLM de démarrer.
REGISTRY="/models/registry.json"
if [ -f "${REGISTRY}" ]; then
  echo ""
  echo "--- Cohérence registre (registry.json) ---"
  # Extraction sh pure (pas de jq dans alpine) : valeurs "source" se terminant par .gguf
  SOURCES=$(grep -o '"source"[[:space:]]*:[[:space:]]*"[^"]*\.gguf"' "${REGISTRY}" 2>/dev/null \
            | sed 's/.*"\([^"]*\.gguf\)"$/\1/' | sort -u)
  for SRC in ${SOURCES}; do
    BASE=$(basename "${SRC}")
    if [ -f "${MODELS_DIR:-/models}/${BASE}" ]; then
      echo "[OK] ${BASE} (référencé par le registre)"
    else
      echo "[AVERTISSEMENT] Le registre référence '${SRC}' mais ${MODELS_DIR:-/models}/${BASE} est absent."
      echo "  L'activation de ce modèle échouera : replacez le fichier dans ./data/models/"
      echo "  ou ré-enregistrez le modèle (POST /api/fine-tuning/models/register)."
    fi
  done
fi

echo ""
if [ "${OK}" -eq 0 ]; then
  echo "=> Au moins un modèle est manquant ou corrompu. Suivez les instructions ci-dessus."
  exit 1
fi
echo "=> Tous les modèles sont présents. Démarrage des serveurs LLM..."
