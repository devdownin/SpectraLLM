# Synthèse et décision — optimisation de SpectraLLM (Qdrant + EnterpriseRAG-Bench)

> Étape 7. Rédigée le 2026-11-10 à partir des étapes 1 à 6 (PR #487, #489, #490, #491).
> La version chiffrée de cette synthèse se **génère** : `python3 scripts/optimization_summary.py` lit les
> résultats présents, dit pour chaque étape s'ils existent, et refuse de conclure quand ils manquent.

## 1. Verdict

**L'outillage est prêt et éprouvé par des tests ; aucune mesure n'a été exécutée, donc aucune amélioration de
performance n'est établie.** Ni Docker, ni modèles, ni accès à Hugging Face, ni clé de juge n'étaient disponibles
dans l'environnement de travail. Ce qui est décidé ci-dessous porte donc sur la **méthode**, les **règles
d'adoption** et la **correction de défauts** ; la **configuration gagnante** reste à désigner par les mesures.

La baseline v1 reste la référence officielle jusqu'à preuve du contraire.

## 2. Décisions prises

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Baseline v1 figée** (`deploy/bench/enterprise-rag-baseline-v1.*`) comme seule référence de comparaison | Sans point fixe, un écart entre deux essais ne veut rien dire |
| D2 | **Comparer en apparié, avec IC95 %**, sur `dev-120` ; confirmer sur les 380 questions de validation avant de figer | Une question difficile l'est pour les deux réglages : seule la différence compte |
| D3 | **Règle d'adoption du retrieval** : rappel@10 *et* nDCG@10 en hausse (IC95 % > 0), bruit `extra@10` non aggravé, p95 ≤ 2 × la référence | Évite d'adopter un gain de rappel payé en bruit ou en latence (`enterprise_rag_stage2_summary.py`) |
| D4 | **Génération jugée par un modèle distinct** du modèle évalué (Claude Opus 5.5), même juge pour toutes les variantes comparées ; refus du juge = panne, jamais de repli sur un autre modèle | Un juge qui change en cours de mesure rend les scores incomparables |
| D5 | **Règle go/no-go du fine-tuning** : non-infériorité du score, zéro nouvelle hallucination hors corpus, aucune régression de catégorie, parité GGUF q8_0/f16 | Le benchmark est petit : on exige l'absence de perte probable plutôt qu'un gain significatif |
| D6 | **Génération évaluée déterministe** (température 0) dans le benchmark qualité Java | À 0,7, deux passages du même modèle différaient : l'écart entre versions se perdait dans le tirage |
| D7 | **Confidentialité par défaut** : questions et URL masquées dans les journaux, installation de modèles refusable (`SPECTRA_OFFLINE`), air-gap imposé par Docker (`docker-compose.airgap.yml`) | Le mode « local » ne doit rien envoyer, et les journaux ne sont pas un lieu de stockage de questions |
| D8 | **Aucun levier combiné sans rejeu** : les réglages adoptés un par un se valident ensemble (baseline rejouée avec la combinaison) | Deux gains isolés ne s'additionnent pas toujours |

## 3. Ce qui a été livré

| Étape | Livrable | Point d'entrée |
|---|---|---|
| 1. Baseline | configuration et profil figés, métriques (rappel@k, MRR, nDCG, fidélité, latence), commande `report` | `scripts/enterprise_rag_baseline.sh` |
| 2. Recherche | surcharges Compose, verdict adopter/rejeter par comparaison, guide d'exécution via `suite` | `docs/tech/enterprise-rag-stage2.fr.md` |
| 3. Retrieval | tableau rappel/latence par configuration, audit par index (dense, hybride, reranker, filtres, HNSW, ACORN, hybride natif), embeddings BGE-M3 et Qwen3 | `scripts/enterprise_rag_audit.sh`, `enterprise_rag_matrix.py` |
| 4. Génération | juge Opus 5.5 pour l'évaluateur Onyx, fidélité, précision des citations, refus correct et abusif, par catégorie | `scripts/enterprise_rag_generation_audit.py` |
| 5. Fine-tuning | collecte figée et décision go/no-go par version, export GGUF f16 de référence | `scripts/finetune_audit.py` |
| 6. Confidentialité | checklist, contrôles rejouables (journaux, sorties réseau, air-gap), contrôle d'exécution des résidus | `docs/process/checklist-confidentialite.fr.md` |
| 7. Synthèse | cette note et la génération du rapport chiffré | `scripts/optimization_summary.py` |

## 4. Défauts trouvés en chemin (corrigés)

- Le benchmark qualité Java générait à température 0,7 (bruit de mesure) → déterministe.
- `SPECTRA_STARTUP_AUTO_INSTALL_MODELS` et `SPECTRA_CHUNK_LOCALE` n'atteignaient pas le conteneur `spectra-api` :
  une valeur posée dans `.env` était sans effet. Même cause, deux variables.
- La recherche de dépôt GGUF ignorait `HF_ENDPOINT` (miroir d'entreprise) et appelait `huggingface.co` en dur.
- 12 appels de journal écrivaient une question, une reformulation ou une URL brute.
- 2 vulnérabilités hautes npm (`zod`, `follow-redirects`) et 6 avis `python-multipart` (docparser).
- Deux régressions de fusion introduites en cours de route (import de `SpectraClient`, rappel@20 de `compare`),
  corrigées dans #489 : une commande `suite` plantait au démarrage.

Particularités de la baseline, **volontairement conservées** pour ne pas fausser la référence, à mesurer comme
leviers : découpage en locale `fr` sur un corpus anglais (`index-locale-en.env`), chunks de 256 tokens alors que
`application.yml` prévoit 512 (`index-chunk-512.env`), 20 candidats côté serveur contre 50 par défaut du script
d'expérience (couvert par la série `candidates`).

## 5. Écarts ouverts

| Gravité | Écart | Action |
|---|---|---|
| Élevée | Aucune mesure n'a tourné : rien de ce qui précède n'est quantifié | Exécuter le plan du § 6 |
| Moyenne | Avis sur `torch`, `transformers`, `sentence-transformers` (reranker) | Montée faite ; scores et parité non vérifiés (service optionnel, `engine=http` seulement) |
| Moyenne | Pas de scan Python, npm ni images en CI ; images frontend à étiquette flottante | Ajouter `pip-audit`, `npm audit --omit=dev`, scan d'images (l'avis du reranker est traité) ; épingler par condensat |
| Moyenne | Données en clair sous `data/`, pas de chiffrement au repos ; Qdrant sans clé d'API par défaut | Chiffrer le volume ; clé d'API sur hôte partagé |
| Moyenne | Un document supprimé peut subsister dans les segments Qdrant ; jeux d'entraînement et poids dérivés non liés à la suppression | Mesurer (`privacy_runtime_check.sh`) ; effacement garanti = supprimer `data/qdrant` |
| Basse | Rétention des journaux codée en dur (30 jours, 3 Go) ; noms de fichiers non masqués | Décision de politique |
| Basse | `UrlFetcherServiceTest` dépend du DNS ; installateur `llmfit` non vérifié par défaut | Simuler la résolution ; renseigner `LLMFIT_SHA256` |
| À vérifier | Noms de fichiers GGUF des overlays BGE-M3 et Qwen3 ; `backend_revision` du profil de baseline | Corriger avant usage |
| Non couvert | Langue des consignes du prompt RAG (français sur corpus anglais) | Aucun outil ne la fait varier : à ajouter si le levier est retenu |

## 6. Plan d'exécution des mesures

Prérequis de l'hôte : Docker, de quoi charger Qwen2.5-7B (Q4_K_M) et le reranker, accès à Hugging Face pour le
corpus (1,4 Go), clé du juge (Anthropic pour D4), clone figé d'EnterpriseRAG-Bench. Chaque étape ne démarre que
si la précédente a passé son critère.

| Ordre | Action | Critère de passage |
|---|---|---|
| 0 | Remplacer `backend_revision` du profil par le digest déployé ; démarrer avec `enterprise-rag-baseline-v1.env` | `applied.rerankApplied` et `hybridSearchApplied` = 1 |
| 1 | `scripts/enterprise_rag_baseline.sh` | `report.json` en `retrieve` et en `answer` jugé |
| 2 | `suite` (candidats 20/50, multi-query, regroupement) sur deux piles | verdicts du § D3 ; budget de candidats retenu |
| 3 | `enterprise_rag_audit.sh` par index : nomic, BGE-M3, Qwen3, puis `native`, int8, HNSW m=32, chunks 512, locale en | `matrix.md` : au moins la référence et un challenger par axe |
| 4 | `enterprise_rag_generation_audit.py ground` puis `report` sur la baseline et sur la variante retenue | catégories faibles identifiées ; refus abusifs et corrects mesurés |
| 5 | `finetune_audit.py collect` puis `decide` pour chaque version, avec référence f16 | une décision GO ou NO-GO par version |
| 6 | Capture réseau, `privacy_audit.py airgap`, `privacy_runtime_check.sh` (§ 5 de la checklist) | résidu nul, sortie nulle |
| 7 | Combiner les réglages adoptés, rejouer 1, confirmer sur les 380 questions | écart apparié confirmé ; puis figer un `baseline-v2` |
| 8 | `python3 scripts/optimization_summary.py --work … --finetune … --runtime-checked --out synthese.md` | verdict global sans « INCOMPLET » ni « REFUS » |

Le coût du juge est le seul poste proportionnel au nombre de questions : l'audit de génération fait jusqu'à 25
appels par réponse (12 affirmations notées au plus, deux appels chacune, plus le refus). Mesurer sur une dizaine de questions avant de lancer les 120.

## 7. Décisions qui vous reviennent

1. ~~**Juge unique.**~~ **Tranché : Claude Opus 5.5 pour tous les runs comparés.** `enterprise_rag_baseline.sh`
   l'utilise par défaut ; le juge d'Onyx (`gpt-5.4`) reste disponible par `LLM_PROVIDER=openai` pour se comparer au
   classement public, mais ne se mélange jamais avec le premier dans une comparaison (`compare` les refuse).
2. **Hôte de mesure.** Qui lance le plan du § 6, et sur quelle machine ? Je peux aussi préparer un script qui
   enchaîne les étapes 1 à 5 sur un seul hôte.
3. **Avis du reranker.** Corriger d'abord (rejeu de parité requis) ou accepter le risque, le service étant
   optionnel et sur réseau interne ?
4. **Politique de rétention et de chiffrement** (journaux, volume `data/`) : à fixer par l'exploitant.
5. **Ordre de fusion.** La PR #491 (étape 6, avec la présente synthèse) est ouverte.
