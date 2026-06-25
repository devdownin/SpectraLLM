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
   préparés — voir `k8s/README.md`, ou utilisez le seeding automatique (§7).
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
| Pods llama en `CrashLoopBackOff` | fichier GGUF absent du PVC (voir §7 seeding) |
| Pod GPU `Pending` (Insufficient nvidia.com/gpu) | node pool GPU absent / à 0 nœud, ou drivers non installés |
| Chat tourne en CPU malgré l'overlay | image construite depuis `Dockerfile.llama` (CPU) au lieu de `Dockerfile.llama.cuda` |
| Job `seed-models` en `Pending` | un pod llama-cpp détient déjà le PVC RWO — lancer le seeding **avant** `kubectl apply -k k8s/base` |
| Certificat managé bloqué en `Provisioning` | DNS du domaine ne résout pas encore vers l'IP de l'Ingress (peut prendre 15–60 min après propagation DNS) |

---

## 7. Seeding automatique des modèles GGUF

Plutôt que la copie manuelle `kubectl cp` (k8s/README §2), un Job télécharge les
modèles directement sur les PVC, côté cluster. **Idempotent** : un modèle déjà
présent n'est pas re-téléchargé.

```bash
./scripts/gke-seed-models.sh         # applique ns + PVC + Job, attend la fin
kubectl apply -k k8s/base            # puis déploie la stack
```

> ⚠️ Les PVC sont `ReadWriteOnce` : le seeding doit tourner **avant** le déploiement
> des pods llama-cpp (sinon ils détiennent déjà les volumes et le Job reste `Pending`).

Modèles par défaut (surchargeables dans `k8s/seed/seed-models.yaml`, ConfigMap
`model-seed-config`) : chat Phi-4-mini → `spectra-fine-tuning-pvc:/merged/model.gguf`,
embedding nomic-embed-text → `spectra-models-pvc:/embed.gguf`.

---

## 8. Ingress HTTPS avec TLS managé (GKE natif)

L'overlay [`k8s/overlays/gke`](../k8s/overlays/gke) remplace l'Ingress nginx et le
LoadBalancer du frontend par un **Ingress GKE natif** avec **certificat TLS managé
par Google** (provision et renouvellement automatiques) :

- `ManagedCertificate` — TLS sans cert-manager.
- `FrontendConfig` — redirection HTTP → HTTPS.
- `BackendConfig` — `timeoutSec: 3600` pour ne pas couper les flux **SSE**
  (le défaut GKE de 30 s tuerait `/api/query/stream`).
- Service frontend en `ClusterIP` + NEG (load balancing container-native).

```bash
# 1. Réserver une IP statique globale (recommandé) et créer le DNS A vers elle
gcloud compute addresses create spectra-ip --global

# 2. Remplacer spectra.example.com par votre domaine dans :
#    k8s/overlays/gke/ingress-gke.yaml  et  managed-certificate.yaml
#    (et décommenter l'annotation global-static-ip-name dans ingress-gke.yaml)

# 3. Déployer
kubectl apply -k k8s/overlays/gke

# 4. Suivre la provision du certificat (15–60 min après que le DNS résout)
kubectl -n spectra describe managedcertificate spectra-cert
```

---

## 9. Observabilité (Prometheus + Grafana)

Les métriques sont déjà exposées sur `/actuator/prometheus` (tag commun
`application=spectrallm`, histogrammes HTTP et RAG). Avec un **Prometheus Operator**
(kube-prometheus-stack), l'overlay [`k8s/monitoring`](../k8s/monitoring) ajoute :

- `ServiceMonitor` — scrape de spectra-api.
- `PrometheusRule` — alertes (API down, taux 5xx, latence RAG p95, heap JVM).
- Dashboard Grafana (importé via le label `grafana_dashboard`).

```bash
# Ajuster le label `release` des ServiceMonitor/PrometheusRule au selector de
# votre Prometheus, puis :
kubectl apply -k k8s/monitoring
```

> **Note — pas d'autoscaling (HPA) de `spectra-api`.** Le backend est *stateful*
> (H2 en fichier, index BM25 en mémoire, PVC `ReadWriteOnce` en écriture,
> `strategy: Recreate`) : il doit rester à **1 réplica**. Un HPA corromprait les
> données. L'autoscaling horizontal nécessiterait d'externaliser l'état (Postgres,
> stockage RWX/objet, index partagé) — hors périmètre actuel. L'autoscaling se fait
> donc au niveau des **nœuds** (node pool GKE, §2.3 / script de création de cluster).
