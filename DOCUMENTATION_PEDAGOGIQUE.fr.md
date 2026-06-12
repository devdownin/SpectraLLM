# 📚 Guide Pédagogique et Technique de Spectra

Bienvenue dans la documentation officielle de Spectra. Ce guide a pour but d'expliquer, de manière à la fois accessible et précise, le fonctionnement interne de Spectra, les algorithmes utilisés et comment ils transforment vos documents en une expertise IA souveraine.

---

## 1. Introduction : Qu'est-ce que Spectra ?

Spectra est un **Domain LLM Builder**. Contrairement aux assistants IA généralistes (comme ChatGPT), Spectra est conçu pour devenir un expert de **votre** domaine métier en utilisant **votre** documentation interne.

Il repose sur deux piliers technologiques :
1.  **Le RAG (Retrieval-Augmented Generation)** : L'IA "lit" vos documents en temps réel pour répondre à une question.
2.  **Le Fine-Tuning (Affinage)** : L'IA "apprend" de manière permanente votre jargon et vos spécificités pour améliorer sa compréhension globale.

Le tout fonctionne **100 % en local**, garantissant qu'aucune donnée ne quitte votre infrastructure.

---

## 2. Le Pipeline d'Ingestion : De la donnée brute au savoir structuré

L'ingestion est l'étape où Spectra "digère" vos documents. Ce n'est pas un simple copier-coller ; c'est un processus complexe en plusieurs étapes.

### A. Parsing "Layout-Aware" (Conscience de la mise en page)
Un PDF classique est un amas de texte. Les tableaux et les colonnes sont souvent mélangés lors d'une extraction simple. Spectra utilise des outils comme **PyMuPDF4LLM** ou **IBM Docling** pour transformer ces fichiers en **Markdown**.
*   **Pourquoi ?** Le format Markdown préserve la structure (titres, tableaux, listes). Pour un LLM, lire un tableau structuré est bien plus efficace que de lire une liste de mots désordonnés.

### B. Nettoyage du texte (Text Cleaning)
Spectra applique 8 étapes de nettoyage automatique :
*   Normalisation Unicode (pour les caractères spéciaux).
*   Suppression des en-têtes et pieds de page récurrents.
*   Correction des ligatures OCR (ex: "ﬀ" devient "ff").
*   Nettoyage des bordures de tableaux ASCII.

### C. Chunking Sémantique (Découpage)
Un document de 50 pages est trop gros pour être lu d'un coup par l'IA. Spectra le découpe en "chunks" (morceaux) d'environ **512 tokens** (~2000 caractères).
*   **Technique** : Spectra utilise un "sliding window" (fenêtre glissante) avec un chevauchement de 64 tokens. Cela garantit qu'une phrase coupée en fin de chunk se retrouve entière au début du suivant, évitant ainsi la perte de contexte.

### D. Les Embeddings (La vectorisation)
C'est ici que la magie opère. Chaque chunk de texte est transformé en une liste de nombres (un **vecteur**) par un modèle spécialisé (**Nomic Embed**).
*   **Principe** : Dans cet espace mathématique, deux phrases ayant un sens proche (ex: "Le moteur chauffe" et "Surchauffe de l'unité motrice") auront des vecteurs très proches.
*   **Stockage** : Ces vecteurs sont rangés dans **ChromaDB**, notre base de données vectorielle.

---

## 3. Le Pipeline de Recherche (Retrieval) : Trouver l'aiguille dans la botte de foin

Quand vous posez une question, Spectra doit trouver les chunks les plus pertinents. Il utilise pour cela une stratégie "Hybride".

### A. Recherche Vectorielle (Sémantique)
Spectra cherche les chunks dont les vecteurs sont les plus proches de celui de votre question. C'est excellent pour comprendre l'intention et les synonymes.

### B. Recherche BM25 (Mots-clés)
Le **BM25 (Best Matching 25)** est un algorithme de recherche classique (utilisé par les moteurs de recherche). Il est très performant pour trouver des termes techniques exacts, des numéros de série ou des acronymes spécifiques que les vecteurs pourraient parfois "diluer".

### C. Reciprocal Rank Fusion (RRF)
Spectra combine les résultats de la recherche vectorielle et du BM25 via l'algorithme **RRF**.
*   **Formule** : `score = 1 / (60 + rang_vecteur) + 1 / (60 + rang_bm25)`.
*   **Objectif** : Favoriser les documents qui apparaissent en haut des deux listes, garantissant un équilibre parfait entre sens global et précision terminologique.

### D. Re-ranking (Le contrôle qualité)
Spectra sélectionne les 20 meilleurs candidats et les soumet à un **Cross-Encoder**. Contrairement aux étapes précédentes qui sont rapides mais approximatives, le Cross-Encoder analyse la paire (Question + Chunk) en profondeur pour donner un score de pertinence final. Seuls les 5 meilleurs (typiquement) sont conservés.

