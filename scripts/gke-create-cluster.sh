#!/usr/bin/env bash
#
# gke-create-cluster.sh — Crée (de façon idempotente) le cluster GKE qui héberge
# SpectraLLM, puis récupère les credentials kubectl.
#
# C'est la pièce manquante de docs/DEPLOY_GKE.md : le guide configure les APIs,
# Artifact Registry, le service account et Workload Identity Federation, mais
# suppose que le cluster « existe déjà ». Ce script le crée.
#
# Le script est SÛR à relancer : si le cluster (ou le node pool GPU) existe déjà,
# il est laissé en l'état.
#
# ─────────────────────────────────────────────────────────────────────────────
# Utilisation
#   export GCP_PROJECT_ID="mon-projet"        # OBLIGATOIRE
#   ./scripts/gke-create-cluster.sh           # cluster CPU
#   GKE_ENABLE_GPU=true ./scripts/gke-create-cluster.sh   # + node pool GPU T4
#
# Toutes les variables ci-dessous sont surchargeables par l'environnement. Les
# noms reprennent ceux de docs/DEPLOY_GKE.md et de .github/workflows/deploy-gke.yml
# (GKE_CLUSTER, GKE_LOCATION) pour rester cohérents avec le déploiement CI.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# --- Paramètres généraux -----------------------------------------------------
PROJECT_ID="${GCP_PROJECT_ID:-${PROJECT_ID:-}}"
# GKE_LOCATION accepte une ZONE (ex. europe-west1-b → cluster zonal, moins cher)
# ou une RÉGION (ex. europe-west1 → cluster régional, nœuds répliqués par zone).
LOCATION="${GKE_LOCATION:-${GCP_REGION:-europe-west1-b}}"
CLUSTER="${GKE_CLUSTER:-spectra-cluster}"
RELEASE_CHANNEL="${GKE_RELEASE_CHANNEL:-regular}"

# --- Node pool CPU par défaut ------------------------------------------------
# Empreinte des manifests k8s/base (requests, QoS Guaranteed pour chat/embed/api) :
#   llama-chat 4 vCPU / 8 Gi · llama-embed 2 vCPU / 4 Gi · spectra-api 1 vCPU / 2 Gi
#   + chromadb / browserless / frontend  ≈  total ~7,7 vCPU / ~15 Gi de requests.
# Le pod llama-chat exige à lui seul 4 vCPU schedulables : la machine doit donc
# avoir > 4 vCPU allouables. e2-standard-8 (8 vCPU / 32 Gi) + autoscaling couvre.
MACHINE_TYPE="${GKE_MACHINE_TYPE:-e2-standard-8}"
DISK_SIZE="${GKE_DISK_SIZE:-50}"
NUM_NODES="${GKE_NUM_NODES:-1}"
MIN_NODES="${GKE_MIN_NODES:-1}"
MAX_NODES="${GKE_MAX_NODES:-3}"

# --- Node pool GPU (optionnel) -----------------------------------------------
ENABLE_GPU="${GKE_ENABLE_GPU:-false}"
GPU_POOL="${GKE_GPU_POOL:-gpu-pool}"
GPU_MACHINE_TYPE="${GKE_GPU_MACHINE_TYPE:-n1-standard-4}"
GPU_TYPE="${GKE_GPU_TYPE:-nvidia-tesla-t4}"
GPU_COUNT="${GKE_GPU_COUNT:-1}"
GPU_MAX_NODES="${GKE_GPU_MAX_NODES:-1}"

