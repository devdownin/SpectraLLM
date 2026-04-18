#!/bin/sh
# Vérifie que les modèles GGUF requis sont présents dans /models.
# Echoue avec exit 1 si un fichier est absent ou trop petit (<1 Mo).
set -e

CHAT="${LLM_CHAT_MODEL_FILE:-Phi-4-mini-reasoning-UD-IQ1_S.gguf}"
EMBED="${LLM_EMBED_MODEL_FILE:-embed.gguf}"
OK=1

echo "=== Spectra — Vérification des modèles ==="

# ── Chat model ──────────────────────────────────────────────────────────────
SIZE_CHAT=$(wc -c < "/models/${CHAT}" 2>/dev/null || echo 0)
if [ ! -f "/models/${CHAT}" ] || [ "${SIZE_CHAT}" -lt 1048576 ]; then
  echo ""
  echo "[MANQUANT] Modèle de chat : ${CHAT}"
  echo "  Placez-le dans ./data/models/ puis relancez."
  echo ""
  echo "  Téléchargement (huggingface-cli) :"
  echo "    huggingface-cli download unsloth/Phi-4-mini-reasoning-GGUF \\"
  echo "      Phi-4-mini-reasoning-UD-IQ1_S.gguf --local-dir ./data/models/"
  echo ""
  echo "  Téléchargement (wget) :"
  echo "    wget -P ./data/models/ \\"
  echo "      https://huggingface.co/unsloth/Phi-4-mini-reasoning-GGUF/resolve/main/Phi-4-mini-reasoning-UD-IQ1_S.gguf"
  OK=0
else
  echo "[OK] Chat  : ${CHAT} ($(( SIZE_CHAT / 1048576 )) Mo)"
fi

# ── Embedding model ─────────────────────────────────────────────────────────
SIZE_EMBED=$(wc -c < "/models/${EMBED}" 2>/dev/null || echo 0)
if [ ! -f "/models/${EMBED}" ] || [ "${SIZE_EMBED}" -lt 1048576 ]; then
  echo ""
  echo "[MANQUANT] Modèle d'embedding : ${EMBED}"
  echo "  Placez-le dans ./data/models/ puis relancez."
  echo ""
  echo "  Téléchargement (huggingface-cli) :"
  echo "    huggingface-cli download nomic-ai/nomic-embed-text-v1.5-GGUF \\"
  echo "      nomic-embed-text-v1.5.Q4_0.gguf --local-dir ./data/models/ --filename embed.gguf"
  echo ""
  echo "  Téléchargement (wget) :"
  echo "    wget -O ./data/models/embed.gguf \\"
  echo "      https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_0.gguf"
  OK=0
else
  echo "[OK] Embed : ${EMBED} ($(( SIZE_EMBED / 1048576 )) Mo)"
fi

echo ""
if [ "${OK}" -eq 0 ]; then
  echo "=> Au moins un modèle est manquant. Suivez les instructions ci-dessus."
  exit 1
fi
echo "=> Tous les modèles sont présents. Démarrage des serveurs LLM..."
