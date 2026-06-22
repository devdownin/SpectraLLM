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
              └─ kubectl apply -k k8s/  ─▶  cluster GKE (namespace `spectra`)
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

`Settings → Secrets and variables → Actions`.

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

> Le workflow lit les secrets via `secrets.*` et la configuration non sensible
> via `vars.*`. Si vous avez créé vos secrets sous d'autres noms, ajustez le
> bloc `env:` en tête de `.github/workflows/deploy-gke.yml`.

---

## 4. Première exécution

1. Vérifiez que le cluster GKE existe et que les PVC / modèles GGUF sont
   préparés (voir `k8s/README.md`).
2. Déclenchez le workflow : onglet **Actions → Deploy to GKE → Run workflow**,
   ou poussez un commit sur `main`.
3. Suivez le rollout : `kubectl get pods -n spectra -w`.

Les pods `llama-cpp-*` mettent 1–5 min à charger les modèles — c'est normal.

---

## 5. Dépannage

| Symptôme | Cause probable |
|----------|----------------|
| `Permission denied` sur Artifact Registry | rôle `artifactregistry.writer` manquant sur le SA |
| `could not get cluster credentials` | `GKE_CLUSTER` / `GKE_LOCATION` incorrects, ou rôle `container.developer` manquant |
| `unauthorized_client` à l'étape auth | `attribute-condition` du provider ≠ dépôt, ou binding `workloadIdentityUser` absent |
| Pods llama en `CrashLoopBackOff` | fichier GGUF absent du PVC (voir `k8s/README.md` §2) |
