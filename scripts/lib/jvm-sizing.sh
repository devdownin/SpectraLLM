#!/usr/bin/env bash
# Dimensionnement du heap JVM de spectra-api.
#
# Extrait de detect-env.sh pour être testable isolément (scripts/tests/test_jvm_sizing.py).
#
# LE POINT ESSENTIEL : le heap doit tenir DANS la limite mémoire du conteneur
# (SPECTRA_API_MEMORY_LIMIT, 4g par défaut dans docker-compose.yml), pas seulement dans la RAM
# de la machine. L'ancien calcul (RAM/4, borné à 2–4 Go) donnait -Xmx3868m sur une machine
# 16 Go, pour un conteneur de 4 Go. Or la JVM consomme hors heap : métaspace, piles, tampons
# directs, et surtout la mémoire native du reranker ONNX ; ZGC compte en outre son heap en
# mémoire partagée. Le conteneur dépassait sa limite et le noyau tuait la JVM (OOM kill du
# cgroup) — l'API redémarrait en pleine requête, sans autre trace qu'une connexion fermée.
#
# Usage (en bibliothèque) :  source scripts/lib/jvm-sizing.sh ; jvm_heap 16384 4 4g
# Usage (en ligne de commande) :  scripts/lib/jvm-sizing.sh <ram_mo> <diviseur> [limite]
# Sortie : "HEAP=<mo> LIMIT=<mo>"

# Part maximale de la limite du conteneur accordée au heap : le reste couvre le hors-heap.
JVM_HEAP_LIMIT_PERCENT=60

# Convertit une limite Compose ("4g", "4096m", "4gb", "512M") en Mo. Vide → défaut Compose.
memory_limit_mb() {
  local raw="${1:-4g}"
  raw="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  raw="${raw%b}"
  case "$raw" in
    *g) echo $(( ${raw%g} * 1024 )) ;;
    *m) echo "${raw%m}" ;;
    *k) echo $(( ${raw%k} / 1024 )) ;;
    *[!0-9]*|'') return 1 ;;
    *) echo $(( raw / 1024 / 1024 )) ;;
  esac
}

# Renseigne JVM_HEAP (Mo) et JVM_LIMIT_MB.
jvm_heap() {
  local total_ram_mb="$1" divisor="$2" limit="${3:-}"
  local cap

  JVM_LIMIT_MB="$(memory_limit_mb "$limit")" || {
    echo "limite mémoire illisible : '$limit' (attendu : 4g, 4096m…)" >&2
    return 1
  }
  JVM_HEAP=$(( total_ram_mb / divisor ))
  if [ "$JVM_HEAP" -lt 2048 ]; then JVM_HEAP=2048; fi
  if [ "$JVM_HEAP" -gt 4096 ]; then JVM_HEAP=4096; fi

  # Plafond final : il l'emporte sur le plancher de 2 Go, qu'un conteneur plus petit ne peut
  # pas honorer sans OOM kill.
  cap=$(( JVM_LIMIT_MB * JVM_HEAP_LIMIT_PERCENT / 100 ))
  if [ "$JVM_HEAP" -gt "$cap" ]; then JVM_HEAP="$cap"; fi
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "usage: $0 <ram_mo> <diviseur> [limite_conteneur]" >&2
    exit 2
  fi
  jvm_heap "$1" "$2" "${3:-}" || exit 1
  echo "HEAP=$JVM_HEAP LIMIT=$JVM_LIMIT_MB"
fi
