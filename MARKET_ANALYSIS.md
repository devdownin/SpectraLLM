# Analyse Comparative du Marché — Spectra

Ce document compare Spectra avec les meilleures solutions open-source du marché en 2026 pour identifier les forces de Spectra et ses axes d'amélioration stratégiques.

## 1. Panorama des Solutions Comparées

| Solution | Catégorie | Force Principale | Usage Cible |
|----------|-----------|------------------|-------------|
| **Spectra** | **Domain LLM Builder** | Orchestration de bout-en-bout locale | PME / Entités isolées (Air-gapped) |
| **LLaMA-Factory** | Fine-Tuning | Interface UI ultra-complète | Chercheurs et ingénieurs ML |
| **Axolotl** | Fine-Tuning | Configuration YAML & Multi-GPU | Production scale-out / Cloud GPU |
| **RAGFlow** | RAG / Parsing | Deep Document Understanding (OCR/Layout) | Documents complexes (tableaux, schémas) |
| **Dify / LangFlow** | Orchestration | Workflows visuels & Agents | Développeurs d'applications LLM |

---

## 2. Comparaison Détaillée

### A. Ingestion et Parsing
*   **Spectra** : Extraction basique (PDF, DOCX, HTML). Efficace pour du texte brut, mais perd la structure sémantique des tableaux complexes.
*   **RAGFlow (Leader)** : Utilise des modèles visuels (DeepDoc) pour reconnaître la structure des documents. Capable d'extraire des tableaux et de comprendre le layout.
*   **Piste d'amélioration** : Intégrer un moteur de parsing "layout-aware" comme **Docling** ou **MinerU** pour mieux traiter les plans techniques autoroutiers.

### B. Qualité du RAG
*   **Spectra** : Recherche vectorielle simple (Dense Retrieval). Sensible au bruit si les documents sont volumineux.
*   **Dify / LangChain (Leader)** : Proposent du **Hybrid Search** (Vecteurs + Mots-clés BM25) et du **Re-ranking** via Cross-Encoders (ex: BGE-Reranker).
*   **Piste d'amélioration** : Ajouter une étape de re-ranking après la recherche ChromaDB pour trier les 10 meilleurs chunks et n'en garder que les 2 plus pertinents.

### C. Fine-Tuning
*   **Spectra** : QLoRA standard (Unsloth/PEFT). Très efficace sur CPU/GPU unique.
*   **Axolotl (Leader)** : Supporte des techniques avancées comme **NEFTune**, **DoRA**, et **rsLoRA** qui améliorent la stabilité de l'entraînement.
*   **Piste d'amélioration** : Exposer plus de paramètres d'optimisation dans l'interface de fine-tuning (ex: Rank Stabilization, NEFTune noise alpha).

### D. Dataset Generation
*   **Spectra** : Génération multi-tâches (QA, Summary, Classification) avec LLM-as-a-judge. Déjà très avancé pour un outil "all-in-one".
*   **LLaMA-Factory** : Permet de combiner des datasets hétérogènes (Alpaca, ShareGPT) facilement.
*   **Piste d'amélioration** : Permettre l'import de datasets externes pour mixer les connaissances métier de Spectra avec des bases de connaissances générales.

---

## 3. Analyse SWOT de Spectra

### 💪 Forces (Strengths)
*   **Verticalisation Métier** : Conçu spécifiquement pour le domaine autoroutier (nettoyage de texte adapté aux PK, codes, etc.).
*   **Simplicité "Zero-Ops"** : Une seule commande `docker compose up` pour avoir toute la chaîne.
*   **Optimisation CPU** : Performance exceptionnelle sur matériel standard grâce à llama.cpp et Unsloth (simulation/SFT).

### ❌ Faiblesses (Weaknesses)
*   **RAG Naïf** : Manque de re-ranking, ce qui peut causer des hallucinations si le contexte est bruité.
*   **Parsing Visuel Absent** : Difficulté à traiter les annexes techniques contenant des schémas ou des tableaux denses.

### 🚀 Opportunités (Opportunities)
*   **Agentic RAG** : Transformer Spectra en un agent capable de consulter plusieurs sources ou d'appeler des APIs externes (météo trafic, API GID).
*   **Distillation de Modèle** : Utiliser un gros modèle (ex: Llama-3-70B) pour générer le dataset et entraîner un petit modèle (ex: Phi-3-3.8B) extrêmement performant.

### ⚠️ Menaces (Threats)
*   **Solutions Cloud (Dify/Vercel)** : Plus faciles à déployer pour ceux qui n'ont pas de contrainte de souveraineté.
*   **Évolution du matériel** : Si les NPUs (Apple M4, Snapdragon X Elite) deviennent le standard, Spectra devra s'adapter à leurs backends spécifiques.

---

## 4. Roadmap Recommandée (Pistes d'Amélioration)

### Court Terme (Quick Wins)
1.  **Re-ranking post-retrieval** : Intégrer un modèle de re-ranking léger (ex: `bge-reranker-v2-m3`) pour booster la précision du RAG de 20-30%.
2.  **Hybrid Search** : Combiner la recherche vectorielle avec une recherche plein texte (FTS) dans ChromaDB.

### Moyen Terme
1.  **Layout-Aware Parsing** : Remplacer `pdftotext` par une solution capable de préserver la structure des tableaux.
2.  **Multimodalité** : Permettre l'ingestion d'images (photos d'incidents, plans) pour du RAG visuel.

### Long Terme
1.  **Agentic RAG** : Implémenter des boucles de raisonnement (ReAct) pour que Spectra puisse "réfléchir" avant de répondre.
2.  **Souveraineté Totale** : Certification SecNumCloud via des déploiements Kubernetes durcis.
