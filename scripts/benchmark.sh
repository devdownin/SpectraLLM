#!/usr/bin/env bash
# =============================================================================
# benchmark.sh — Campagne de benchmark turboquant pour Spectra
#
# Mesure trois dimensions :
#   1. Débit natif llama-server  → llama-bench (PP + TG à plusieurs tailles)
#   2. Perplexité du modèle      → llama-perplexity (qualité de quantization)
#   3. Latence API Spectra       → curl sur /api/benchmark/*
#
# Usage :
#   ./scripts/benchmark.sh [--api-only] [--llama-only] [--question "..."]
#
# Prérequis :
#   - docker compose up -d (Spectra opérationnel)
#   - Les binaires llama-bench et llama-perplexity doivent être dans l'image
#     (reconstruire avec : docker compose build llama-cpp-chat)
# =============================================================================

set -euo pipefail

API_BASE="${SPECTRA_API:-http://localhost:8080}"
CHAT_CONTAINER="${CHAT_CONTAINER:-spectra-llama-chat}"
EMBED_CONTAINER="${EMBED_CONTAINER:-spectra-llama-embed}"
CHAT_MODEL_PATH="${CHAT_MODEL_PATH:-/fine-tuning/merged/phi-3.5-mini-Q4_K_M.gguf}"
EMBED_MODEL_PATH="/models/embed.gguf"
RAG_QUESTION="${RAG_QUESTION:-Quelle est la procédure principale décrite dans les documents ?}"
OUTPUT_DIR="${OUTPUT_DIR:-./data/benchmark}"
RUN_LLAMA=true
RUN_API=true

# ── Argument parsing ──────────────────────────────────────────────────────────

for arg in "$@"; do
  case $arg in
    --api-only)   RUN_LLAMA=false ;;
    --llama-only) RUN_API=false ;;
    --question=*) RAG_QUESTION="${arg#*=}" ;;
    *) echo "Option inconnue : $arg" && exit 1 ;;
  esac
done

# ── Setup ─────────────────────────────────────────────────────────────────────

mkdir -p "$OUTPUT_DIR"
TIMESTAMP=$(date +%Y%m%dT%H%M%S)
REPORT="$OUTPUT_DIR/benchmark_${TIMESTAMP}.md"

log() { echo "[benchmark] $*"; }
header() {
  echo ""
  echo "## $*"
  echo ""
} >> "$REPORT"

cat > "$REPORT" << EOF
# Benchmark Spectra × turboquant — ${TIMESTAMP}

