#!/usr/bin/env bash
#
# gke-seed-models.sh — Télécharge les modèles GGUF sur les PVC du cluster, AVANT
# de déployer la stack. Remplace la copie manuelle `kubectl cp` (k8s/README §2).
#
# Idempotent : relançable sans danger ; un modèle déjà présent n'est pas
# re-téléchargé. Les PVC étant ReadWriteOnce, ce script doit tourner avant
# `kubectl apply -k k8s/base` (sinon les pods llama-cpp détiennent déjà les
# volumes et le Job reste en Pending).
#
# Usage :
#   ./scripts/gke-seed-models.sh
#   # puis :  kubectl apply -k k8s/base
#
# Surcharge des URLs/chemins : éditer k8s/seed/seed-models.yaml (ConfigMap
# model-seed-config) ou patcher après apply.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

NAMESPACE="${NAMESPACE:-spectra}"
TIMEOUT="${SEED_TIMEOUT:-1800s}"   # 30 min — laisse le temps aux gros GGUF

command -v kubectl >/dev/null 2>&1 || { echo "✗ kubectl introuvable." >&2; exit 1; }

log() { printf '\033[1;34m▶ %s\033[0m\n' "$*"; }

log "Application namespace + PVC + Job de seeding…"
kubectl apply -k k8s/seed

# Un Job ne peut pas être ré-appliqué s'il a changé ; en cas de relance, on
# repart d'un Job propre.
kubectl -n "$NAMESPACE" delete job seed-models --ignore-not-found >/dev/null 2>&1 || true
kubectl apply -k k8s/seed

log "Attente du téléchargement (timeout $TIMEOUT)…"
# Suivre les logs en tâche de fond pour la visibilité.
( kubectl -n "$NAMESPACE" wait --for=condition=ready pod -l app=seed-models --timeout=120s >/dev/null 2>&1 \
  && kubectl -n "$NAMESPACE" logs -f -l app=seed-models ) || true

if kubectl -n "$NAMESPACE" wait --for=condition=complete job/seed-models --timeout="$TIMEOUT"; then
  echo "✓ Modèles seedés. Déployez la stack :  kubectl apply -k k8s/base"
else
  echo "✗ Le Job de seeding n'a pas abouti. Logs :" >&2
  kubectl -n "$NAMESPACE" logs -l app=seed-models --tail=50 >&2 || true
  exit 1
fi
