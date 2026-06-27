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

### C3 — Padding statique à 512 — *corrigé*
`ConversationDataset` rembourrait chaque exemple à `max_length`. Désormais les datasets
renvoient des séquences de **longueur variable** et un collator à **padding dynamique**
(`make_pad_collator`) rembourre au plus long du batch. À `batch_size=1`, il n'y a plus
aucun padding — gain de calcul important sur CPU. `PackedDataset` ne pré-rembourre plus
non plus.

### C4 — Confiance ancrée dans la source — *corrigé*
`parseQaPair`/`parseSummaryPair` ne se contentent plus d'un test de longueur :
`groundedConfidence` mesure la part des mots de contenu de la réponse présents dans le
chunk d'origine (proxy d'ancrage / anti-hallucination) et la combine à une base
(`0.6 + 0.4·ancrage`). Une réponse bien formée et bien ancrée tend vers `1.0` ; bien
formée mais peu ancrée (donc potentiellement inventée) reste vers `0.6` et passe sous
les seuils stricts (0.85 / 0.9) des recettes.

### C5 — Déduplication + split validation — *corrigé*
- **Déduplication** : `DatasetGeneratorService.deduplicate()` retire les paires en
  double (même instruction + réponse) avant persistance, pour ne pas sur-pondérer un
  contenu répété.
- **Split validation** : `train_host.py --val-split <fraction>` tient un jeu à l'écart et
  active `eval_strategy="epoch"` ; l'`eval_loss` est journalisée par époque pour détecter
  le sur-apprentissage (`VAL_SPLIT` exposé par les pipelines, défaut 0 = désactivé).
  Le checkpointing/early-stopping reste désactivé volontairement (datasets CPU petits,
  pas de churn disque).

### C6 — Attention SDPA — *corrigé*
`attn_implementation="sdpa"` (plus rapide) avec repli automatique sur `eager` pour les
architectures non supportées (voie CPU non-Unsloth).

### C7 — `gradient_checkpointing` — *corrigé*
Activé **uniquement sur GPU non-Unsloth** (`gradient_checkpointing=has_gpu and not
USE_UNSLOTH`, `use_cache=False`, `enable_input_require_grads()`), pour réduire la
mémoire des modèles 7-8B. Désactivé sur CPU où il ne ferait que ralentir.

### C8 — Troncature préservant la réponse — *corrigé*
`ConversationDataset._fit` supprime d'abord les tokens de prompt (non supervisés) en
tête lorsqu'une séquence dépasse `max_length`, préservant la réponse de l'assistant ;
les exemples dont la réponse disparaît entièrement sont ignorés.

### C9 — `PackedDataset` : loss pleine séquence — *par conception*
Le mode packing supervise toute la séquence concaténée (prompts inclus). C'est un
compromis débit/qualité assumé pour un mode optionnel ; documenté ici pour mémoire.

### C10 — Alias `phi3` trompeur + `target_modules` LoRA codés en dur — *corrigé*
`phi3` pointait en réalité vers TinyLlama, et les `target_modules`
(`q_proj/k_proj/v_proj/o_proj`, style Llama) étaient codés en dur — ce qui aurait fait
**échouer** LoRA sur l'attention fusionnée `qkv_proj` de Phi-3 (bug latent que la
substitution TinyLlama masquait).
**Correctif** :
- `MODEL_MAP` honnête (identique dans `train_host.py` et `export_gguf.py`) :
  `tinyllama` = défaut CPU léger (1.1B), `phi3` = vrai Phi-3-mini (3.8B).
- `find_target_modules()` auto-détecte les projections d'attention présentes
  (Llama → `q/k/v/o_proj`, Phi-3 → `qkv_proj/o_proj`), avec repli style Llama.
- Recette `cpu-rapide` bascule sur `tinyllama` (comportement préservé : c'est le
  modèle qui était réellement chargé) ; `gpu-qualite` / `dpo-alignement` gardent `phi3`,
  désormais honnête.

---

## Récapitulatif des fichiers modifiés

| Fichier | Nature du correctif |
|---|---|
| `scripts/train_host.py` | Masquage du prompt (B2), collator par défaut (B1), `ref_model=None` + validation DPO (B4, A6), padding dynamique (C3), troncature préservant la réponse (C8), SDPA (C6), gradient checkpointing GPU (C7), `--val-split` (C5), `MODEL_MAP` honnête + `find_target_modules` (C10) |
| `scripts/train.sh` | Réécrit en adaptateur de `train_host.py` (A1, A2, A4, B3, A5) |
| `scripts/export_gguf.py` | `MODEL_MAP` aligné et honnête (B5, C10) |
| `src/.../recipes/cpu-rapide.yml` | `baseModel: tinyllama` (C10) |
| `src/.../FineTuningService.java` | Flags packing/dpo (A5), artefact répertoire (A3), export DPO (A6), chemin script absolu, cleanup disque (C2) |
| `src/.../DpoGenerationService.java` | `exportJsonl()` (A6) |
| `src/.../dataset/DatasetGeneratorService.java` | Confiance ancrée (C4), déduplication (C5) |
| `src/.../controller/DatasetController.java` | Endpoint `POST /api/dataset/dpo/export` (A6) |
| `pipeline.sh` / `pipeline.bat` | Hyperparamètres surchargeables `EPOCHS/LORA_RANK/LORA_ALPHA/LR/VAL_SPLIT` (C1, C5), bon fichier DPO (A6) |

## Reste à faire (non bloquant)

- Éventuel early-stopping / checkpointing du meilleur modèle (volontairement écarté ici
  pour éviter le churn disque sur de petits datasets CPU).
- Étendre LoRA aux projections MLP (`gate/up/down_proj`) pour les gros modèles si la
  qualité le justifie (actuellement attention seule, conforme à l'existant).
