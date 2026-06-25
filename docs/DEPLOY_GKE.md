# Déploiement automatique sur Google Kubernetes Engine (GKE)

Ce document décrit la configuration GCP nécessaire au workflow
[`.github/workflows/deploy-gke.yml`](../.github/workflows/deploy-gke.yml), qui
construit les images Docker, les pousse vers **Artifact Registry** et déploie la
stack complète sur **GKE** à chaque push sur `main` (ou manuellement via
*Run workflow*).

L'authentification utilise **Workload Identity Federation** (OIDC) — aucune clé
de service account n'est stockée dans GitHub.

---

## 1. Vue d'ensemble

```
push main ─▶ GitHub Actions
              ├─ auth OIDC (Workload Identity Federation)
              ├─ docker build/push  spectra-api · spectra-frontend · spectra-llama-cpp
              │     └─▶ Artifact Registry (<region>-docker.pkg.dev/<projet>/<repo>)
              └─ kubectl apply -k k8s/base/  ─▶  cluster GKE (namespace `spectra`)
```

Le workflow déploie les manifests de [`k8s/`](../k8s/). Les modèles GGUF et les
PVC doivent être préparés **une fois** sur le cluster — voir
[`k8s/README.md`](../k8s/README.md) (sections 2 et 5).

---

## 2. Prérequis GCP (à faire une fois)

Remplacez les valeurs entre `< >`. `gcloud` doit pointer sur votre projet.

```bash
export PROJECT_ID="<projet>"
export PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
export REGION="europe-west1"
export REPO="spectra"
export CLUSTER="spectra-cluster"
export SA="deployer"

gcloud config set project "$PROJECT_ID"

# Activer les APIs
gcloud services enable \
  artifactregistry.googleapis.com \
  container.googleapis.com \
  iamcredentials.googleapis.com \
  sts.googleapis.com

# Dépôt Artifact Registry Docker
gcloud artifacts repositories create "$REPO" \
  --repository-format=docker --location="$REGION" \
  --description="Spectra images"
```

### 2.1 Service account de déploiement

```bash
gcloud iam service-accounts create "$SA" \
  --display-name="GitHub Actions deployer"

export SA_EMAIL="${SA}@${PROJECT_ID}.iam.gserviceaccount.com"

# Push vers Artifact Registry + déploiement GKE
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:${SA_EMAIL}" --role="roles/artifactregistry.writer"
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:${SA_EMAIL}" --role="roles/container.developer"
```

### 2.2 Workload Identity Federation (OIDC GitHub → GCP)

```bash
# Pool d'identités
gcloud iam workload-identity-pools create "github" \
  --location="global" --display-name="GitHub Actions"

# Provider OIDC restreint au dépôt
gcloud iam workload-identity-pools providers create-oidc "github" \
  --location="global" --workload-identity-pool="github" \
  --display-name="GitHub OIDC" \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository == 'devdownin/SpectraLLM'"

# Autoriser le dépôt à usurper le service account
gcloud iam service-accounts add-iam-policy-binding "$SA_EMAIL" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github/attribute.repository/devdownin/SpectraLLM"

# Nom complet du provider — à copier dans le secret GCP_WIF_PROVIDER
echo "projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github/providers/github"
```

### 2.3 Cluster GKE

Le workflow déploie sur un cluster **existant** : il faut donc le créer une fois.
Le script [`scripts/gke-create-cluster.sh`](../scripts/gke-create-cluster.sh) s'en
charge (idempotent — sans danger à relancer) : il active les APIs, crée le cluster
puis récupère les credentials `kubectl`.

```bash
export GCP_PROJECT_ID="$PROJECT_ID"
export GKE_CLUSTER="$CLUSTER"          # défaut : spectra-cluster
export GKE_LOCATION="europe-west1-b"   # zone (zonal, moins cher) ou région (régional)

./scripts/gke-create-cluster.sh

# Variante avec node pool GPU T4 (voir §5) :
GKE_ENABLE_GPU=true ./scripts/gke-create-cluster.sh
```

Le node pool par défaut est dimensionné pour l'empreinte des manifests
(`llama-cpp-chat` exige à lui seul 4 vCPU / 8 Gi en QoS Guaranteed, soit ~7,7 vCPU
/ ~15 Gi de *requests* au total) : `e2-standard-8` avec autoscaling 1→3 nœuds.
Toutes les valeurs (machine, autoscaling, zone, GPU) sont surchargeables par
variables d'environnement — voir l'en-tête du script.

---

## 3. Secrets & variables GitHub

Le workflow utilise l'environnement GitHub **`Cloud`** : tout est scopé à cet
environnement (`Settings → Environments → Cloud`), pas au niveau du dépôt.

### Secrets

