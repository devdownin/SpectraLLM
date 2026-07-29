#!/usr/bin/env bash
# ────────────────────────────────────────────────────────
# Script d'entraînement QLoRA pour Spectra (appelé par FineTuningService).
#
# Arguments positionnels (fournis par FineTuningService.runTrainingProcess) :
#   $1 = chemin du dataset JSONL
#   $2 = chemin de sortie de l'adaptateur (répertoire ; un suffixe .gguf est toléré)
#   $3 = modèle de base (phi3, tinyllama, mistral, llama3…)
#   $4 = LoRA rank
#   $5 = LoRA alpha
#   $6 = nombre d'epochs
#   $7 = learning rate
#   $8  = packing ("true"/"false")  — optionnel, défaut false
#   $9  = dpo     ("true"/"false")  — optionnel, défaut false
#   $10 = orpo    ("true"/"false")  — optionnel, défaut false
#
# Ce script est un simple adaptateur : il délègue à train_host.py, qui gère à la fois
# le backend GPU (Unsloth) et CPU (HuggingFace PEFT), le masquage du prompt, le packing
# et le DPO. On évite ainsi la divergence avec le pipeline CLI (un seul moteur d'entraînement).
# ────────────────────────────────────────────────────────

set -euo pipefail

DATASET_PATH="$1"
OUTPUT_PATH="$2"
BASE_MODEL="$3"
LORA_RANK="$4"
LORA_ALPHA="$5"
EPOCHS="$6"
LEARNING_RATE="$7"
PACKING="${8:-false}"
DPO="${9:-false}"
ORPO="${10:-false}"

# train_host.py écrit un répertoire d'adaptateur ; on tolère un chemin ".gguf" hérité.
OUTPUT_DIR="${OUTPUT_PATH%.gguf}"

# Localiser train_host.py à côté de ce script, quel que soit le répertoire de travail.
# (FineTuningService passe un chemin absolu pour ce script, donc BASH_SOURCE est absolu.)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Spectra Fine-Tuning ==="
echo "Dataset:       $DATASET_PATH"
echo "Output:        $OUTPUT_DIR"
echo "Base model:    $BASE_MODEL"
echo "LoRA rank:     $LORA_RANK"
echo "LoRA alpha:    $LORA_ALPHA"
echo "Epochs:        $EPOCHS"
echo "Learning rate: $LEARNING_RATE"
echo "Packing:       $PACKING"
echo "DPO:           $DPO"
echo "ORPO:          $ORPO"

EXTRA_ARGS=()
[ "$PACKING" = "true" ] && EXTRA_ARGS+=(--packing)
[ "$DPO" = "true" ]     && EXTRA_ARGS+=(--dpo)
[ "$ORPO" = "true" ]    && EXTRA_ARGS+=(--orpo)
# Longueur de séquence surchargeable (sinon défaut 512 côté train_host.py).
[ -n "${SPECTRA_TRAIN_MAX_LENGTH:-}" ] && EXTRA_ARGS+=(--max-length "$SPECTRA_TRAIN_MAX_LENGTH")

PYTHON_BIN="${PYTHON_BIN:-python3}"

exec "$PYTHON_BIN" "$SCRIPT_DIR/train_host.py" \
  --dataset    "$DATASET_PATH" \
  --output     "$OUTPUT_DIR" \
  --base-model "$BASE_MODEL" \
  --lora-rank  "$LORA_RANK" \
  --lora-alpha "$LORA_ALPHA" \
  --epochs     "$EPOCHS" \
  --lr         "$LEARNING_RATE" \
  ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}
