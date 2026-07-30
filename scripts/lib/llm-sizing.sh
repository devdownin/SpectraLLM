#!/usr/bin/env bash
# Dimensionnement du serveur d'inférence : fenêtre de contexte et parallélisme.
#
# Extrait de detect-env.sh pour être testable isolément (scripts/tests/test_llm_sizing.py).
# Ce calcul avait été faux sans que rien ne puisse le révéler : c'est de l'arithmétique pure,
# dont l'erreur ne se manifestait qu'en production, sous la forme de réponses tronquées.
#
# LE POINT ESSENTIEL : LLM_CONTEXT est le contexte TOTAL du serveur, réparti entre les slots
# parallèles. Une requête ne voit que LLM_CONTEXT / LLM_PARALLEL tokens. L'ancien calcul
# fixait le total selon la RAM tout en faisant croître le parallélisme avec les cœurs — donc
# plus la machine avait de cœurs, plus la fenêtre par requête rétrécissait (512 tokens sur une
# machine 32 Go / 16 cœurs, quand le RAG agentique en budgète 3000 à lui seul).
#
# On dimensionne donc la fenêtre PAR REQUÊTE — la seule qui compte fonctionnellement — puis on
# en déduit le total, en plafonnant le parallélisme pour tenir l'enveloppe mémoire. Sur du CPU,
# deux conversations à 4096 tokens valent mieux que huit à 512.
#
# Usage (en bibliothèque) :  source scripts/lib/llm-sizing.sh ; llm_sizing 16384 8
# Usage (en ligne de commande) :  scripts/lib/llm-sizing.sh <ram_mo> <cœurs>
# Sortie : "PARALLEL=<n> CONTEXT=<total> SLOT=<par-requête>"

# Fenêtre visée par requête et enveloppe totale, selon la RAM disponible.
# Renseigne les variables LLM_PARALLEL, LLM_CONTEXT et LLM_SLOT_CONTEXT.
llm_sizing() {
  local total_ram_mb="$1"
  local cpu_cores="$2"
  local slot max_total max_slots

  if [ "$total_ram_mb" -ge 32768 ]; then
    slot=8192;  max_total=32768
  elif [ "$total_ram_mb" -ge 16384 ]; then
    slot=4096;  max_total=16384
  elif [ "$total_ram_mb" -ge 8192 ]; then
    slot=2048;  max_total=4096
  else
    slot=1024;  max_total=2048
  fi

  # Parallélisme : la moitié des cœurs, borné à 8, puis plafonné par l'enveloppe totale
  # pour que la fenêtre par requête ne soit jamais rognée.
  LLM_PARALLEL=$(( cpu_cores / 2 ))
  if [ "$LLM_PARALLEL" -lt 1 ]; then LLM_PARALLEL=1; fi
  if [ "$LLM_PARALLEL" -gt 8 ]; then LLM_PARALLEL=8; fi

  max_slots=$(( max_total / slot ))
  if [ "$max_slots" -lt 1 ]; then max_slots=1; fi
  if [ "$LLM_PARALLEL" -gt "$max_slots" ]; then LLM_PARALLEL="$max_slots"; fi

  LLM_SLOT_CONTEXT="$slot"
  LLM_CONTEXT=$(( slot * LLM_PARALLEL ))
}

# Exécution directe : sert de point d'entrée aux tests.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  if [ "$#" -ne 2 ]; then
    echo "usage: $0 <ram_mo> <coeurs>" >&2
    exit 2
  fi
  llm_sizing "$1" "$2"
  echo "PARALLEL=$LLM_PARALLEL CONTEXT=$LLM_CONTEXT SLOT=$LLM_SLOT_CONTEXT"
fi