# --- Helpers -----------------------------------------------------------------
log()  { printf '\033[1;34m▶ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

command -v gcloud >/dev/null 2>&1 || die "gcloud introuvable — installez le Google Cloud SDK."
[ -n "$PROJECT_ID" ] || die "GCP_PROJECT_ID (ou PROJECT_ID) doit être défini."

log "Projet     : $PROJECT_ID"
log "Cluster    : $CLUSTER"
log "Location   : $LOCATION"
log "Machine    : $MACHINE_TYPE  (autoscaling $MIN_NODES→$MAX_NODES)"
[ "$ENABLE_GPU" = "true" ] && log "GPU        : $GPU_POOL  ($GPU_COUNT × $GPU_TYPE sur $GPU_MACHINE_TYPE, 0→$GPU_MAX_NODES)"

gcloud config set project "$PROJECT_ID" >/dev/null

# --- 1. APIs requises --------------------------------------------------------
log "Activation des APIs (container, artifactregistry, iamcredentials, sts)…"
gcloud services enable \
  container.googleapis.com \
  artifactregistry.googleapis.com \
  iamcredentials.googleapis.com \
  sts.googleapis.com
ok "APIs actives."

# --- 2. Cluster GKE ----------------------------------------------------------
if gcloud container clusters describe "$CLUSTER" --location "$LOCATION" >/dev/null 2>&1; then
  warn "Cluster '$CLUSTER' déjà présent dans '$LOCATION' — création ignorée."
else
  log "Création du cluster GKE '$CLUSTER'… (5–8 min)"
  gcloud container clusters create "$CLUSTER" \
    --location "$LOCATION" \
    --release-channel "$RELEASE_CHANNEL" \
    --machine-type "$MACHINE_TYPE" \
    --disk-size "$DISK_SIZE" \
    --num-nodes "$NUM_NODES" \
    --enable-autoscaling --min-nodes "$MIN_NODES" --max-nodes "$MAX_NODES" \
    --enable-ip-alias \
    --enable-autorepair --enable-autoupgrade \
    --workload-pool "${PROJECT_ID}.svc.id.goog"
  ok "Cluster créé."
fi

# --- 3. Node pool GPU (optionnel) --------------------------------------------
if [ "$ENABLE_GPU" = "true" ]; then
  if gcloud container node-pools describe "$GPU_POOL" \
       --cluster "$CLUSTER" --location "$LOCATION" >/dev/null 2>&1; then
    warn "Node pool GPU '$GPU_POOL' déjà présent — création ignorée."
  else
    log "Création du node pool GPU '$GPU_POOL'…"
    # gpu-driver-version=default : GKE installe les drivers NVIDIA automatiquement.
    # GKE taint ces nœuds nvidia.com/gpu=present:NoSchedule ; l'overlay GPU
    # (k8s/overlays/gpu) porte la toleration correspondante.
    gcloud container node-pools create "$GPU_POOL" \
      --cluster "$CLUSTER" --location "$LOCATION" \
      --machine-type "$GPU_MACHINE_TYPE" \
      --accelerator "type=${GPU_TYPE},count=${GPU_COUNT},gpu-driver-version=default" \
      --num-nodes 1 --enable-autoscaling --min-nodes 0 --max-nodes "$GPU_MAX_NODES"
    ok "Node pool GPU créé (autoscaling à partir de 0 nœud — pas de coût hors charge)."
  fi
fi

# --- 4. Credentials kubectl --------------------------------------------------
log "Récupération des credentials kubectl…"
gcloud container clusters get-credentials "$CLUSTER" --location "$LOCATION"
ok "kubectl pointe désormais sur '$CLUSTER'."

# --- Récapitulatif -----------------------------------------------------------
cat <<EOF

──────────────────────────────────────────────────────────────────────────────
 Cluster prêt.

 Variables GitHub (Settings → Environments → Cloud) à aligner sur ce cluster :
   GKE_CLUSTER  = $CLUSTER
   GKE_LOCATION = $LOCATION

 Étapes suivantes :
   1. Préparer les PVC et copier les modèles GGUF      → voir k8s/README.md (§2, §5)
   2. Déployer la stack :
        kubectl apply -k k8s/base            # CPU
        kubectl apply -k k8s/overlays/gpu    # GPU (si GKE_ENABLE_GPU=true)
      …ou laisser le workflow .github/workflows/deploy-gke.yml le faire sur push main.
   3. Suivre le rollout :  kubectl get pods -n spectra -w
──────────────────────────────────────────────────────────────────────────────
EOF
