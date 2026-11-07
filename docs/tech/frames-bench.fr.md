# Évaluer SpectraLLM avec Google FRAMES

[Google FRAMES](https://huggingface.co/datasets/google/frames-benchmark) fournit 824 questions en anglais, leurs réponses de référence et les articles Wikipédia associés. Le script interroge le pipeline SpectraLLM via `POST /api/query`. Il réserve les réponses de référence au juge et utilise les liens par question uniquement pour calculer le rappel après génération. Le corpus RAG est partagé entre toutes les questions du run.

## Premier passage

Démarrer une instance SpectraLLM dédiée, avec un index vide, un modèle de chat et un modèle d'embedding. Utiliser la même version, les mêmes modèles et le même échantillon pour les deux modes. Pour un déploiement agentique, définir `SPECTRA_AGENTIC_LANGUAGE=en`.

```bash
python3 scripts/frames_bench.py --work-dir data/frames-pilot prepare --limit 20
python3 scripts/frames_bench.py --work-dir data/frames-pilot ingest

export FRAMES_JUDGE_URL=http://localhost:8091/v1
# Avec un juge distant authentifié, fournir FRAMES_JUDGE_API_KEY via votre environnement.
# Remplacer judge-model-id par l'identifiant du modèle juge réellement servi.
python3 scripts/frames_bench.py --work-dir data/frames-pilot run --limit 20 \
  --mode closed-book --judge-model judge-model-id --output data/frames-pilot/closed-book.jsonl
python3 scripts/frames_bench.py --work-dir data/frames-pilot run --limit 20 \
  --mode rag --judge-model judge-model-id --output data/frames-pilot/rag.jsonl
```

`--api` désigne l'URL du backend SpectraLLM, par défaut `http://localhost:8080`. Utiliser de préférence un modèle juge distinct du modèle évalué. Sans `--judge-model`, le script conserve les réponses et mesure la latence et le rappel en mode RAG. Un fichier de sortie existant est protégé contre l'écrasement. Un run interrompu laisse un JSONL partiel et ne produit aucun agrégat final.

## Passage complet

Retirer `--limit` à la préparation et aux deux exécutions, utiliser un nouveau dossier de travail et un index vierge. Conserver le dossier du corpus avec les résultats. Le TSV est épinglé à une révision Hugging Face et les pages Wikipédia sont téléchargées dans leur version courante puis mises en cache. Les relances de préparation réutilisent les pages déjà téléchargées.

Comparer le taux de réponses correctes, le rappel moyen des articles et la latence p95. Le rappel est mesuré sur les sources que renvoie l'API, et la latence exclut le temps du juge. Le résumé JSON s'affiche sur la sortie standard et ventile ces mesures par type de raisonnement. Une question peut compter dans plusieurs types.

Avant les requêtes, le script vérifie la santé des services nécessaires et soumet une réponse connue au juge configuré. En mode RAG, il contrôle aussi l'inventaire via l'historique d'ingestion. Une ingestion complète, active et avec des fragments sert de preuve d'indexation. Le script refuse un historique contenant des fichiers hors corpus.

Le diagnostic par question distingue les articles manquants dans cet inventaire, les articles inventoriés mais absents des sources retournées et les réponses jugées fausses malgré la présence de tous les articles attendus. Cette dernière catégorie aide à inspecter les passages et la synthèse. La présence d'un article dans les sources ne garantit pas que le passage utile a été fourni. Chaque ligne JSONL contient l'identifiant, le type de raisonnement, la réponse, les articles attendus, les sources retournées et le verdict éventuel.

Le pilote réduit le nombre de questions et le corpus à leurs articles de référence. Le passage complet utilise l'union des articles de toutes les questions. Le corpus courant et le choix du juge font de ce résultat une mesure interne sur FRAMES. Consigner la configuration SpectraLLM et conserver un juge fixe pour les comparaisons entre runs. Voir le [protocole publié](https://aclanthology.org/2025.naacl-long.243/).
