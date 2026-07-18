# Déploiement Kubernetes — Spectra

## Architecture

```
Internet
   │
   ▼ :80 (LoadBalancer ou Ingress)
spectra-frontend (nginx, ×2)
   │  /api/* → proxifié en interne
   ▼
spectra-api (:8080)
   ├──→ llama-cpp-chat  (:8080)  ← model.gguf  (PVC fine-tuning)
   ├──→ llama-cpp-embed (:8080)  ← embed.gguf  (PVC models)
   ├──→ chromadb        (:8000)               (PVC chromadb)
   └──→ browserless     (:3000)
```

> 🤖 **Déploiement automatisé (GKE)** : un workflow CI construit les images,
> les pousse sur Artifact Registry et applique ces manifests à chaque push sur
> `main`. Voir la section GKE de [Getting Started](../../docs/getting-started.en.md#5-deploy-to-kubernetes--gke-optional).

Tous les services sont en `ClusterIP` — seul le frontend est exposé.  
Le proxy nginx du frontend (`/api/ → spectra-api:8080`) fonctionne sans modification car les noms de services K8s dans le même namespace sont résolus directement.

---

## Prérequis

- Cluster Kubernetes ≥ 1.26 (minikube, kind, EKS, AKS, GKE…)
- `kubectl` configuré sur le cluster cible
- `kustomize` ≥ 4.x (ou `kubectl apply -k` qui l'intègre)
- Images Docker construites et accessibles depuis le cluster

---

## 1. Construire et rendre les images disponibles

```bash
# Construire les 3 images (depuis la racine du repo)
docker build -f Dockerfile.llama -t spectra-llama-cpp:latest .
docker build -t spectra-api:latest .
docker build -t spectra-frontend:latest ./frontend

# ── Minikube ──────────────────────────────────────────────────────────────
# Option A — charger les images directement dans minikube (pas de registry)
minikube image load spectra-llama-cpp:latest
minikube image load spectra-api:latest
minikube image load spectra-frontend:latest

# ── Cluster distant / CI ──────────────────────────────────────────────────
# Option B — pousser vers un registry (adapter les noms dans kustomization.yaml)
docker tag spectra-api:latest registry.example.com/spectra/api:1.2.0
docker push registry.example.com/spectra/api:1.2.0
```

---

## 2. Préparer les données

Les PVCs seront créés vides. Vous devez y placer les modèles GGUF **avant** de démarrer les pods llama-cpp.

### Option A — seeding automatique (recommandé)

Un Job télécharge les modèles directement sur les PVC (idempotent, pas de copie locale) :

```bash
./scripts/gke-seed-models.sh     # applique ns + PVC + Job, attend la fin
```

URLs/chemins surchargeables dans `k8s/seed/seed-models.yaml` (ConfigMap `model-seed-config`).

### Option B — copie manuelle

```bash
# Créer les PVCs d'abord
kubectl apply -f k8s/base/00-namespace.yaml -f k8s/base/02-pvc.yaml

# Copier embed.gguf via un pod temporaire
kubectl run -n spectra copy-models --image=busybox --restart=Never \
  --overrides='{"spec":{"volumes":[{"name":"m","persistentVolumeClaim":{"claimName":"spectra-models-pvc"}}],"containers":[{"name":"c","image":"busybox","command":["sleep","3600"],"volumeMounts":[{"name":"m","mountPath":"/data"}]}]}}' \
  -- sleep 3600

# Le modèle de chat vit désormais dans le volume des modèles (servi par le superviseur
# llm-chat via le pointeur du modèle actif). Le nom de fichier doit correspondre à
# SPECTRA_LLM_CHAT_FILE / llama-chat-config.LLM_CHAT_MODEL_FILE.
kubectl cp data/models/embed.gguf spectra/copy-models:/data/embed.gguf
kubectl cp data/models/Phi-4-mini-reasoning-UD-IQ1_S.gguf spectra/copy-models:/data/Phi-4-mini-reasoning-UD-IQ1_S.gguf
kubectl delete pod -n spectra copy-models
```

---

## 3. Déployer

```bash
# Déploiement complet via kustomize
kubectl apply -k k8s/base/

# Vérifier la progression
kubectl get pods -n spectra -w

# Les pods llama-cpp prennent 1–5 min pour charger les modèles (normal)
# Attendre que tous les pods soient Running/Ready
```

### Variantes (overlays kustomize)

| Cible | Effet |
|-------|-------|
| `kubectl apply -k k8s/overlays/gpu` | Accélération GPU NVIDIA (image CUDA + `nvidia.com/gpu`) — voir §5 |
| `kubectl apply -k k8s/overlays/gke` | Ingress GKE natif + **TLS managé** Google (HTTP→HTTPS, timeouts SSE) |
| `kubectl apply -k k8s/monitoring` | **Observabilité** : `ServiceMonitor` + alertes Prometheus + dashboard Grafana |

Les overlays incluent la base ; appliquer **soit** `k8s/base`, **soit** un overlay (qui le contient), pas les deux.

---

## 4. Accéder à l'application

```bash
# ── Minikube ──────────────────────────────────────────────────────────────
minikube service spectra-frontend -n spectra

# ── Cluster cloud (LoadBalancer) ──────────────────────────────────────────
kubectl get svc -n spectra spectra-frontend
# → EXTERNAL-IP : l'IP assignée par le cloud provider

# ── Avec Ingress ──────────────────────────────────────────────────────────
# 1. Décommenter 09-ingress.yaml dans kustomization.yaml
# 2. Remplacer spectra.example.com par votre domaine
# 3. Passer spectra-frontend en ClusterIP dans 08-spectra-frontend.yaml
kubectl apply -k k8s/base/

# ── Port-forward (test local sans LoadBalancer) ───────────────────────────
kubectl port-forward -n spectra svc/spectra-frontend 8888:80
# → http://localhost:8888
```

---

## 5. Activation GPU NVIDIA

```bash
# 1. Installer le device plugin NVIDIA
kubectl apply -f https://raw.githubusercontent.com/NVIDIA/k8s-device-plugin/v0.14.5/nvidia-device-plugin.yml

# 2. Dans 06-llama-chat.yaml (et/ou 05-llama-embed.yaml), décommenter :
#      limits:
#        nvidia.com/gpu: "1"
#    et régler l'offload GPU dans le ConfigMap :
#      - chat  (superviseur)     : LLM_CHAT_EXTRA_ARGS: "--n-gpu-layers 999"
#      - embed (llama-autostart) : LLAMA_NGL: "-1"
#    (ou plus simplement : kubectl apply -k k8s/overlays/gpu)

# 3. Redéployer
kubectl apply -k k8s/base/
```

---

## 6. Commandes utiles

```bash
# État des pods
kubectl get pods -n spectra

# Logs d'un service
kubectl logs -n spectra -l app=spectra-api --tail=50
kubectl logs -n spectra -l app=llama-cpp-chat --tail=30

# Redémarrer un déploiement (ex: après mise à jour du modèle)
kubectl rollout restart deployment/llama-cpp-chat -n spectra

# Mettre à jour une image
kubectl set image deployment/spectra-api spectra-api=registry.example.com/spectra/api:1.3.0 -n spectra

# Accéder à l'API directement (debug)
kubectl port-forward -n spectra svc/spectra-api 8080:8080
curl http://localhost:8080/api/status

# Supprimer complètement (conserve les PVCs = données préservées)
kubectl delete -k k8s/base/

# Supprimer tout y compris les données
kubectl delete namespace spectra
```

---

## Différences avec docker-compose

| Aspect | docker-compose | Kubernetes |
|--------|---------------|-----------|
| CPU pinning | `cpuset: "0-3"` | Resource `requests/limits` |
| Dépendances | `depends_on` + `condition` | Init containers |
| Variables | `.env` file | ConfigMap + Secret |
| Volumes | Bind mounts `./data/...` | PersistentVolumeClaims |
| Réseau | Bridge `spectra-net` | Namespace + ClusterIP |
| Exposition | `ports: 80:80` | LoadBalancer ou Ingress |
| Scaling | Manuel (`--scale`) | `replicas:` dans Deployment |
| Script llama | Monté via volume | Intégré dans l'image (Dockerfile) |
