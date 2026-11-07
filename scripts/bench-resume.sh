#!/usr/bin/env bash
# Reprend une commande du banc EnterpriseRAG sur un environnement qui redémarre sans prévenir.
#
#   scripts/bench-resume.sh ingest
#   scripts/bench-resume.sh retrieve --hybrid on --output data/enterprise-rag-bench/retrieval.jsonl
#
# Idempotent : relancer la même commande après chaque redémarrage suffit, car `ingest`, `answer` et
# `retrieve` reprennent là où elles s'étaient arrêtées. Le script :
#   1. relance le démon Docker s'il est arrêté, après avoir supprimé les fichiers pid périmés
#      (un numéro de processus réutilisé après un redémarrage fait échouer dockerd avec
#      « failed to save containerd pid to disk: process with PID N is still running ») ;
#   2. relance la pile (`docker compose up -d` ne recrée rien si rien n'a changé) ;
#   3. passe la main au harnais, qui attend lui-même /actuator/health/readiness, donc le rechargement
#      de l'index BM25, avant d'envoyer la moindre requête.
#
# Variables : BENCH_COMPOSE (commande compose, `none` pour ne pas toucher à la pile), BENCH_DOCKER_WAIT
# (secondes d'attente du démon, 60 par défaut).
set -euo pipefail
cd "$(dirname "$0")/.."

DOCKER="${BENCH_DOCKER:-docker}"
DOCKERD="${BENCH_DOCKERD:-dockerd}"
COMPOSE="${BENCH_COMPOSE:-$DOCKER compose --project-directory . -f deploy/docker/docker-compose.yml}"
WAIT="${BENCH_DOCKER_WAIT:-60}"
PID_FILES="${BENCH_PID_FILES:-/var/run/docker.pid /run/docker.pid /run/docker/docker.pid /run/docker/containerd/containerd.pid /var/run/docker/containerd/containerd.pid}"

if ! "$DOCKER" info >/dev/null 2>&1; then
  if command -v "$DOCKERD" >/dev/null 2>&1; then
    echo "[bench-resume] démon Docker arrêté : nettoyage des fichiers pid, redémarrage" >&2
    # shellcheck disable=SC2086
    rm -f $PID_FILES
    pkill -x containerd 2>/dev/null || true
    nohup "$DOCKERD" >/tmp/dockerd.log 2>&1 &
  fi
  for _ in $(seq 1 "$WAIT"); do
    "$DOCKER" info >/dev/null 2>&1 && break
    sleep 1
  done
  if ! "$DOCKER" info >/dev/null 2>&1; then
    echo "[bench-resume] le démon Docker ne répond pas après ${WAIT} s (voir /tmp/dockerd.log)" >&2
    exit 1
  fi
fi

if [ "$COMPOSE" != "none" ]; then
  # shellcheck disable=SC2086
  $COMPOSE up -d
fi

exec python3 scripts/enterprise_rag_bench.py "$@"
