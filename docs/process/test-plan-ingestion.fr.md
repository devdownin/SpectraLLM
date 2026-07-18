# Plan de test — Ingestion DOCX et JSON

## Contexte

Ce plan couvre les tests d'ingestion des formats **Word (DOCX)** et **messages JSON**,
qui complètent les PDF déjà validés en session de débogage (458 chunks, COMPLETED).

Les extracteurs concernés :
- `DocxExtractor` — Apache POI (XWPF), extrait paragraphes + tableaux + métadonnées
- `JsonExtractor` — Jackson, aplatit récursivement l'arbre JSON en texte lisible
- `DocumentExtractorFactory.resolveContentType()` — détection par extension (`.docx`, `.doc`, `.json`)

---

## 1. Jeux de données de test

Créer les fichiers suivants dans `data/test/` avant d'exécuter les tests.

### 1.1 Fichiers DOCX

#### `test_procedure_simple.docx`
Document Word minimal avec :
- Titre : "Procédure d'intervention sur voie rapide"
- 3 paragraphes de corps (texte > 50 mots chacun)
- 1 titre de niveau Heading1 : "Étapes d'intervention"
- 1 tableau 3×3 : colonnes Code, Équipement, Délai

Objectif : valider l'extraction des paragraphes, la détection des styles Heading, et la sérialisation des tableaux.

#### `test_metadata_docx.docx`
Document avec propriétés Core renseignées (via Fichier > Propriétés > Résumé) :
- Titre : "Guide de patrouille 2024"
- Auteur : "Direction Exploitation ASF"
- Sujet : "Patrouille autoroutière"

Objectif : valider que `coreProps.getTitle()` et `coreProps.getCreator()` sont bien extraits dans les métadonnées.

#### `test_tables_only.docx`
Document avec uniquement 2 tableaux (pas de paragraphes) :
- Tableau 1 : Nomenclature équipements (5 lignes, 3 colonnes)
- Tableau 2 : Codes incident / Libellé / Priorité (10 lignes)

Objectif : valider que les tableaux seuls produisent du texte exploitable même sans paragraphes.

#### `test_vide.docx`
Document Word vide (aucun contenu, aucune propriété).

Objectif : valider que le service renvoie `chunksCreated: 0` sans erreur (texte blanc → aucun chunk).

#### `test_long_docx.docx`
Document de 50+ pages avec chapitres numérotés (simuler un référentiel technique).

Objectif : valider le chunking sur un document long — vérifier que le nombre de chunks est cohérent (~1 chunk/500 mots) et qu'aucun OOM ne survient.

---

### 1.2 Fichiers JSON

#### `test_message_simple.json`
```json
{
  "type": "INCIDENT",
  "identifiant": "INC-2024-00142",
  "dateHeure": "2024-03-15T14:32:00Z",
  "localisation": {
    "autoroute": "A7",
    "sens": "Sud",
    "PR": "142+300"
  },
  "description": "Véhicule en panne sur la bande d'arrêt d'urgence. Feux de détresse allumés.",
  "gravite": "MOYENNE",
  "intervenants": ["Patrouilleur P12", "Dépanneur agréé"]
}
```
Objectif : valider l'aplatissement (`localisation.autoroute: A7`, `localisation.PR: 142+300`, etc.) et que le texte produit est lisible pour le LLM.

#### `test_tableau_messages.json`
```json
[
  {
    "id": "MSG-001",
    "categorie": "TRAFIC",
    "contenu": "Ralentissement entre PR 12 et PR 18 en raison d'un accident."
  },
  {
    "id": "MSG-002",
    "categorie": "METEO",
    "contenu": "Verglas signalé sur A40 entre Mâcon Nord et Tournus."
  },
  {
    "id": "MSG-003",
    "categorie": "TRAVAUX",
    "contenu": "Fermeture de nuit de la voie de droite entre PR 55 et PR 58 du 20 au 24 mars."
  }
]
```
Objectif : valider la gestion d'un tableau JSON (`elementCount: 3` dans les métadonnées), et que chaque élément produit du texte aplatissable.

