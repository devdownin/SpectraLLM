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
#   ./scripts/benchmark.sh [--api-only] [--llama-only] [--question="..."] [--followup="..."]
#
# Prérequis :
#   - docker compose --project-directory . -f deploy/docker/deploy/docker/docker-compose.yml up -d (Spectra opérationnel)
#   - Les binaires llama-bench et llama-perplexity doivent être dans l'image
#     (reconstruire avec : docker compose --project-directory . -f deploy/docker/deploy/docker/docker-compose.yml build llama-cpp-chat)
# =============================================================================

set -euo pipefail

API_BASE="${SPECTRA_API:-http://localhost:8080}"
# Noms des conteneurs et chemin du modèle alignés sur deploy/docker/docker-compose.yml
# (container_name: spectra-llm-chat / spectra-llm-embed ; ./data/models monté sur /models).
CHAT_CONTAINER="${CHAT_CONTAINER:-spectra-llm-chat}"
EMBED_CONTAINER="${EMBED_CONTAINER:-spectra-llm-embed}"
CHAT_MODEL_PATH="${CHAT_MODEL_PATH:-/models/${LLM_CHAT_MODEL_FILE:-Phi-4-mini-reasoning-UD-IQ1_S.gguf}}"
EMBED_MODEL_PATH="/models/embed.gguf"
RAG_QUESTION="${RAG_QUESTION:-Quelle est la procédure principale décrite dans les documents ?}"
RAG_FOLLOWUP="${RAG_FOLLOWUP:-Peux-tu donner plus de détails sur ce point ?}"
OUTPUT_DIR="${OUTPUT_DIR:-./data/benchmark}"
RUN_LLAMA=true
RUN_API=true
RUN_STRATEGIES=true

# ── Argument parsing ──────────────────────────────────────────────────────────

for arg in "$@"; do
  case $arg in
    --api-only)        RUN_LLAMA=false ;;
    --llama-only)      RUN_API=false; RUN_STRATEGIES=false ;;
    --no-strategies)   RUN_STRATEGIES=false ;;
    --question=*)      RAG_QUESTION="${arg#*=}" ;;
    --followup=*)      RAG_FOLLOWUP="${arg#*=}" ;;
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
    # Guarder le docker cp : sous set -e, un conteneur absent ferait avorter tout le
    # benchmark (et sauterait les benchmarks API + le rapport).
    if docker cp "$PPLX_FILE" "$CHAT_CONTAINER:/tmp/wikitext2.txt" 2>/dev/null; then
      PPLX=$(docker exec "$CHAT_CONTAINER" llama-perplexity \
        -m "$CHAT_MODEL_PATH" \
        -f /tmp/wikitext2.txt \
        --chunks 20 2>/dev/null \
        | grep -E "Final perplexity|PPL" || echo "Perplexité non disponible")
    else
      log "docker cp échoué (conteneur $CHAT_CONTAINER indisponible) — perplexité ignorée"
      PPLX="Perplexité non disponible (conteneur $CHAT_CONTAINER indisponible)"
    fi

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
  ENCODED_Q=$(RAG_Q="$RAG_QUESTION" python3 -c "import os,urllib.parse; print(urllib.parse.quote(os.environ['RAG_Q']))")
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

# ── 4. Benchmark des stratégies RAG avancées ─────────────────────────────────

