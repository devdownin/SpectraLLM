# Exemples — Documents de démonstration

Ce dossier contient des documents de démonstration pour tester Spectra sans avoir à fournir vos propres fichiers. Ingérez-les en une commande pour valider que le pipeline complet fonctionne.

## Démarrage rapide

```bash
# Windows
adddoc.bat examples

# Linux / macOS
bash adddoc.sh examples

# Via l'API directement
curl -X POST http://localhost:8080/api/ingest \
  -F "files=@examples/guide-maintenance-industrielle.txt" \
  -F "files=@examples/lexique-terminologie-technique.txt" \
  -F "files=@examples/procedures-securite-atelier.txt"
```

## Documents inclus

| Fichier | Contenu | Chunks estimés |
|---------|---------|----------------|
| `guide-maintenance-industrielle.txt` | Guide de maintenance préventive et corrective pour équipements industriels | ~15 |
| `lexique-terminologie-technique.txt` | Glossaire de 60+ termes techniques du domaine maintenance/exploitation | ~8 |
| `procedures-securite-atelier.txt` | Procédures de sécurité, EPI, consignation/déconsignation | ~10 |

Ces trois documents couvrent le vocabulaire, les procédures et les nomenclatures — les trois types de contenu que Spectra gère le mieux.

## Résultat attendu après ingestion

Après `adddoc.bat examples` + génération de dataset, vous devriez obtenir :
- ~30 chunks vectorisés dans ChromaDB
- ~25–40 paires d'entraînement (selon les paramètres)
- Des questions du type : "Quelle est la fréquence recommandée pour la lubrification des roulements ?" ou "Quels EPI sont obligatoires lors d'une opération de soudage ?"

## Corpus autoroutier pour l'écran « Optimisation » (Hit@k / MRR)

Le sous-dossier `examples/highway/` est le corpus **aligné sur le benchmark
d'évaluation** `benchmarks/highway_benchmark.jsonl`. Il sert à activer les
métriques de *retrieval* (Hit@k, MRR, Recall@k) de l'ablation : chaque question
répondable du benchmark est annotée d'`expectedSources` pointant vers ces
fichiers, et le score de retrieval vérifie que la bonne source remonte dans le top-k.

```bash
# Windows
adddoc.bat examples\highway

# Via l'API directement (Linux / macOS / Windows)
curl -X POST http://localhost:8080/api/ingest \
  -F "files=@examples/highway/procedures-intervention-autoroute.txt" \
  -F "files=@examples/highway/evenements-trafic-pmv.txt" \
  -F "files=@examples/highway/nomenclature-exploitation-autoroute.txt" \
  -F "files=@examples/highway/reglementation-securite-autoroute.txt"
```

| Fichier | Couvre (catégorie benchmark) |
|---------|------------------------------|
| `procedures-intervention-autoroute.txt` | panne BAU, accident corporel, animal errant, viabilité hivernale (`procedures`) |
| `evenements-trafic-pmv.txt` | limitation PMV, diffusion message, brouillard (`evenements`) |
| `nomenclature-exploitation-autoroute.txt` | PR, BAU, tronçon, PMV (`nomenclatures`) |
| `reglementation-securite-autoroute.txt` | arrêt BAU, EPI, conformité maintenance (`reglementation`) |

Une fois ce corpus ingéré, lancez l'ablation depuis la page **Optimisation** (ou
`POST /api/ablation`) : les colonnes Hit@k / MRR / Recall@k se renseignent
automatiquement. Les 6 questions non-répondables du benchmark restent sans
`expectedSources` (elles servent à mesurer le taux d'hallucination, pas le retrieval).

## Remplacer par vos propres documents

Ces exemples sont conçus pour la démonstration. Pour un usage réel, remplacez-les par vos propres documents métier (manuels techniques, procédures internes, nomenclatures, réglementations, fiches incidents...).