> Généré par \`scripts/benchmark.sh\`

**Modèle chat :** \`${CHAT_MODEL_PATH}\`
**Modèle embed :** \`${EMBED_MODEL_PATH}\`
**Host API :** \`${API_BASE}\`

EOF

# ── 1. llama-bench : débit natif PP + TG ─────────────────────────────────────

if $RUN_LLAMA; then
  log "=== llama-bench : débit PP (prompt processing) et TG (text generation) ==="
  {
    header "1. Débit natif llama-bench (PP + TG)"
    echo "**Signification des colonnes :**"
    echo "- \`t/s PP\` : tokens traités par seconde lors de l'ingestion du prompt (prefill)"
    echo "- \`t/s TG\` : tokens générés par seconde lors de la génération de réponse"
    echo ""
    echo "Un TG élevé = réponses RAG rapides. Un PP élevé = traitement rapide du contexte."
    echo ""
  } >> "$REPORT"

  log "Exécution de llama-bench (chat model)..."
  BENCH_OUTPUT=$(docker exec "$CHAT_CONTAINER" llama-bench \
    -m "$CHAT_MODEL_PATH" \
    -p 128,512,1024 \
    -n 64,128 \
    -r 3 \
    -o md 2>/dev/null || echo "ERREUR: llama-bench non disponible dans l'image")

  {
    echo "\`\`\`"
    echo "$BENCH_OUTPUT"
    echo "\`\`\`"
    echo ""
  } >> "$REPORT"

  log "Exécution de llama-bench (embed model)..."
  EMBED_BENCH=$(docker exec "$EMBED_CONTAINER" llama-bench \
    -m "$EMBED_MODEL_PATH" \
    -p 128,512 \
    -n 0 \
    -r 3 \
    -o md \
    --embeddings 1 2>/dev/null || echo "ERREUR: llama-bench non disponible dans l'image")

  {
    echo "### Modèle d'embedding"
    echo "\`\`\`"
    echo "$EMBED_BENCH"
    echo "\`\`\`"
    echo ""
  } >> "$REPORT"
fi

# ── 2. llama-perplexity : qualité de quantization ─────────────────────────────

if $RUN_LLAMA; then
  log "=== llama-perplexity : qualité du modèle quantizé ==="
  {
    header "2. Perplexité (qualité de quantization)"
    echo "**Signification :** une perplexité basse = le modèle préserve mieux la connaissance."
    echo "Le delta avec F16 (référence) mesure la perte due à la quantization."
    echo "Turboquant vise à minimiser ce delta pour les formats IQ (1.5-bit, 2-bit)."
    echo ""
    echo "> Pour comparer, générer le même modèle en Q4_K_M, IQ3_M et IQ2_S :"
    echo "> \`\`\`bash"
    echo "> llama-quantize model-f16.gguf model-q4.gguf Q4_K_M"
    echo "> llama-quantize model-f16.gguf model-iq3.gguf IQ3_M"
    echo "> \`\`\`"
    echo ""
  } >> "$REPORT"

  # Télécharger un jeu de test de perplexité si absent
  PPLX_FILE="$OUTPUT_DIR/wikitext2.txt"
  if [ ! -f "$PPLX_FILE" ]; then
    log "Téléchargement de WikiText-2 pour la perplexité..."
    curl -sL \
      "https://huggingface.co/datasets/wikitext/resolve/main/wikitext-2-raw-v1/test-00000-of-00001.parquet" \
      -o "$OUTPUT_DIR/wikitext2.parquet" 2>/dev/null \
      || log "AVERTISSEMENT: téléchargement WikiText-2 échoué — perplexité ignorée"
    # Conversion parquet → texte brut (nécessite python3 + pandas + pyarrow)
    python3 -c "
import pandas as pd, sys
df = pd.read_parquet('$OUTPUT_DIR/wikitext2.parquet')
print('\n'.join(df['text'].dropna().tolist()))
" > "$PPLX_FILE" 2>/dev/null || echo "" > "$PPLX_FILE"
  fi

  if [ -s "$PPLX_FILE" ]; then
    log "Calcul de la perplexité..."
    docker cp "$PPLX_FILE" "$CHAT_CONTAINER:/tmp/wikitext2.txt"
    PPLX=$(docker exec "$CHAT_CONTAINER" llama-perplexity \
      -m "$CHAT_MODEL_PATH" \
      -f /tmp/wikitext2.txt \
      --chunks 20 2>/dev/null \
      | grep -E "Final perplexity|PPL" || echo "Perplexité non disponible")

    {
      echo "\`\`\`"
      echo "$PPLX"
      echo "\`\`\`"
      echo ""
    } >> "$REPORT"
  else
    {
      echo "> WikiText-2 non disponible. Exécutez manuellement :"
      echo "> \`docker exec spectra-llama-chat llama-perplexity -m /fine-tuning/merged/model.gguf -f /path/to/wiki.test.raw\`"
      echo ""
    } >> "$REPORT"
  fi
fi

# ── 3. Latence API Spectra ────────────────────────────────────────────────────

if $RUN_API; then
  log "=== Benchmark API Spectra ==="
  {
    header "3. Latence API Spectra (via /api/benchmark)"
  } >> "$REPORT"

  # 3a. Embedding
  log "Benchmark embedding (10 itérations)..."
  EMBED_RESULT=$(curl -sf "${API_BASE}/api/benchmark/embedding?iterations=10" \
    -H "Accept: application/json" 2>/dev/null \
    || echo '{"error": "API indisponible"}')

  {
    echo "### 3a. Débit embedding (10 × 512 tokens)"
    echo "\`\`\`json"
    echo "$EMBED_RESULT" | python3 -m json.tool 2>/dev/null || echo "$EMBED_RESULT"
    echo "\`\`\`"
    echo ""
  } >> "$REPORT"

  # 3b. LLM pure
  log "Benchmark LLM pure (3 itérations, peut prendre 5-10 min sur CPU)..."
  LLM_RESULT=$(curl -sf "${API_BASE}/api/benchmark/llm?iterations=3" \
    -H "Accept: application/json" \
    --max-time 600 2>/dev/null \
    || echo '{"error": "timeout ou API indisponible"}')

  {
    echo "### 3b. Latence LLM pure (3 générations)"
    echo "\`\`\`json"
    echo "$LLM_RESULT" | python3 -m json.tool 2>/dev/null || echo "$LLM_RESULT"
    echo "\`\`\`"
    echo ""
  } >> "$REPORT"

  # 3c. RAG bout en bout
  log "Benchmark RAG (5 itérations)..."
  ENCODED_Q=$(python3 -c "import urllib.parse; print(urllib.parse.quote('${RAG_QUESTION}'))")
  RAG_RESULT=$(curl -sf "${API_BASE}/api/benchmark/rag?iterations=5&maxChunks=2&question=${ENCODED_Q}" \
    -H "Accept: application/json" \
    --max-time 900 2>/dev/null \
    || echo '{"error": "timeout ou API indisponible"}')

  {
    echo "### 3c. Latence RAG bout en bout (5 × maxChunks=2)"
    echo "> Question : \`${RAG_QUESTION}\`"
    echo ""
    echo "\`\`\`json"
    echo "$RAG_RESULT" | python3 -m json.tool 2>/dev/null || echo "$RAG_RESULT"
    echo "\`\`\`"
    echo ""
  } >> "$REPORT"
fi

# ── Résumé ────────────────────────────────────────────────────────────────────

{
  header "4. Résumé et interprétation"
  echo "| Métrique | Valeur | Seuil acceptable |"
  echo "|----------|--------|-----------------|"
  echo "| TG (tokens/s chat) | voir llama-bench | ≥ 5 t/s |"
  echo "| PP (tokens/s prefill) | voir llama-bench | ≥ 50 t/s |"
  echo "| Embedding latence P50 | voir 3a | ≤ 500 ms / chunk |"
  echo "| LLM latence P50 | voir 3b | — (baseline) |"
  echo "| RAG latence P50 | voir 3c | ≤ 60 000 ms |"
  echo ""
  echo "**Interprétation turboquant :**"
  echo "- TG > standard llama.cpp sur la même machine = gain réel du fork"
  echo "- Perplexité IQ3/IQ2 ≈ Q4/Q5 standard = meilleure compression à iso-qualité"
  echo "- Si TG < 3 t/s : augmenter \`LLAMA_CHAT_PARALLELISM=1\` et vérifier les threads"
  echo ""
} >> "$REPORT"

log "Rapport généré : $REPORT"
echo ""
echo "=== RAPPORT : $REPORT ==="
cat "$REPORT"
