# Audit du processus de fine-tuning — Spectra

> Audit réalisé le 2026-06-27 sur la branche `claude/fine-tuning-audit-j9qy7j`.
> Ce document recense les anomalies relevées à chaque étape de la chaîne de
> fine-tuning, les optimisations possibles, et l'état des correctifs appliqués.

## Vue d'ensemble

La chaîne de fine-tuning de Spectra se décompose en :

1. **Génération du dataset SFT** — `DatasetGeneratorService` interroge le LLM sur
   chaque chunk ChromaDB pour produire des paires Q/R, résumés et classifications.
2. **Génération DPO (optionnelle)** — `DpoGenerationService` produit des triplets
   `{prompt, chosen, rejected}`.
3. **Export / filtrage** — `DatasetExportService` (et l'export interne du
   `FineTuningService`) sérialisent les paires en JSONL.
4. **Orchestration de l'entraînement** — `FineTuningService` lance un script externe.
5. **Entraînement** — `scripts/train.sh` (voie API) ou `scripts/train_host.py`
   (voie CLI `pipeline.sh`/`pipeline.bat`).
6. **Export GGUF + enregistrement** — `scripts/export_gguf.py`.

### Constat structurant

Il existait **deux pipelines divergents** :

| | Voie API web | Voie CLI |
|---|---|---|
| Déclencheur | `FineTuningController` → `FineTuningService` | `pipeline.sh` / `pipeline.bat` |
| Script | `scripts/train.sh` | `scripts/train_host.py` |
| Export GGUF | absent | `scripts/export_gguf.py` |
| Packing / DPO / reprise | ignorés | gérés |

Templates de chat différents, modèles différents, capacités différentes. Cette
divergence était à l'origine de la majorité des anomalies. Le correctif principal
consiste à **unifier les deux voies sur un moteur unique** (`train_host.py`),
`train.sh` devenant un simple adaptateur.

---

## 🔴 Anomalies bloquantes