if $RUN_API && $RUN_STRATEGIES; then
  log "=== Benchmark des stratégies RAG avancées ==="
  {
    header "4. Surcoût des stratégies RAG avancées"
    echo "Mesure l'impact de latence de chaque module additionnel."
    echo "Chaque stratégie doit être activée séparément via variable d'environnement."
    echo ""
  } >> "$REPORT"

  ENCODED_Q=$(RAG_Q="$RAG_QUESTION" python3 -c "import os,urllib.parse; print(urllib.parse.quote(os.environ['RAG_Q']))")
  ENCODED_FQ=$(RAG_FQ="$RAG_FOLLOWUP" python3 -c "import os,urllib.parse; print(urllib.parse.quote(os.environ['RAG_FQ']))")

  # 4a. Conversational RAG — question de suivi avec historique
  log "Benchmark Conversational RAG (question de suivi)..."
  # Passer les textes par l'environnement (jamais par interpolation shell) : une apostrophe
  # française (« Qu'est-ce… ») ou un guillemet casserait sinon le littéral Python / le JSON.
  CONV_BODY=$(RAG_Q="$RAG_QUESTION" RAG_FQ="$RAG_FOLLOWUP" python3 -c "
import json, os
body = {
  'question': os.environ['RAG_FQ'],
  'conversationHistory': [
    {'role': 'user',      'content': os.environ['RAG_Q']},
    {'role': 'assistant', 'content': 'Réponse simulée pour le benchmark.'}
  ]
}
print(json.dumps(body))
")
  CONV_START=$(date +%s%3N)
  CONV_RESULT=$(curl -sf "${API_BASE}/api/query" \
    -X POST \
    -H "Content-Type: application/json" \
    -d "$CONV_BODY" \
    --max-time 300 2>/dev/null \
    || echo '{"error": "timeout ou API indisponible"}')
  CONV_END=$(date +%s%3N)
  CONV_MS=$((CONV_END - CONV_START))

  {
    echo "### 4a. Conversational RAG — question de suivi (SPECTRA_CONVERSATIONAL_RAG_ENABLED=true)"
    echo "> Question de suivi : \`${RAG_FOLLOWUP}\`"
    echo "> Durée mesurée côté client : **${CONV_MS} ms**"
    echo ""
    echo "\`\`\`json"
    echo "$CONV_RESULT" | python3 -m json.tool 2>/dev/null || echo "$CONV_RESULT"
    echo "\`\`\`"
    echo ""
    echo "**Surcoût attendu :** +1 appel LLM (reformulation question) ≈ +2-5 s sur CPU"
    echo ""
  } >> "$REPORT"

  # 4b. Corrective RAG — overhead de grading
  log "Benchmark Corrective RAG (grading batch)..."
  CORR_START=$(date +%s%3N)
  CORR_RESULT=$(curl -sf "${API_BASE}/api/query" \
    -X POST \
    -H "Content-Type: application/json" \
    -d "{\"question\": \"${RAG_QUESTION}\", \"maxContextChunks\": 5}" \
    --max-time 300 2>/dev/null \
    || echo '{"error": "timeout ou API indisponible"}')
  CORR_END=$(date +%s%3N)
  CORR_MS=$((CORR_END - CORR_START))

  {
    echo "### 4b. Corrective RAG — filtrage de pertinence (SPECTRA_CORRECTIVE_RAG_ENABLED=true)"
    echo "> Durée mesurée côté client : **${CORR_MS} ms**"
    echo ""
    CORR_APPLIED=$(echo "$CORR_RESULT" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('correctiveApplied', 'N/A'))" 2>/dev/null || echo "N/A")
    echo "correctiveApplied : **${CORR_APPLIED}**"
    echo ""
    echo "**Surcoût attendu :** +1 appel LLM batch (N chunks en une fois) ≈ +3-8 s selon N"
    echo ""
  } >> "$REPORT"

  # 4c. Adaptive RAG — overhead du classifier
  log "Benchmark Adaptive RAG (classification)..."
  ADAPT_START=$(date +%s%3N)
  ADAPT_RESULT=$(curl -sf "${API_BASE}/api/query" \
    -X POST \
    -H "Content-Type: application/json" \
    -d "{\"question\": \"${RAG_QUESTION}\"}" \
    --max-time 300 2>/dev/null \
    || echo '{"error": "timeout ou API indisponible"}')
  ADAPT_END=$(date +%s%3N)
  ADAPT_MS=$((ADAPT_END - ADAPT_START))

  {
    echo "### 4c. Adaptive RAG — routage par classifier (SPECTRA_ADAPTIVE_RAG_ENABLED=true)"
    echo "> Durée mesurée côté client : **${ADAPT_MS} ms**"
    echo ""
    RAG_STRAT=$(echo "$ADAPT_RESULT" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('ragStrategy', 'N/A'))" 2>/dev/null || echo "N/A")
    echo "ragStrategy : **${RAG_STRAT}**"
    echo ""
    echo "**Surcoût attendu :** +1 appel LLM court (classification) ≈ +1-3 s"
    echo "**Gain potentiel :** -50-80% de latence sur requêtes DIRECT (pas de retrieval)"
    echo ""
  } >> "$REPORT"

  # 4d. Self-RAG — overhead de réflexion
  log "Benchmark Self-RAG (réflexion)..."
  SELF_START=$(date +%s%3N)
  SELF_RESULT=$(curl -sf "${API_BASE}/api/query" \
    -X POST \
    -H "Content-Type: application/json" \
    -d "{\"question\": \"${RAG_QUESTION}\"}" \
    --max-time 300 2>/dev/null \
    || echo '{"error": "timeout ou API indisponible"}')
  SELF_END=$(date +%s%3N)
  SELF_MS=$((SELF_END - SELF_START))

  {
    echo "### 4d. Self-RAG — auto-évaluation (SPECTRA_SELF_RAG_ENABLED=true)"
    echo "> Durée mesurée côté client : **${SELF_MS} ms**"
    echo ""
    SELF_APPLIED=$(echo "$SELF_RESULT" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('selfRagApplied', 'N/A'))" 2>/dev/null || echo "N/A")
    echo "selfRagApplied : **${SELF_APPLIED}**"
    echo ""
    echo "**Surcoût attendu :** +1 appel LLM (évaluation) ≈ +2-5 s"
    echo "**En cas de raffinement :** +1 appel LLM supplémentaire (génération corrigée)"
    echo ""
  } >> "$REPORT"

  {
    echo "### Tableau de comparaison des surcoûts"
    echo ""
    echo "| Stratégie | Variable d'activation | Surcoût LLM | Cas d'usage |"
    echo "|-----------|----------------------|-------------|-------------|"
    echo "| Conversational | SPECTRA_CONVERSATIONAL_RAG_ENABLED | +1 (reformulation) | Chat multi-tours |"
    echo "| Corrective | SPECTRA_CORRECTIVE_RAG_ENABLED | +1 (grading batch) | Index bruité |"
    echo "| Adaptive | SPECTRA_ADAPTIVE_RAG_ENABLED | +1 (classifier) | Workloads mixtes |"
    echo "| Self-RAG | SPECTRA_SELF_RAG_ENABLED | +1 à +2 (réflexion) | Haute fiabilité |"
    echo "| Agentic | SPECTRA_AGENTIC_RAG_ENABLED | +2 à +6 (boucle ReAct) | Questions complexes |"
    echo ""
  } >> "$REPORT"
fi

# ── Résumé ────────────────────────────────────────────────────────────────────

{
  header "5. Résumé et interprétation"
  echo "| Métrique | Valeur | Seuil acceptable |"
  echo "|----------|--------|-----------------|"
  echo "| TG (tokens/s chat) | voir llama-bench | ≥ 5 t/s |"
  echo "| PP (tokens/s prefill) | voir llama-bench | ≥ 50 t/s |"
  echo "| Embedding latence P50 | voir 3a | ≤ 500 ms / chunk |"
  echo "| LLM latence P50 | voir 3b | — (baseline) |"
  echo "| RAG latence P50 | voir 3c | ≤ 60 000 ms |"
  echo "| Conversational RAG | voir 4a | baseline + 2-5 s |"
  echo "| Corrective RAG | voir 4b | baseline + 3-8 s |"
  echo "| Adaptive RAG | voir 4c | baseline + 1-3 s (gain sur DIRECT) |"
  echo "| Self-RAG | voir 4d | baseline + 2-10 s |"
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