#### `test_imbrication_profonde.json`
```json
{
  "session": {
    "id": "SES-2024-001",
    "patrouille": {
      "vehicule": {
        "immatriculation": "AB-123-CD",
        "type": "Véhicule d'intervention léger"
      },
      "agent": {
        "matricule": "P042",
        "nom": "Dupont Jean"
      }
    },
    "interventions": [
      {
        "heure": "08:15",
        "type": "Signalement obstacle",
        "resolu": true
      },
      {
        "heure": "10:45",
        "type": "Assistance automobiliste",
        "resolu": false
      }
    ]
  }
}
```
Objectif : valider la récursivité de `flattenJson` sur 3+ niveaux d'imbrication. Vérifier que les clés produites sont `session.patrouille.vehicule.immatriculation: AB-123-CD` etc.

#### `test_json_malformed.json`
```
{ "type": "INCIDENT", "localisation": { "autoroute": "A7"
```
(JSON invalide — accolade fermante manquante)

Objectif : valider que l'ingestion retourne `status: FAILED` avec un message d'erreur clair, sans crash du service.

#### `test_json_vide.json`
```json
{}
```
Objectif : valider que le service produit `chunksCreated: 0` sans erreur.

#### `test_json_valeurs_nulles.json`
```json
{
  "type": "INCIDENT",
  "description": null,
  "localisation": null,
  "gravite": "HAUTE"
}
```
Objectif : valider que les valeurs `null` sont gérées (Jackson les représente comme texte `null` ou les ignore selon la config).

---

## 2. Procédure d'exécution

### Pré-condition
```bash
# Services démarrés
docker compose up -d
curl http://localhost:8080/api/status   # tous les services "available: true"
```

### Commande générique
```bash
curl -s -X POST http://localhost:8080/api/ingest \
  -F "files=@data/test/<fichier>" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['taskId'])"
```

### Polling du résultat
```bash
curl -s http://localhost:8080/api/ingest/<taskId> | python3 -m json.tool
```

---

## 3. Cas de test détaillés

### CT-DOCX-01 — Extraction basique DOCX

| | |
|---|---|
| **Fichier** | `test_procedure_simple.docx` |
| **Commande** | `curl -X POST .../api/ingest -F "files=@test_procedure_simple.docx"` |
| **Résultat attendu** | `status: COMPLETED`, `chunksCreated >= 1` |
| **Vérifications** | Le texte des paragraphes apparaît dans ChromaDB ; les titres Heading sont préfixés `##` |
| **Résultat observé** | *(à compléter)* |

---

### CT-DOCX-02 — Métadonnées Core Properties

| | |
|---|---|
| **Fichier** | `test_metadata_docx.docx` |
| **Résultat attendu** | Métadonnées du chunk : `title: Guide de patrouille 2024`, `author: Direction Exploitation ASF` |
| **Comment vérifier** | `GET /api/dataset/stats` puis inspecter les chunks via ChromaDB ou les logs |
| **Résultat observé** | *(à compléter)* |

---

### CT-DOCX-03 — Document avec tableaux seulement

| | |
|---|---|
| **Fichier** | `test_tables_only.docx` |
| **Résultat attendu** | `chunksCreated >= 1` ; le texte contient les séparateurs `|` nettoyés par `TextCleanerService` |
| **Résultat observé** | *(à compléter)* |

---

### CT-DOCX-04 — Document vide

| | |
|---|---|
| **Fichier** | `test_vide.docx` |
| **Résultat attendu** | `status: COMPLETED`, `chunksCreated: 0`, log WARN "Aucun chunk produit" |
| **Résultat observé** | *(à compléter)* |

---

### CT-DOCX-05 — Document long (50+ pages)

| | |
|---|---|
| **Fichier** | `test_long_docx.docx` |
| **Résultat attendu** | `status: COMPLETED` sans OOM ; `chunksCreated` proportionnel au volume (~1 chunk/500 mots) |
| **Points de surveillance** | Mémoire heap avant/après (visible dans les logs `IngestionTaskExecutor` : "heap used=XMB") |
| **Résultat observé** | *(à compléter)* |

---

