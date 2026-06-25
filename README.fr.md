# Spectra — Domain LLM Builder 🚀

[![Java CI with Maven](https://github.com/devdownin/SpectraLLM/actions/workflows/ci.yml/badge.svg)](https://github.com/devdownin/SpectraLLM/actions/workflows/ci.yml)
[![CodeQL](https://github.com/devdownin/SpectraLLM/actions/workflows/codeql.yml/badge.svg)](https://github.com/devdownin/SpectraLLM/actions/workflows/codeql.yml)
[![Dependency Check](https://github.com/devdownin/SpectraLLM/actions/workflows/dependency-scan.yml/badge.svg)](https://github.com/devdownin/SpectraLLM/actions/workflows/dependency-scan.yml)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![React 19](https://img.shields.io/badge/React-19-61DAFB.svg)](https://react.dev/)

🌍 **Langues :** [English](README.md) · Français

> **Transformez vos documents métier bruts en LLMs spécialisés, prêts pour la production. 100 % local, souverain et respectueux de la vie privée.**

Spectra est une plateforme complète conçue pour construire, optimiser et déployer des modèles de langage (LLM) spécialisés à partir de vos propres données. De l'ingestion de PDF au fine-tuning QLoRA, en passant par le déploiement GGUF, Spectra gère l'intégralité du cycle de spécialisation des modèles dans une seule stack simplifiée.

---

## 🏗️ Le Pipeline de Spécialisation Complet

Spectra n'est pas seulement un outil RAG ; c'est une **usine à intelligence spécialisée**.

```mermaid
graph LR
    A[Docs Bruts] --> B[Ingestion Intelligente]
    B --> C[Base Vectorielle]
    C --> D[RAG Avancé]
    D --> E[Génération Dataset]
    E --> F[Fine-Tuning]
    F --> G[LLM Spécialisé]
```

1.  **Ingestion** : PDF (Layout-aware), DOCX, HTML, URLs et ZIPs.
2.  **Traitement** : Chunking sémantique, nettoyage de texte en 8 étapes et embeddings Nomic.
3.  **Recherche** : Recherche hybride BM25 + Vecteurs avec re-ranking Cross-Encoder.
4.  **Enrichissement** : RAG Agentique (boucle ReAct) pour un raisonnement profond et des requêtes multi-étapes.
5.  **Synthèse** : Génération automatique de datasets Q&A, DPO et résumés.
6.  **Spécialisation** : Fine-tuning QLoRA (Unsloth) pour injecter vos connaissances dans le modèle.
7.  **Déploiement** : Export au format GGUF et service via une API compatible OpenAI.

---

## 📚 Documentation Pédagogique
Pour comprendre les idées et les algorithmes de Spectra (embeddings, HNSW, BM25 + RRF, les 6 stratégies de RAG, DPO/QLoRA, résilience, déploiement…), chacun illustré d'un exemple d'usage concret, consultez notre **[Mini-Livre Pédagogique](./DOCUMENTATION_PEDAGOGIQUE.fr.md)**.

---

## ✨ Pourquoi Spectra ? (Originalité & Innovation)

### 🧩 Workflow Unifié "Tout-en-un"
La plupart des outils se concentrent sur un seul maillon de la chaîne (uniquement le RAG ou uniquement le fine-tuning). Spectra unifie le **cycle complet**, des données brutes au modèle fine-tuné. Plus besoin de jongler entre cinq outils différents ; tout est dans un seul Docker Compose.

### 🔍 RAG Avancé : Hybride & Agentique
*   **Recherche Hybride** : Combine la précision des **mots-clés BM25** avec la profondeur sémantique des **Vecteurs**, fusionnés via Reciprocal Rank Fusion (RRF).
*   **Re-ranking Cross-Encoder** : Utilise un modèle spécialisé pour réévaluer les meilleurs candidats, garantissant que seul le contexte le plus pertinent atteint le LLM.
*   **RAG Agentique (ReAct)** : Le LLM peut "réfléchir" et effectuer plusieurs itérations de recherche (PENSÉE -> ACTION -> RECHERCHE) pour répondre à des requêtes complexes impossibles à résoudre en une seule passe.

### 📄 Ingestion Respectueuse de la Mise en Page (Layout-Aware)
Spectra comprend que les documents ne sont pas que des suites de texte. En utilisant **PyMuPDF4LLM** et **IBM Docling**, il préserve la structure des tableaux, des en-têtes et des listes, convertissant les PDF complexes en Markdown propre pour une meilleure récupération.

### 🛡️ 100 % Local & Souverain
Exécutez tout sur votre propre matériel. Pas d'APIs cloud, pas de fuites de données, pas d'abonnements. Conçu pour les environnements isolés (air-gapped) et les données métier sensibles.

---

## 🖥️ Expérience Visuelle Moderne

Spectra propose un tableau de bord réactif sous **React 19 / Tailwind CSS 4** qui donne vie à vos opérations LLM.

*   **📊 Moniteur en Direct** : Visualisation en temps réel de l'ingestion, de la génération de dataset et de la progression du fine-tuning via SSE (Server-Sent Events).
*   **🎮 RAG Playground** : Une interface de chat complète avec réponses en streaming, paramètres réglables (température, top-p, top-k) et transparence des sources.
*   **📂 Gestion Documentaire** : Ingestion par glisser-déposer, récupération d'URL distantes (via Chrome headless) et suivi d'état.
*   **🧬 Fine-Tuning Studio** : Configurez votre rang LoRA, l'alpha et le taux d'apprentissage via une interface guidée avec suivi de la perte en temps réel.
*   **📦 Model Hub** : Changez de modèle à chaud, téléchargez les GGUF recommandés depuis HuggingFace et gérez votre registre de modèles local.

---

## ⚡ Mode Batch Automatisé

Besoin d'entraîner un modèle pendant la nuit ? Spectra inclut un puissant **Mode Batch Automatisé**.

Configurez un répertoire source, et Spectra va :
1.  **Ingérer** automatiquement tous les fichiers supportés dans le dossier.
2.  Lancer la **Génération de Dataset** sur l'ensemble du corpus.
3.  Soumettre un **Job de Fine-Tuning** utilisant les données générées.
4.  Produire un **Modèle GGUF** spécialisé prêt à l'emploi.

Pointez simplement vos données, activez `spectra.batch.enabled=true`, et réveillez-vous avec un LLM spécialisé.

---

## 🛠️ Stack Technique

| Composant | Technologie |
| :--- | :--- |
| **Backend** | Java 21 / Spring Boot 3.4 (Virtual Threads / Loom) |
| **Frontend** | React 19 / Vite / Tailwind CSS v4 |
| **Inférence** | llama.cpp (GGUF, API compatible OpenAI) |
| **Base Vectorielle** | ChromaDB (API v2) |
| **Fine-Tuning** | Unsloth / PEFT (QLoRA) |
| **Parsing** | PyMuPDF4LLM / Docling / Browserless (Chrome Headless) |
| **Recherche** | BM25 (implémentation Java) + Cross-Encoder Reranker |

---

## 🚀 Démarrage Rapide

### Prérequis
*   **Docker Desktop** (ou Docker Engine + Compose v2)
*   **16 Go+ de RAM** (32 Go recommandés pour les gros datasets)
*   **GPU (Optionnel)** : Accélère l'inférence et l'entraînement (NVIDIA recommandé).

### Installation Rapide
```bash
# Cloner le dépôt
git clone https://github.com/YourOrg/Spectra.git && cd Spectra

# Exécuter la configuration (vérifie les ressources et crée les dossiers)
./setup.sh

# Démarrer la stack (mode CPU)
./start.sh --detach
```

Pour l'accélération GPU : `./start.sh --detach --gpu`

### Accès
*   **Interface Web** : `http://localhost:80`
*   **Docs API** : `http://localhost:8080/swagger-ui.html`
*   **Serveur LLM** : `http://localhost:8081`
*   **Métriques Prometheus** : `http://localhost:8080/actuator/prometheus`

### Déploiement Kubernetes / GKE (optionnel)
Spectra fournit des manifestes Kubernetes complets (`k8s/`, kustomize) et un pipeline CI/CD pour **Google Kubernetes Engine** :

```bash
kubectl apply -k k8s/                 # cluster local (minikube, kind, k3s…)
kubectl apply -k k8s/overlays/gpu     # overlay GPU NVIDIA (opt-in)
```

Le workflow `.github/workflows/deploy-gke.yml` build, push et déploie sur GKE à chaque push sur `main` (authentification **Workload Identity Federation**, sans clé JSON). Guide complet : **[docs/DEPLOY_GKE.md](docs/DEPLOY_GKE.md)**.

---

## ⚖️ Licence

Spectra est publié sous licence **GNU AGPL-3.0**. Vous pouvez l'utiliser, le modifier et l'auto-héberger librement. L'AGPL est une licence copyleft forte : si vous exécutez une version modifiée comme service réseau, vous devez en mettre le code source correspondant à disposition de ses utilisateurs. Voir [LICENSE](LICENSE) pour le texte complet.

---

*Spectra — Du document brut à l'expertise métier.*