### A1 — `train.sh` n'exportait pas ses variables (arguments perdus)
`scripts/train.sh` affectait `DATASET_PATH="$1"`… puis un heredoc Python lisait
`os.environ.get("DATASET_PATH")`. Les variables shell n'étant **pas exportées**, le
sous-processus Python ne les voyait jamais et retombait sur ses valeurs par défaut
(`dataset=None`, `base_model="mistral"`, `rank=64`…). La voie API était donc
**inopérante**.
**Correctif** : `train.sh` réécrit en adaptateur qui passe les arguments en argv à
`train_host.py` (plus aucune dépendance aux variables d'environnement).

### A2 — Loss factice remontée à l'UI
`scripts/train.sh` imprimait `epoch=N loss=0.0` dans une boucle **avant**
`trainer.train()`. C'est exactement ce que parse `FineTuningService.parseTrainingOutput`.
La progression affichée était donc **fabriquée**.
**Correctif** : `train_host.py` émet la vraie loss via `ProgressLogger`
(`epoch=2.00 loss=0.4523`), captée par le parseur Java.

### A3 — Artefact attendu ≠ artefact produit
Java attendait un **fichier** `adapter.gguf` (`Files.exists(...)`) alors que la voie
CPU produit un **répertoire** d'adaptateur PEFT.
**Correctif** : l'artefact de succès est désormais
`adapter/adapter_config.json` (présent quel que soit le backend).

### A4 — Modèle de base invalide pour la voie API
`baseModel` par défaut = nom du modèle llama-server actif (ex. `phi3`). L'ancien
`MODEL_MAP` de `train.sh` ne connaissait que `mistral`/`llama3`.
**Correctif** : délégation à `train_host.py`, dont le `MODEL_MAP` gère
`phi3`/`tinyllama`/`mistral`/`llama3`.

### A5 — Packing et DPO ignorés (voie API)
`FineTuningService.runTrainingProcess` ne transmettait que 7 arguments positionnels.
`packingEnabled` / `dpoEnabled` (présents dans `FineTuningRequest` et les recettes)
n'étaient **jamais** passés. La recette « DPO Alignement » lançait en réalité un SFT.
**Correctif** : transmission de `packing` (`$8`) et `dpo` (`$9`) ; `train.sh` les
convertit en `--packing` / `--dpo`.

### A6 — Le DPO recevait le mauvais fichier de dataset
La voie CLI vérifiait l'existence de paires DPO puis passait l'export **SFT**
(format `conversations`) à `train_host.py --dpo`, qui attend
`{prompt, chosen, rejected}` → plantage. La voie API exportait également des paires
SFT pour un job DPO.
**Correctif** :
- Nouvel endpoint `POST /api/dataset/dpo/export` (+ `DpoGenerationService.exportJsonl`).
- `pipeline.sh`/`.bat` exportent et passent le fichier DPO quand `--dpo`.
- `FineTuningService.exportDpoDataset` sérialise les `DpoPair` quand `dpoEnabled`.
- `train_host.py` valide la présence des clés `prompt/chosen/rejected` et échoue
  avec un message clair sinon.

---

## 🟠 Anomalies de qualité d'entraînement (`train_host.py`)

### B1 — Le collator écrasait les labels et cassait le masquage
Les datasets fournissaient déjà `labels`, mais un `DataCollatorForLanguageModeling`
les **recalculait** (`labels = input_ids.clone()`, puis `pad_token_id → -100`).
Conséquences :
- Le masquage `-100` du `PackedDataset` (séparation des séquences) était **perdu**.
- `pad_token == eos_token` ⇒ **tous les EOS réels masqués** ⇒ le modèle n'apprenait
  jamais à s'arrêter.
**Correctif** : suppression du collator, remplacé par `default_data_collator` qui
empile les tenseurs **sans** toucher aux labels.

### B2 — La loss couvrait tout le prompt (SFT)
`labels = input_ids.clone()` sur toute la séquence ⇒ le modèle apprenait à
régénérer aussi le prompt système et la question.
**Correctif** : `ConversationDataset` tokenise tour par tour et masque le prompt
(`-100`) ; seuls les tokens de la réponse de l'assistant (EOS compris) sont
supervisés.

### B3 — Templates de chat incohérents entre les deux scripts
`train_host.py` plaçait `<|assistant|>` dans le tour *user* ; `train.sh` le plaçait
dans le tour *assistant*.
**Correctif** : `train.sh` délègue à `train_host.py` ⇒ un seul template.

### B4 — `ref_model = copy.deepcopy(model)` en DPO
Dupliquer toute la politique PEFT doublait l'empreinte mémoire.
**Correctif** : `ref_model=None` — `DPOTrainer` désactive l'adaptateur pour obtenir
la référence (base figée).

### B5 — Incohérence du modèle de base entre entraînement et export
`train_host.py` mappait `phi3 → TinyLlama` (substitution CPU) tandis que
`export_gguf.py` mappait `phi3 → microsoft/Phi-3-mini`. Fusionner un adaptateur
TinyLlama sur Phi-3 fait échouer `merge_and_unload` (dimensions incompatibles).
**Correctif** : `MODEL_MAP` aligné dans `export_gguf.py` (`phi3 → TinyLlama`), avec
un commentaire imposant de garder les deux tables identiques.

---

## 🟡 Optimisations

### C1 — Hyperparamètres des recettes ignorés (voie CLI) — *corrigé*
`pipeline.sh`/`.bat` codaient en dur `--epochs 1 --lora-rank 8` et ne passaient ni
`lora_alpha` ni `lr`.
**Correctif** : variables surchargeables `EPOCHS`, `LORA_RANK`, `LORA_ALPHA`, `LR`
(défauts CPU rapides), toutes transmises à `train_host.py`.

### C2 — Cycle de vie des jobs déséquilibré — *corrigé*
`cleanupOldJobs` supprimait les jobs **COMPLETED** de la base après 1 h (perte de la
référence `outputPath`) mais ne nettoyait jamais les répertoires sur disque (fuite).
**Correctif** : seuls les jobs **FAILED** sont purgés, et leur répertoire de travail
est supprimé ; les jobs COMPLETED (qui contiennent l'adaptateur produit) sont
conservés.

### C3 — Padding statique à 512 — *non corrigé (noté)*
`ConversationDataset` rembourre chaque exemple à `max_length` (`padding` fixe). Sur
CPU, beaucoup de calcul est gaspillé sur du padding. Le mode `--packing` atténue le
problème. Piste : padding dynamique au niveau du collator.

### C4 — La « confiance » du dataset est un simple test de longueur — *noté*
`parseQaPair`/`parseSummaryPair` fixent `confidence=0.9` si la question/réponse
dépasse un seuil de caractères, sinon `0.4`. Avec `minConfidence=0.8` par défaut,
toute paire courte est jetée, sans mesure réelle de qualité. Pistes : score basé sur
la cohérence LLM-juge, l'ancrage dans le chunk, ou un filtrage de redondance.

### C5 — Pas de déduplication ni de split train/val — *noté*
La génération ne déduplique pas les paires et n'isole pas de jeu de validation ;
`save_strategy="no"` interdit tout checkpoint/early-stopping. Pistes : split
train/val, `EarlyStoppingCallback`, déduplication par similarité.

### C6 — `attn_implementation="eager"` — *noté*
`sdpa` est plus rapide quand disponible (voie CPU non-Unsloth).

### C7 — `gradient_checkpointing` désactivé — *noté*
À activer pour les modèles 7-8B (Mistral/Llama3) afin de réduire l'empreinte mémoire.

### C8 — Troncature pouvant supprimer toute la réponse — *noté*
Pour un prompt très long, la troncature à `max_length` peut retirer l'intégralité de
la réponse de l'assistant, produisant un exemple entièrement masqué. Piste :
tronquer en priorité le prompt, ou ignorer ces exemples après troncature.

### C9 — `PackedDataset` : loss pleine séquence — *par conception*
Le mode packing supervise toute la séquence concaténée (prompts inclus). C'est un
compromis débit/qualité assumé pour un mode optionnel ; documenté ici pour mémoire.

---

## Récapitulatif des fichiers modifiés

| Fichier | Nature du correctif |
|---|---|
| `scripts/train_host.py` | Masquage du prompt (B2), collator par défaut (B1), `ref_model=None` + validation DPO (B4, A6) |
| `scripts/train.sh` | Réécrit en adaptateur de `train_host.py` (A1, A2, A4, B3, A5) |
| `scripts/export_gguf.py` | `MODEL_MAP` aligné sur `train_host.py` (B5) |
| `src/.../FineTuningService.java` | Flags packing/dpo (A5), artefact répertoire (A3), export DPO (A6), chemin script absolu, cleanup disque (C2) |
| `src/.../DpoGenerationService.java` | `exportJsonl()` (A6) |
| `src/.../controller/DatasetController.java` | Endpoint `POST /api/dataset/dpo/export` (A6) |
| `pipeline.sh` / `pipeline.bat` | Hyperparamètres surchargeables (C1), bon fichier DPO (A6) |

## Reste à faire (non bloquant)

- C3 (padding dynamique), C4 (scoring de confiance réel), C5 (split val + checkpoints),
  C6 (`sdpa`), C7 (`gradient_checkpointing`), C8 (troncature côté prompt).
- Renommer l'alias `phi3` (qui pointe en réalité vers TinyLlama sur CPU) pour lever
  l'ambiguïté côté utilisateur.