### CT-JSON-01 — Message d'exploitation simple

| | |
|---|---|
| **Fichier** | `test_message_simple.json` |
| **Résultat attendu** | `status: COMPLETED`, `chunksCreated: 1` |
| **Vérifications** | Le texte aplatissé contient `localisation.autoroute: A7`, `localisation.PR: 142+300` |
| **Résultat observé** | *(à compléter)* |

---

### CT-JSON-02 — Tableau de messages

| | |
|---|---|
| **Fichier** | `test_tableau_messages.json` |
| **Résultat attendu** | `chunksCreated: 1` (3 messages courts = 1 chunk) ; métadonnée `elementCount: 3` |
| **Vérifications** | Le texte contient les contenus des 3 messages |
| **Résultat observé** | *(à compléter)* |

---

### CT-JSON-03 — Imbrication profonde

| | |
|---|---|
| **Fichier** | `test_imbrication_profonde.json` |
| **Résultat attendu** | Clé `session.patrouille.vehicule.immatriculation: AB-123-CD` présente dans le texte |
| **Vérifications** | Tableau d'interventions aplati avec indices `session.interventions[0].type: Signalement obstacle` |
| **Résultat observé** | *(à compléter)* |

---

### CT-JSON-04 — JSON malformé

| | |
|---|---|
| **Fichier** | `test_json_malformed.json` |
| **Résultat attendu** | `status: FAILED`, `error` contient "Erreur extraction JSON" |
| **Vérification critique** | Le service ne crashe pas ; les autres fichiers peuvent toujours être ingérés après |
| **Résultat observé** | *(à compléter)* |

---

### CT-JSON-05 — JSON vide

| | |
|---|---|
| **Fichier** | `test_json_vide.json` |
| **Résultat attendu** | `status: COMPLETED`, `chunksCreated: 0` |
| **Résultat observé** | *(à compléter)* |

---

### CT-JSON-06 — Valeurs nulles

| | |
|---|---|
| **Fichier** | `test_json_valeurs_nulles.json` |
| **Résultat attendu** | `status: COMPLETED` ; le texte contient au moins `gravite: HAUTE` |
| **Vérification** | Pas de NullPointerException dans les logs |
| **Résultat observé** | *(à compléter)* |

---

### CT-MIX-01 — Ingestion simultanée DOCX + JSON

| | |
|---|---|
| **Fichiers** | `test_procedure_simple.docx` + `test_message_simple.json` en un seul appel multipart |
| **Commande** | `curl -X POST .../api/ingest -F "files=@test_procedure_simple.docx" -F "files=@test_message_simple.json"` |
| **Résultat attendu** | `status: COMPLETED` ; `chunksCreated` = somme des deux fichiers individuels |
| **Résultat observé** | *(à compléter)* |

---

### CT-EXT-01 — Extension inconnue

| | |
|---|---|
| **Fichier** | `test_inconnu.xyz` (fichier texte renommé) |
| **Résultat attendu** | `status: FAILED`, `error` contient "Extension de fichier non supportée" |
| **Résultat observé** | *(à compléter)* |

---

---

## 4. Plan de Test Fine-Tuning (hôte)

### Prérequis

```bash
pip install peft transformers trl accelerate bitsandbytes
curl -X POST http://localhost:8080/api/dataset/export -o data/fine-tuning/export.jsonl
```

---

### CT-FT-01 — Entraînement CPU (TinyLlama)

| | |
|---|---|
| **Commande** | `python scripts/train_host.py --dataset data/fine-tuning/export.jsonl --output data/fine-tuning/adapter --base-model phi3 --epochs 1 --lora-rank 8` |
| **Résultat attendu** | Progression epoch/loss affichée, `Fine-tuning terminé avec succès` en sortie |
| **Vérification** | `data/fine-tuning/adapter/` contient `adapter_model.safetensors` et `adapter_config.json` |
| **Résultat observé** | ✅ VALIDÉ — loss 2.52→1.92 en ~10 min (50 exemples, CPU, TinyLlama 1.1B) |

---

### CT-FT-02 — Export GGUF et import Ollama