---

## 4. Génération de Réponses : L'IA qui raisonne

Une fois les sources trouvées, Spectra prépare le "contexte" pour le LLM de chat.

### A. RAG Standard
Spectra envoie au LLM : "Voici des extraits de documents : [Extraits]. En t'appuyant uniquement sur eux, réponds à : [Question]".

### B. RAG Agentique (Boucle ReAct)
Pour les questions complexes, Spectra active le mode **Agentique** basé sur le framework **ReAct** (Reasoning + Acting).
1.  **Pensée (Thought)** : Le LLM analyse la question.
2.  **Action** : Il décide de faire une recherche.
3.  **Observation** : Il lit les résultats.
4.  **Boucle** : S'il lui manque une info, il formule une *nouvelle* recherche plus précise.
5.  **Réponse finale** : Il synthétise le tout une fois qu'il a assez d'éléments.

### C. Optimisations de contexte
*   **Context Compression** : Spectra demande au LLM d'extraire uniquement les phrases utiles de chaque chunk pour ne pas encombrer sa mémoire de travail avec du bruit.
*   **Semantic Deduplication** : Utilise la similarité de **Jaccard** pour supprimer les chunks qui disent presque la même chose (souvent le cas dans les documents versionnés).

---

## 5. Spécialisation : Fine-Tuning et Apprentissage Continu

Le RAG permet de répondre, mais le fine-tuning permet au modèle de **devenir** l'expert.

### A. Synthèse de Dataset
Spectra utilise son propre LLM pour lire vos chunks et s'auto-générer des exercices d'entraînement :
*   **Q&A** : Questions et réponses basées sur le texte.
*   **DPO (Direct Preference Optimization)** : Spectra génère une bonne réponse et une mauvaise réponse (hallucination plausible). Cela apprend au modèle à rejeter les erreurs.
*   **Garde Jaccard** : Pour garantir la qualité du dataset DPO, Spectra rejette automatiquement les paires où la bonne et la mauvaise réponse sont trop similaires (indice de Jaccard > 0,85).

### B. Fine-Tuning QLoRA (Unsloth)
Spectra utilise la technique **QLoRA** via le framework **Unsloth**.
*   **Pédagogie** : Au lieu de modifier les milliards de paramètres du cerveau de l'IA (ce qui est lent et lourd), on lui ajoute de "petits modules" de mémoire (LoRA) qu'on entraîne. C'est 2x plus rapide et demande beaucoup moins de mémoire vidéo (VRAM).

### C. La Boucle de Rétroaction (Feedback Loop)
Via l'interface, vous pouvez évaluer les commentaires générés (👍/👎). Ces évaluations sont transformées en paires DPO. Spectra peut même déclencher un ré-entraînement automatique dès que vous avez validé un certain nombre de commentaires. Le modèle s'améliore ainsi chaque jour grâce à votre expertise.

**Évaluation "LLM-as-a-Judge"** : Pour mesurer les progrès, Spectra peut auto-évaluer ses propres réponses en utilisant le LLM comme un juge qui attribue une note de 1 à 10 et justifie son verdict.

---

## 6. Infrastructure et Souveraineté

*   **100 % Local** : Spectra utilise **Docker** pour isoler ses services. Tout tourne sur votre machine, sans connexion internet requise pour l'inférence.
*   **Hardware Auto-Detection** : Au démarrage, Spectra analyse votre CPU, votre RAM et votre GPU (NVIDIA, AMD ou Vulkan) pour configurer automatiquement les paramètres optimaux (nombre de threads, couches GPU, taille du contexte).
*   **Sécurité** : L'accès à l'API peut être protégé par une clé `X-API-Key`.

---

## 7. Glossaire des Algorithmes

| Terme | Explication simple |
| :--- | :--- |
| **Embedding** | Traduction d'un texte en une liste de nombres représentant son sens. |
| **ChromaDB** | La base de données qui stocke et compare ces listes de nombres. |
| **BM25** | Algorithme de recherche par mots-clés (statistiques sur la fréquence des mots). |
| **RRF** | Méthode mathématique pour fusionner deux classements différents. |
| **Cross-Encoder** | Modèle IA "expert" qui compare précisément une question et un document. |
| **QLoRA** | Méthode d'entraînement ultra-efficace pour les LLMs. |
| **DPO** | Technique pour apprendre à l'IA vos préférences (ce qui est "bon" vs "mauvais"). |
| **ReAct** | Stratégie où l'IA alterne entre réflexion et action (recherche). |

---
*Spectra : Du document brut à l'expertise métier, en toute confidentialité.*
