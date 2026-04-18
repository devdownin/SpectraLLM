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

## Remplacer par vos propres documents

Ces exemples sont conçus pour la démonstration. Pour un usage réel, remplacez-les par vos propres documents métier (manuels techniques, procédures internes, nomenclatures, réglementations, fiches incidents...).