| | |
|---|---|
| **Commande** | `python scripts/export_gguf.py --adapter data/fine-tuning/adapter --output data/fine-tuning/merged --base-model phi3 --model-name spectra-autoroute` |
| **Résultat attendu** | `data/fine-tuning/merged/model.gguf` créé ; `ollama list` affiche `spectra-autoroute` |
| **Vérification** | `ollama run spectra-autoroute "test"` répond sans erreur |
| **Résultat observé** | ✅ VALIDÉ — GGUF 1.17 Go (Q8_0), modèle importé dans Ollama |

---

### CT-FT-05 — Entraînement incrémental

| | |
|---|---|
| **Prérequis** | CT-FT-01 validé — `data/fine-tuning/adapter/adapter_config.json` existe |
| **Commande** | `python scripts/train_host.py --dataset data/fine-tuning/export.jsonl --output data/fine-tuning/adapter --base-model phi3 --resume-adapter data/fine-tuning/adapter --epochs 1` |
| **Résultat attendu** | Affiche `Mode incrémental — reprise depuis : ...` ; loss part d'une valeur inférieure à la run initiale |
| **Vérification** | `adapter_config.json` toujours présent après l'entraînement |
| **Résultat observé** | *(à compléter)* |

---

### CT-FT-06 — Reset via pipeline.bat

| | |
|---|---|
| **Prérequis** | `data\fine-tuning\pipeline-adapter\adapter_config.json` existe |
| **Commande** | `pipeline.bat data\documents phi3 phi3-autoroute --reset` |
| **Résultat attendu** | Affiche `--reset : suppression de l'adaptateur existant` puis `Entrainement initial` ; l'adaptateur est recréé |
| **Vérification** | `data\fine-tuning\pipeline-adapter\` recréé avec un nouvel `adapter_config.json` |
| **Résultat observé** | *(à compléter)* |

---

### CT-FT-03 — Dataset vide (0 paires)

| | |
|---|---|
| **Condition** | Lancer `train_host.py` avec un fichier JSONL vide |
| **Résultat attendu** | Message d'erreur clair : `dataset vide`, exit code 1, pas de crash |
| **Résultat observé** | *(à compléter)* |

---

### CT-FT-04 — Interrogation RAG post fine-tuning

| | |
|---|---|
| **Commande** | `curl -X POST http://localhost:8080/api/query -H "Content-Type: application/json" -d '{"question": "Quelle est la procédure en cas de panne sur voie rapide ?"}'` |
| **Résultat attendu** | `answer` non vide, `sources` contient des chunks ChromaDB |
| **Résultat observé** | *(à compléter)* |

---

## 5. Vérification dans ChromaDB (optionnel)

Pour inspecter le contenu réellement stocké après ingestion :

```bash
# Liste des collections
curl http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections

# Récupérer les N premiers documents d'une collection
curl -X POST http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections/<collection_id>/get \
  -H "Content-Type: application/json" \
  -d '{"limit": 5, "include": ["documents", "metadatas"]}'
```

---

## 6. Critères de Succès Global

| Critère | Seuil | Statut |
|---------|-------|--------|
| Taux de réussite des ingestions valides | 100 % (COMPLETED) | *(à compléter)* |
| Gestion des cas invalides | 100 % (FAILED propre, pas de crash) | *(à compléter)* |
| Aucun OOM sur document long | 0 erreur heap | *(à compléter)* |
| Métadonnées extraites sur DOCX avec propriétés | title + author présents | *(à compléter)* |
| Aplatissement JSON 3+ niveaux | clés complètes visibles dans le texte | *(à compléter)* |
| Fine-tuning CPU (TinyLlama 1.1B) | loss décroissante, adapter sauvegardé | ✅ VALIDÉ |
| Export GGUF + import Ollama | model.gguf créé, modèle dans `ollama list` | ✅ VALIDÉ |
| Entraînement incrémental (`--resume-adapter`) | loss part plus basse, adapter conservé | *(à compléter)* |
| Reset pipeline (`--reset`) | adaptateur supprimé, entraînement initial relancé | *(à compléter)* |