| Nom | Valeur |
|-----|--------|
| `GCP_WIF_PROVIDER` | `projects/<NUMÉRO>/locations/global/workloadIdentityPools/github/providers/github` |
| `GCP_SERVICE_ACCOUNT` | `deployer@<projet>.iam.gserviceaccount.com` |

### Variables

| Nom | Exemple |
|-----|---------|
| `GCP_PROJECT_ID` | `mon-projet` |
| `GCP_REGION` | `europe-west1` |
| `GAR_REPOSITORY` | `spectra` |
| `GKE_CLUSTER` | `spectra-cluster` |
| `GKE_LOCATION` | `europe-west1` |

> Le workflow lit les secrets via `secrets.*` et les variables via `vars.*`,
> tous deux injectés car le job déclare `environment: Cloud`. Si vous utilisez
> d'autres noms, ajustez le bloc `env:` en tête de
> `.github/workflows/deploy-gke.yml`.

---

## 4. Première exécution

1. Vérifiez que le cluster GKE existe et que les PVC / modèles GGUF sont
   préparés (voir `k8s/README.md`).
2. Déclenchez le workflow : onglet **Actions → Deploy to GKE → Run workflow**,
   ou poussez un commit sur `main`.
3. Suivez le rollout : `kubectl get pods -n spectra -w`.

Les pods `llama-cpp-*` mettent 1–5 min à charger les modèles — c'est normal.

---

## 5. Accélération GPU (optionnel)

L'inférence de chat est nettement plus rapide sur GPU. L'activation repose sur
trois éléments qui doivent être alignés : **image CUDA**, **demande de ressource
GPU**, et **node pool GPU**. Par défaut, le déploiement reste en CPU.

### 5.1 Node pool GPU

```bash
gcloud container node-pools create gpu-pool \
  --cluster="$CLUSTER" --location="$GKE_LOCATION" \
  --machine-type=n1-standard-4 \
  --accelerator=type=nvidia-tesla-t4,count=1,gpu-driver-version=default \
  --num-nodes=1 --min-nodes=0 --max-nodes=1 --enable-autoscaling
```

`gpu-driver-version=default` laisse GKE installer automatiquement les drivers
NVIDIA (pas de DaemonSet manuel). GKE applique le taint
`nvidia.com/gpu=present:NoSchedule` sur ces nœuds ; l'overlay GPU porte la
toleration correspondante.

> 💰 Un nœud T4 coûte sensiblement plus qu'un nœud CPU. L'autoscaling
> `min-nodes=0` permet de retomber à zéro nœud GPU hors charge.

### 5.2 Image CUDA

Construire `spectra-llama-cpp` depuis `Dockerfile.llama.cuda` (variante CUDA) au
lieu de `Dockerfile.llama`. En CI, adapter l'étape *Build & push spectra-llama-cpp*
de `deploy-gke.yml` : `file: Dockerfile.llama.cuda`.

### 5.3 Déployer l'overlay GPU

L'overlay [`k8s/overlays/gpu`](../k8s/overlays/gpu) patche le ConfigMap
(`LLAMA_NGL: "-1"`) et ajoute `nvidia.com/gpu: 1` + la toleration au déploiement
`llama-cpp-chat` :

```bash
kubectl apply -k k8s/overlays/gpu
```

Pour un déploiement automatisé en GPU, remplacer dans `deploy-gke.yml` le
`kubectl apply -k .` (dans `k8s/`) par un apply de l'overlay.

> Par défaut seul le chat passe sur GPU. Pour aussi mettre l'embedding sur GPU
> (2ᵉ GPU requis), décommenter le bloc embed dans
> `k8s/overlays/gpu/patches.yaml`.

Vérifier que le GPU est bien utilisé : les logs de `llama-cpp-chat` doivent
mentionner l'offload des couches (`ngl=-1`) et un device CUDA.

---

## 6. Dépannage

| Symptôme | Cause probable |
|----------|----------------|
| `Permission denied` sur Artifact Registry | rôle `artifactregistry.writer` manquant sur le SA |
| `could not get cluster credentials` | `GKE_CLUSTER` / `GKE_LOCATION` incorrects, ou rôle `container.developer` manquant |
| `unauthorized_client` à l'étape auth | `attribute-condition` du provider ≠ dépôt, ou binding `workloadIdentityUser` absent |
| Pods llama en `CrashLoopBackOff` | fichier GGUF absent du PVC (voir `k8s/README.md` §2) |
| Pod GPU `Pending` (Insufficient nvidia.com/gpu) | node pool GPU absent / à 0 nœud, ou drivers non installés |
| Chat tourne en CPU malgré l'overlay | image construite depuis `Dockerfile.llama` (CPU) au lieu de `Dockerfile.llama.cuda` |
