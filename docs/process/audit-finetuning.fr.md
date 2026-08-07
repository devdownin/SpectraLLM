# Audit — Fine-tuning

> Audit du 2026-07-29. Périmètre : la chaîne complète de fine-tuning, de la soumission d'un job
> à la mise en service du modèle. Croisement code backend (`FineTuningService`,
> `FineTuningController`, `FineTuningRequest`, `BaseModelCatalog`, `DpoGenerationService`,
> `ArticleCommentService`), moteur d'entraînement (`scripts/train.sh`, `scripts/train_host.py`,
> `scripts/export_gguf.py`, `scripts/requirements.txt`), frontend (`pages/FineTuning.tsx`),
> déploiement (`deploy/docker/`, `deploy/k8s/`) et documentation (`docs/tech/technical-doc.fr.md`).
>
> **Constat général.** L'orchestration Java est la partie la plus solide de la chaîne :
> machine à états explicite, verrou d'unicité, annulation coopérative, réconciliation au
> démarrage, traçabilité GED. Les problèmes sont ailleurs, et ils sont structurels. D'une part
> le **moteur d'entraînement n'est physiquement pas joignable** dans le mode de déploiement
> documenté en page d'accueil (`docker compose up`) : ni le script, ni Python ne sont présents
> dans l'image `spectra-api`. D'autre part, quand il est joignable, il **entraîne sur un format
> de conversation qui ne correspond qu'à un seul des quatre modèles de base du catalogue** — pas
> celui configuré par défaut. Le fine-tuning est la fonctionnalité mise en avant comme
> différenciateur dans le README ; au moment de l'audit, elle n'était vérifiable ni en
> déploiement, ni en qualité de résultat.
>
> Aucun test automatisé ne couvrait le moteur d'entraînement au moment de l'audit : les deux
> seuls tests (`FineTuningGedTraceTest`, `FineTuningSftExclusionTest`) portaient sur des méthodes
> périphériques de `FineTuningService`, et il n'existait aucun test Python pour `train_host.py`.
>
> **Statut.** Les constats **F2 à F11** sont **corrigés** ; ils restent décrits ci-dessous pour la
> traçabilité, avec la correction apportée. **F1 et F10 ont été fermés par le lot 4a** du
> [plan de migration](plan-migration-java.fr.md) : la soumission refuse désormais par un 503
> motivé quand aucun exécuteur n'est disponible, et les arguments positionnels sont devenus un
> `TrainingSpec` nommé.
>
> **F1 est désormais clos pour de bon.** Le lot 4a avait rendu l'échec honnête (503 motivé au
> lieu d'une `IOException` à mi-course) et le lot 4b avait fourni l'exécuteur manquant (service
> `spectra-trainer`, `HttpTrainingRunner`). Restait le dernier maillon, purement opératoire, et
> c'est celui que les utilisateurs rencontraient : **aucun chemin de lancement documenté ne
> démarrait le trainer**. `start.sh` ignorait le profil `trainer` — que `stop.sh` connaissait
> pourtant — et n'avait aucun moyen de basculer le runner. Un `docker compose up` nominal
> donnait donc « script d'entraînement introuvable : /app/scripts/train.sh », pour un script
> présent dans le dépôt et parfaitement exécutable. `./scripts/start.sh --trainer` pose
> maintenant les deux réglages ensemble ; le motif de refus nomme cette commande.
>
> La **documentation désynchronisée du §6 est corrigée** : le mode « Simulation » (qui n'existe
> pas) et le montage `./scripts` (qui n'existe pas non plus) ont disparu de la doc technique, du
> diagramme C4 et de `llama-cpp.fr.md`.
>
> **Tous les constats sont clos.** F12 (accès sortant à l'export) et le champ mort
> `reportPath` du §6 l'ont été en dernier.
> **F14 est clos** : une part du dataset est générée sous la forme réellement servie.
> **F13 est clos** : l'orchestration — machine à états, verrou d'unicité, annulation — est
> désormais couverte de bout en bout par `FineTuningOrchestrationTest`. Aucun n'est bloquant.
>
> **Suite.** Le **suivi du processus dans l'interface** — barre d'étapes, courbe de perte, flux de
> télémétrie, restitution des échecs, pilotage — fait l'objet d'un audit distinct :
> [audit-suivi-finetuning-ui.fr.md](audit-suivi-finetuning-ui.fr.md). Plusieurs points mineurs
> listés au §7 y sont repris et instruits (réponse de création mal typée, message 409 perdu, ORPO
> sans interface, volume des logs SSE).

---

## 1. Blocages de déploiement

### F1 — Le script d'entraînement est absent de l'image `spectra-api` — **bloquant** — ✅ corrigé

> **Corrigé par le lot 4a** (cf. [`plan-migration-java.fr.md`](plan-migration-java.fr.md)).
> L'absence de Python dans l'image reste vraie et **assumée** : ce qui est corrigé, c'est le
> mensonge. `TrainingRunner.isAvailable()` est consulté **avant** d'accepter la soumission ;
> quand aucun exécuteur n'est en état de travailler, l'API répond **503 avec la raison en
> clair** (`TrainingUnavailableException`), sans créer de job, sans générer de jeu de données et
> sans prendre le verrou d'entraînement. Une fonctionnalité explicitement absente au lieu d'une
> fonctionnalité qui échoue à mi-course.
>
> Le mode hôte est inchangé (`ProcessTrainingRunner`). Fournir l'entraînement *en conteneur*
> reste à faire — c'est le lot 4b, dont l'urgence a baissé depuis le retrait de Kubernetes.
>
> Énoncé d'origine conservé ci-dessous.

`FineTuningService` résout `spectra.fine-tuning.script` (défaut `./scripts/train.sh`) en chemin
absolu dans le constructeur, puis l'exécute via `ProcessBuilder` avec `workDir` comme répertoire
courant. Dans un conteneur, cela donne `/app/scripts/train.sh`. Or :

- `deploy/docker/Dockerfile` — l'étage runtime est `eclipse-temurin:25-jre` et ne copie que
  `app.jar` (plus l'installation de `llmfit`). **Pas de `scripts/`, pas de Python, pas de
  `torch`/`transformers`/`peft`.**
- `deploy/docker/docker-compose.yml` — le service `spectra-api` ne monte que `./data:/app/data`.
  Il n'y a **aucun montage de `./scripts`**.
- `deploy/k8s/base/07-spectra-api.yaml` — mêmes constats : volumes `documents`, `dataset`,
  `fine-tuning`, `models`, aucun volume de scripts.

Conséquence : tout job lancé depuis l'UI passe `PENDING → EXPORTING_DATASET → TRAINING`, puis
`pb.start()` lève une `IOException` (« No such file or directory »), rattrapée par le `catch`
générique de `runAsync`, et le job finit `FAILED` avec un message d'erreur d'`IOException` brut.
La fonctionnalité phare du produit — « QLoRA fine-tuning ✅ » dans le tableau comparatif du
README — n'est **pas exécutable** par le chemin d'installation en une commande que le README
recommande.

La documentation, elle, décrit un montage qui n'existe pas :

- `docs/tech/technical-doc.fr.md:1749` — `- ./scripts:/app/scripts   ← train.sh accessible depuis FineTuningService`
- `docs/tech/c4-level-2-containers.fr.md:42` — « Volume monté : ./scripts »

Le diagramme C4 et le compose réel ne décrivent donc pas le même système. C'est probablement la
raison pour laquelle l'écart n'a pas été détecté.

Recommandation : décider explicitement du modèle d'exécution, puis l'aligner partout.
Deux options cohérentes, à ne pas mélanger :

1. **Entraînement dans le conteneur** — monter `./scripts` dans `spectra-api` et construire une
   image disposant de Python et de la stack d'entraînement (idéalement une image dédiée, l'image
   JRE ne devant pas embarquer `torch`).
2. **Entraînement sur l'hôte** — assumer que `train.sh` s'exécute hors conteneur, et alors
   documenter que le fine-tuning n'est pas disponible en mode Docker, ou introduire un service
   `spectra-trainer` séparé que `FineTuningService` appelle par HTTP plutôt que par
   `ProcessBuilder`.

Tant que ce n'est pas tranché, la voie la moins coûteuse est de **détecter l'absence du script au
démarrage** et de refuser les soumissions avec un message actionnable (HTTP 503 + explication),
au lieu de laisser chaque job échouer à mi-course sur une `IOException`.

### F2 — `scripts/train.sh` n'a pas le bit exécutable — **bloquant (mode hôte)** — ✅ corrigé

`git ls-files -s scripts/train.sh` renvoie le mode **`100644`**. `FineTuningService.runProcess`
construit pourtant `ProcessBuilder(List.of(trainingScript, …))`, c'est-à-dire une **exécution
directe du fichier**, pas une invocation par un interpréteur. Sur un clone frais Linux/macOS, le
premier job échoue donc sur `error=13, Permission denied` — y compris dans le seul mode où le
script est présent.

Le dépôt sait pourtant gérer ce point ailleurs : `deploy/docker/Dockerfile.llama:26` fait
explicitement `chmod +x` sur ses entrypoints, et `gke-*.sh`, `llm-chat-entrypoint.sh`,
`check-doc-links.py` sont bien en `100755`. `train.sh` (comme `start.sh`, `setup.sh`,
`pipeline.sh`) est resté en 644, et rien nulle part ne le `chmod`.

**Correction appliquée** : `scripts/train.sh` passe en `100755`.

Reste ouvert : invoquer le script via un interpréteur explicite côté Java (`bash <script> …`)
plutôt que de dépendre du bit de permission serait plus robuste, et réglerait du même coup
l'impossibilité d'exécuter un `.sh` sous Windows, alors que le README annonce un support Windows
(`scripts\start.bat`). C'est un changement de comportement de `FineTuningService`, donc hors du
lot « correctifs sans risque ».

---

## 2. Correction de l'entraînement

### F3 — Le gabarit de conversation est codé en dur et ne correspond qu'à 1 modèle sur 4 — **critique** — ✅ corrigé

`train_host.py` construisait les exemples avec un gabarit littéral, écrit dans
`ConversationDataset._encode` et dupliqué dans `PackedDataset._format` :

```
<|system|>\n{content}</s>\n
<|user|>\n{content}</s>\n<|assistant|>\n
{content}</s>\n
```

C'est le gabarit **Zephyr**, celui de `TinyLlama-1.1B-Chat-v1.0`. Confronté au catalogue
`scripts/base_models.json` :

| Alias | Gabarit réel du modèle | Verdict |
|---|---|---|
| `tinyllama` | `<|system|>…</s>` (Zephyr) | ✅ correspond |
| `phi3` (**défaut**, `application.yml:172`) | `<|system|>…<|end|>` — terminateur `<\|end\|>`, pas `</s>` | ❌ |
| `mistral` | `[INST] … [/INST]` — `<\|user\|>` n'existe pas dans le vocabulaire | ❌ |
| `llama3` | `<\|start_header_id\|>…<\|eot_id\|>` — `</s>` n'existe pas dans le vocabulaire | ❌ |

Le service, lui, passe par `LlamaCppChatClient` → `/v1/chat/completions`, endpoint qui applique
le **gabarit embarqué dans le GGUF** (hérité du `tokenizer_config.json` du modèle fusionné).
Aucune surcharge `--chat-template` n'existe dans le dépôt (`grep` sur `chat-template`,
`chat_template`, `--jinja` : zéro occurrence). Entraînement et service divergent donc pour trois
des quatre bases supportées — dont celle configurée par défaut.

Les conséquences ne sont pas cosmétiques :

- **Sur `phi3`**, le modèle apprend à terminer ses réponses par `</s>` alors que llama-server
  s'arrête sur `<|end|>`. Le symptôme attendu est une **génération qui ne s'arrête pas**, le
  modèle enchaînant sur des tours inventés.
- **Sur `mistral`/`llama3`**, les marqueurs de rôle sont tokenisés comme du texte ordinaire : le
  modèle n'apprend aucune structure de dialogue exploitable au service, et l'EOS n'est jamais
  supervisé.

**Correction appliquée** : la mise en forme est extraite dans `scripts/chat_format.py` — module
voisin importable sans torch, sur le modèle de `base_models.py` — et délègue à
`tokenizer.apply_chat_template()`. Le masque de supervision est dérivé de la position réelle du
segment assistant : pour chaque tour, la conversation est tokenisée deux fois, avec et sans ce
tour (prompt de génération inclus), et le **préfixe commun** aux deux tokenisations marque la
frontière prompt / réponse. Cette construction reste correcte quel que soit le gabarit — y
compris à marqueurs fusionnés — et gère les dialogues multi-tours.

Trois cas limites traités explicitement :

- **Tokenizer sans gabarit** → repli sur le format littéral historique, avec un avertissement
  imprimé au démarrage de l'entraînement (le silence était précisément le problème).
- **Gabarit refusant le rôle système** (Mistral-Instruct lève une exception) → la persona est
  fusionnée dans le premier tour utilisateur. Sans ce repli, un dataset qui commence toujours par
  la persona serait intégralement inexploitable sur ce modèle.
- **Rendu non incrémental** → le préfixe commun dégrade proprement au lieu de produire un
  masque faux.

Vérifié : sur un gabarit de style Phi-3, le segment supervisé est exactement
`REPONSE<|end|>` — la réponse **et le terminateur de tour du modèle**. C'est le cœur du
correctif : le modèle apprend enfin à émettre le jeton sur lequel llama-server s'arrête.

Couvert par `scripts/tests/test_chat_format.py` (19 tests, sans GPU ni téléchargement de modèle),
exécuté par un nouveau job CI `training-scripts`.

### F4 — Le mode packing désactive silencieusement le masquage du prompt — **critique** — ✅ corrigé

`ConversationDataset` masque soigneusement le prompt à `-100` (le commentaire de classe explique,
à juste titre, que sans cela « le modèle apprend à régénérer la question au lieu de seulement
répondre »). Mais `PackedDataset._flush` (ligne 237-241) écrit :

```python
"labels": torch.tensor(list(buffer), dtype=torch.long),
```

soit **la séquence entière**, tours système et utilisateur compris. Cocher « packing » dans l'UI
change donc l'objectif d'apprentissage — de « apprendre à répondre » à « apprendre à reproduire
toute la conversation » — sans avertissement nulle part, alors que la case est présentée comme
une simple optimisation de débit (« 20-40 % de réduction du nombre d'étapes »).

Écart secondaire du même ordre : `PackedDataset` tokenise avec `add_special_tokens=True`,
`ConversationDataset` avec `add_special_tokens=False`. Le BOS est donc présent dans un mode,
absent dans l'autre, et présent au service — troisième variante de distribution.

**Correction appliquée** : `PackedDataset` réutilise `ConversationDataset._encode` et `._fit`,
puis concatène des paires `(input_ids, labels)` **déjà masquées**. Le séparateur EOS entre deux
exemples n'appartient à aucune réponse : il est inséré avec un label `-100`. La re-tokenisation
du texte brut disparaît, ce qui harmonise du même coup `add_special_tokens` entre les deux
chemins et supprime `_format`, devenu inutile.

Vérifié : sur un dataset de 4 exemples identiques, les deux modes supervisent la même proportion
de tokens (~18 %) ; avant correction le mode packé en supervisait 100 %.

### F5 — Le chemin GPU (Unsloth) ignore `--max-length` — **moyen** — ✅ corrigé

`train_host.py:302-306` :

```python
model, tokenizer = FastLanguageModel.from_pretrained(
    model_name=hf_model, max_seq_length=512, load_in_4bit=True,
)
```

`512` est en dur, alors que `MAX_SEQ_LENGTH = args.max_length` est respecté partout ailleurs.
Sur GPU — c'est-à-dire dans le seul cas où allonger le contexte a un intérêt pratique —
`SPECTRA_TRAIN_MAX_LENGTH` n'a donc aucun effet.

**Correction appliquée** : `MAX_SEQ_LENGTH` est défini juste après le parsing des arguments,
avant le chargement du modèle, et utilisé par les deux backends — une seule source pour tous les
chemins.

### F6 — `loraAlpha` et `learningRate` ne sont jamais envoyés par l'UI — **moyen** — ✅ corrigé

`FineTuning.tsx` ne soumet que `modelName`, `baseModel`, `epochs`, `loraRank`, `minConfidence` et
les drapeaux (`onFormSubmit`, ligne 399-402 : `fineTuningApi.createJob(data)` avec `data` = les
champs du formulaire). Le formulaire n'expose ni `loraAlpha`, ni `learningRate`, ni `orpoEnabled`.

`FineTuningRequest` applique alors ses défauts : `loraAlpha = 128`, quel que soit le rang choisi.
Le facteur d'échelle LoRA (`alpha / r`) dérive donc avec le rang :

| Rang choisi dans l'UI | alpha appliqué | Échelle effective |
|---|---|---|
| 64 (défaut) | 128 | 2 — conventionnel |
| 16 | 128 | 8 |
| 8 | 128 | 16 |
| 4 (minimum autorisé) | 128 | 32 |

Baisser le rang pour « entraîner plus léger » multiplie donc l'amplitude effective de
l'adaptation par 16, ce qui est le contraire de l'intention et une cause classique
d'instabilité. Le même écran fait pourtant le bon calcul ailleurs :
`exportRecipe` (ligne 277) envoie `loraAlpha: formValues.loraRank * 2`. Les deux chemins de la
même page ne sont pas d'accord entre eux.

**Correction appliquée** : le défaut de `loraAlpha` est dérivé du rang dans le compact
constructor de `FineTuningRequest` (`Math.min(2 * loraRank, 512)`, la borne conservant le domaine
de `@Max(512)`). L'échelle vaut donc 2 quel que soit le rang, y compris pour les appels API qui
omettent le champ, et le chemin `exportRecipe` de l'UI redevient cohérent avec le chemin de
soumission. Couvert par `FineTuningRequestTest`.

Reste ouvert : `learningRate` n'est toujours pas exposé par le formulaire (valeur effective
toujours 2e-4), et `orpoEnabled` n'a toujours aucun contrôle dans l'UI — ce sont des ajouts
d'interface, pas des correctifs.

---

## 3. Câblage des fonctionnalités

### F7 — L'apprentissage continu à partir des commentaires approuvés n'entraîne pas sur ces commentaires — **élevé** — ✅ corrigé

`ArticleCommentService.triggerRetraining` (ligne 189-205) :

1. exporte les paires DPO issues des commentaires vers `data/dataset/comments_dpo.jsonl` ;
2. soumet un job `dpoEnabled = true`.

Or `FineTuningService.exportDpoDataset` ne lit pas ce fichier : il appelle
`dpoGenerator.getAllPairs()`, qui agrège `dpo_pairs.jsonl` (génération DPO) et
`dpo_preference_pairs.jsonl` (préférences A/B du Playground). **`comments_dpo.jsonl` n'est lu par
personne** dans tout le backend.

Le job entraîne donc sur des paires sans rapport avec les commentaires qui l'ont déclenché — et
si les deux autres fichiers sont vides, il échoue sur « Aucune paire de préférence disponible ».
La boucle « DPO / continuous learning » annoncée dans le tableau comparatif du README ne se
referme pas.

Deux défauts aggravants sur le même bloc :

- **Le retour `null` de `submit()` n'est pas traité.** `submit()` renvoie `null` quand un
  entraînement tourne déjà (contrat documenté, traduit en 409 par le contrôleur). Ici le résultat
  est directement journalisé : `log.info("Re-entraînement DPO automatique soumis : … jobId={}")`
  affiche `jobId=null` comme un succès. `BatchService:96-99` gère correctement ce cas — la
  divergence est locale à `ArticleCommentService`.
- **`exportGguf` vaut `false`.** L'appel passe `null` en dernier argument, donc le défaut `false`.
  Même en cas de succès, le ré-entraînement automatique produit un adaptateur PEFT jamais
  fusionné, jamais converti, jamais enregistré — donc jamais servi. Un cycle d'apprentissage
  continu qui ne met rien en service n'a pas d'effet observable.

**Correction appliquée** :

- La construction des paires est extraite dans `buildDpoPairs()`, et ses paires sont désormais
  **enregistrées dans `DpoGenerationService`** (via `addPreferencePair`, l'ingress déjà utilisé
  par les préférences A/B du Playground) en plus d'être exportées dans `comments_dpo.jsonl`.
  C'est ce registre — et lui seul — que lit `FineTuningService.exportDpoDataset`.
- `addPreferencePair` devient **idempotent** : une paire identique déjà enregistrée est ignorée.
  Sans cela, le déclenchement automatique repartant à chaque cycle de l'ensemble des
  commentaires aurait surpondéré les mêmes paires à chaque ré-entraînement.
- Le `null` de `submit()` est traité : le job est signalé comme différé (les paires restent
  enregistrées pour le prochain déclenchement) au lieu d'être journalisé comme un succès.
- `exportGguf = true` : le modèle produit est fusionné, converti et enregistré, donc réellement
  servable.

Couvert par `ArticleCommentAutoRetrainTest`.

### F8 — Trois formats de prompt différents entre SFT, DPO et service — **élevé** — ✅ corrigé

| Étape | Format du prompt |
|---|---|
| SFT (`train_host.py`) | `<\|system\|>\n…</s>\n<\|user\|>\n…</s>\n<\|assistant\|>\n` |
| DPO (`DpoGenerationService:273`) | `system + "\n\n" + user` — texte brut, aucun marqueur |
| Service (`LlamaCppChatClient`) | gabarit embarqué dans le GGUF |

TRL n'applique aucun gabarit à un `prompt` déjà sous forme de chaîne : la phase DPO optimise donc
sur une mise en forme qui n'est **ni celle du SFT, ni celle du service**. Un enchaînement
SFT → DPO sur le même adaptateur enseigne successivement deux conventions incompatibles.

**Correction appliquée** : l'export de préférence passe au **format conversationnel de TRL** —
`prompt`, `chosen` et `rejected` sont des listes de messages `{role, content}`. C'est ce format,
et lui seul, qui fait appliquer par TRL le gabarit du modèle : la phase DPO/ORPO utilise donc
désormais exactement la même mise en forme que le SFT et que le service.

Concrètement :

- `DpoGenerationService` ne concatène plus persona et question : le `prompt` d'une paire est la
  **question seule**. La persona est réintroduite à l'export comme message système à part
  entière — la même constante `AssistantPersona.SYSTEM_PROMPT` qu'en SFT et au service.
- `FineTuningService.toConversationalPair` construit la ligne exportée et **normalise les paires
  historiques** dont le prompt embarque la persona concaténée, sinon celle-ci apparaîtrait deux
  fois. Seules les trois clés attendues par TRL sont émises ; `category` et `source` restent des
  métadonnées internes.
- `train_host.py` détecte le format. Si le tokenizer n'a pas de gabarit, TRL ne peut pas rendre le
  conversationnel : la ligne est aplatie avec **le même repli littéral que le SFT**, de sorte que
  les deux phases restent cohérentes entre elles dans tous les cas.

Couvert par `FineTuningDpoExportFormatTest` et par les tests d'aplatissement de
`test_chat_format.py`.

### F9 — Les meilleurs réglages de `train_host.py` sont inaccessibles depuis l'application — **élevé** — ✅ partiellement corrigé

`train_host.py` expose `--val-split`, `--lora-target`, `--neftune-alpha`, `--warmup-ratio` et
`--resume-adapter`. `train.sh` n'en transmet **aucun** : il ne relaie que `--packing`, `--dpo`,
`--orpo` et `--max-length`. Et `FineTuningService.runTrainingProcess` envoie une liste positionnelle
figée de 10 arguments. Ces options ne sont donc atteignables qu'en lançant le script à la main.

Le plus coûteux est `--val-split` : **sans split de validation, il n'existe aucun signal de
sur-apprentissage**. Le seul indicateur remonté à l'UI est la *training loss*, qui décroît par
construction. Sur un dataset synthétique de quelques centaines de paires, avec `epochs` par défaut
à 3 et un maximum autorisé de 50, le sur-apprentissage est le mode de défaillance le plus probable
de toute la chaîne — et c'est précisément celui qu'on ne mesure pas.

`--resume-adapter` mérite aussi d'être signalé : l'entraînement incrémental fait l'objet d'un cas
de test documenté (`docs/process/test-plan-ingestion.fr.md:363`) mais n'est pas exposé par l'API.
Chaque job repart de zéro.

**Correction appliquée** : `valSplit` (défaut 0.1, `0` pour désactiver) est ajouté à
`FineTuningRequest`, relayé en 11ᵉ argument positionnel jusqu'à `train.sh` puis `--val-split`,
et exposé comme curseur dans le formulaire. `parseTrainingOutput` reconnaît désormais
`eval_loss` et le porte dans un champ `evalLoss` du job (colonne `eval_loss`, migration
idempotente dans `schema.sql`) ; `LossChart` trace la courbe de validation en pointillés à côté
de celle d'entraînement — c'est l'écart entre les deux qui signale le sur-apprentissage.

Au passage, un bug latent qui devenait actif dès l'activation de l'évaluation : le motif
`loss[= ]*` reconnaissait aussi la sous-chaîne de `eval_loss=…`, donc l'eval_loss était
enregistrée comme loss d'entraînement. Corrigé par un `(?<!eval_)` côté backend **et** côté
frontend (le même bug existait dans le parseur de logs SSE).

Restent ouverts : `--lora-target`, `--neftune-alpha`, `--warmup-ratio` et surtout
`--resume-adapter` (entraînement incrémental) ne sont toujours pas exposés par l'API.

### F10 — Passage d'arguments positionnel et fragile — **faible** — ✅ corrigé

> **Corrigé par le lot 4a.** `TrainingSpec` remplace les arguments positionnels par des champs
> nommés : un paramètre ne peut plus être inséré au mauvais rang côté service. La traduction en
> ligne de commande est confinée à `ProcessTrainingRunner`, seul endroit où l'ordre compte
> encore, et `ProcessTrainingRunnerTest` le compare argument par argument contre un script
> d'écho. Précision au passage : le contrat de `train.sh` porte **onze** arguments (`$1`…`$11`)
> et non dix, l'en-tête du script faisant foi.
>
> Énoncé d'origine conservé ci-dessous.

`runTrainingProcess` construit une liste de 10 arguments positionnels, que `train.sh` relit en
`$1…${10}` avec des défauts (`PACKING="${8:-false}"`). Insérer un paramètre au milieu décale
silencieusement tous les drapeaux booléens suivants, sans erreur ni au build ni au runtime. La
liste étant déjà construite programmatiquement côté Java, passer à des options nommées
(`--dataset`, `--lora-rank`, …) supprimerait la classe entière de bug, et rendrait F9 trivial à
corriger.

---

## 4. Environnement d'exécution & chaîne d'approvisionnement

### F11 — `scripts/requirements.txt` : bornes ouvertes et dépendances manquantes — **moyen** — ✅ corrigé

```
unsloth>=2026.5.2   trl>=1.4.0   transformers>=5.8.1   datasets>=4.8.5
bitsandbytes>=0.49.2   accelerate>=1.13.0
```

- **Aucune borne supérieure.** `train_host.py` utilise des API qui ont déjà été renommées dans
  ces bibliothèques (`processing_class`, `eval_strategy`, `neftune_noise_alpha`). Une
  installation faite six mois après l'autre n'entraîne pas le même code — et rien ne le
  détecterait, faute de test (voir F13).
- **`torch` et `peft` sont importés directement** par `train_host.py` (`import torch`,
  `from peft import get_peft_model, LoraConfig, TaskType, PeftModel`) mais **absents du fichier**.
  Ils n'arrivent que par transitivité d'`unsloth`. Le commentaire d'en-tête conseille pourtant aux
  utilisateurs CPU de « remplacer unsloth par transformers + peft » — ce qui, appliqué
  littéralement, produit un environnement sans `peft` installé et un `ImportError`.

**Correction appliquée** : bornes majeures ajoutées sur les six dépendances existantes,
`torch` et `peft` déclarés explicitement, et le commentaire d'en-tête corrigé — le chemin CPU
consiste à retirer `unsloth`/`bitsandbytes`, pas à « remplacer unsloth par transformers + peft »
(qui étaient de toute façon absents du fichier).

### F12 — `export_gguf.py` télécharge du code distant non épinglé à l'exécution — **moyen** — ✅ corrigé

`export_gguf.py:68-83` : si `convert_hf_to_gguf.py` n'est pas trouvé localement, le script le
télécharge depuis `raw.githubusercontent.com/ggerganov/llama.cpp/master/…` et l'exécute
immédiatement. Deux problèmes distincts :

- **Contradiction avec la promesse produit.** Le README annonce « 100 % local · No cloud » et un
  usage « même air-gapped ». L'export GGUF — l'étape qui rend le modèle fine-tuné déployable —
  exige en pratique un accès Internet sortant.
- **Approvisionnement non épinglé.** `master` n'est pas une référence stable : le script exécuté
  n'est pas le même d'un jour à l'autre, et rien n'en vérifie l'intégrité.

Autres remarques sur le même fichier :

- `subprocess.run(…, capture_output=True)` (ligne 89-93) **avale toute la sortie** de la
  conversion. L'étape dure plusieurs minutes et n'affiche rien dans le flux SSE, alors que
  `runProcess` côté Java est conçu pour diffuser ligne à ligne — le mécanisme existe, il est
  neutralisé ici.
- La persona est **réécrite en dur** ligne 116 (`"Tu es un assistant spécialisé dans
  l'exploitation autoroutière."`), juste sous un commentaire expliquant qu'elle doit correspondre
  à `AssistantPersona.SYSTEM_PROMPT`. Les deux sont identiques aujourd'hui par coïncidence ;
  modifier la constante Java désynchronisera silencieusement l'instruction imprimée.

**Correction appliquée.** L'épinglage de la révision avait déjà traité le second point ; restait
le premier, c'est-à-dire la contradiction avec la promesse produit.

`llama_cpp_convert.resolve()` cherche désormais le convertisseur **d'abord sur le disque** :
`SPECTRA_LLAMA_CPP_DIR` (fourni par l'exploitant), puis `/opt/llama-cpp-converters` (rempli à la
**construction** de l'image du trainer, où le réseau est disponible et le résultat figé dans une
couche), puis le paquet `llama_cpp`, puis le cache, et seulement en **dernier recours** le
téléchargement — qui le dit désormais explicitement dans le flux, pour qu'un échec en
environnement cloisonné soit compris du premier coup.

Le fichier embarqué porte la révision dans son nom : une image plus ancienne ne fournit jamais en
silence un convertisseur d'une autre révision que celle demandée à l'exécution. Un déploiement
air-gapped passe par le disque et ne touche jamais le réseau.

### F13 — Aucune couverture de test du moteur d'entraînement — **moyen** — ✅ corrigé

- Côté Java, `FineTuningGedTraceTest` et `FineTuningSftExclusionTest` testent la traçabilité GED
  et le filtre de catégories — deux méthodes périphériques, atteintes par réflexion. Ni la
  machine à états, ni le verrou d'unicité (`trainingRunning`), ni l'annulation, ni la
  construction de la ligne de commande ne sont couverts. (`FineTuningRequestTest` et
  `ArticleCommentAutoRetrainTest`, ajoutés avec les correctifs F6/F9 et F7, couvrent les défauts
  de la requête et le câblage du ré-entraînement automatique — pas l'orchestration.)
- Côté Python, il n'existe toujours **aucun test** : les seuls `test_*.py` du dépôt sont dans
  `services/reranker` et `services/docparser`. `ci.yml` ne lance rien sur `scripts/`, et
  `shellcheck.yml` ne mentionne pas `train.sh`. `train_host.py` n'est de surcroît pas importable
  — son corps s'exécute au chargement (argparse, `import torch`, détection Unsloth) — donc ses
  classes de dataset ne peuvent pas être testées sans le restructurer d'abord.

Les défauts F3, F4, F5 sont tous des invariants testables sans GPU ni téléchargement de modèle
(un tokenizer factice suffit à vérifier que les labels du prompt valent `-100`, que le gabarit
appliqué provient bien du tokenizer, que `max_length` est respecté). C'est le levier le moins
cher pour éviter la réapparition de cette classe de bugs — et le corollaire naturel du refactor
de F3, qui devra de toute façon rendre le module importable.

**Correction appliquée.** Le volet Python l'avait été avec F3 (`scripts/tests/test_chat_format.py`,
job CI `training-scripts`). Le volet Java l'est désormais : `FineTuningOrchestrationTest` conduit un
job réel de bout en bout contre un `TrainingRunner` de test qui rejoue les lignes de
`ProgressLogger` et produit l'artefact attendu. Sont couverts les trois mécanismes qui manquaient :

- **la machine à états** — la séquence `PENDING → EXPORTING_DATASET → TRAINING → COMPLETED` est
  observée dans l'ordre, et un échec est situé à la phase qu'il a interrompue ;
- **le verrou d'unicité** — la soumission concurrente est tentée *pendant* l'entraînement, seul
  moment où le verrou est réellement tenu, puis on vérifie qu'il est rendu (un échec ne doit pas
  condamner la fonctionnalité jusqu'au redémarrage) ;
- **l'annulation** — déclenchée en cours d'exécution, elle atteint l'exécuteur, laisse le job en
  `CANCELLED` à la phase interrompue, et aucune ligne tardive ne le ressuscite.

S'y ajoutent les cas où l'orchestration doit refuser de conclure : code de sortie non nul,
adaptateur absent malgré un code 0 — un job vide s'afficherait sinon « terminé » —, et dataset
vide après filtrage, qui ne doit même pas solliciter l'exécuteur.

La valeur de ces tests a été vérifiée par mutation : neutraliser le verrou fait échouer le cas
concurrent sur son assertion nommée, et supprimer la transition `EXPORTING_DATASET` fait échouer
la séquence d'états. Aucun n'est complaisant.

---

## 5. Cohérence entraînement / service

### F14 — Le modèle est entraîné sur une distribution qu'il ne voit jamais en production — **moyen (conception)**

`TrainingPair.of` construit systématiquement `system = AssistantPersona.SYSTEM_PROMPT`,
`user = instruction`, sans contexte documentaire. Au service, `RagService` compose
`system = persona + "\n" + "Contexte:\n%s"` (`SYSTEM_PROMPT_TEMPLATE`, ligne 79-80) avec les
chunks récupérés, et `user = question`.

Le modèle est donc affiné sur des prompts courts sans contexte, puis servi avec des prompts longs
saturés de contexte. L'intention est saine — le commentaire d'`AssistantPersona` explique
correctement pourquoi la persona doit rester identique — mais la cohérence s'arrête à la persona
et ne couvre pas la **forme** du prompt.

Recommandation : générer une part du dataset SFT sous la forme réellement servie (persona +
contexte extrait + question → réponse ancrée). C'est aussi le seul moyen d'apprendre au modèle
à *utiliser* le contexte plutôt qu'à répondre de mémoire — c'est-à-dire d'obtenir le bénéfice
attendu du couplage RAG + fine-tuning revendiqué par le produit.

**Correction appliquée.** Le défaut est plus profond que l'écart de distribution : le prompt servi
porte aussi une **consigne de citation** (« cite tes sources en insérant le numéro entre
crochets ») et une **consigne d'abstention** (« si le contexte ne contient pas l'information,
dis-le clairement ») que le SFT n'enseignait ni l'une ni l'autre. Le fine-tuning entraînait donc à
répondre *de mémoire, sans citer* — contre l'objectif de service.

- La mise en forme du prompt RAG quitte `RagService` pour `fr.spectra.model.RagPromptFormat` :
  **une seule** définition, partagée par le service et la génération du dataset. La leçon est
  celle des évènements structurés du trainer — un format implicite dupliqué finit par diverger.
- `DatasetGeneratorService` produit une part de paires **ancrées** (`spectra.dataset.
  grounded-every-n`, défaut : un chunk sur deux), sous cette forme exacte. **Aucun appel LLM
  supplémentaire** : question et réponse déjà générées sont réemployées, seul le prompt système
  change — la correction est donc gratuite en inférence.
- Le contexte comporte des **distracteurs** issus d'un autre document
  (`spectra.dataset.grounded-distractors`, défaut 2) et la position du vrai passage **tourne** :
  sans cela le modèle apprendrait à citer `[1]` par réflexe, pas à lire.
- Un **refus ancré** accompagne les exemples négatifs : contexte fait de distracteurs seuls,
  réponse d'abstention. C'est le seul exemple qui entraîne la consigne d'abstention.
- Les deux formes coexistent dans le dataset : le mode direct (sans retrieval) existe toujours,
  et n'entraîner que la forme ancrée le dégraderait à son tour.

Le test anti-dérive est dans `RagServiceModelProfileTest` : il compare le prompt **réellement
envoyé au modèle** par `RagService` avec celui que porte une paire d'entraînement ancrée, et
exige l'égalité stricte.

---

## 6. Documentation désynchronisée

`docs/tech/technical-doc.fr.md` §5 décrit un système qui n'est plus celui du code :

- **« Simulation »** — le tableau des modes annonce un repli « Python absent → simulation :
  `adapter/training_complete.json` + logs epoch simulés ». Ce repli n'existe plus : `train.sh`
  fait `exec python3 train_host.py` et échoue si Python est absent. Cette ligne de documentation
  masque directement F1 : elle laisse croire qu'un environnement sans Python dégrade
  gracieusement, alors qu'il échoue.
- **« Rapport : génère `REPORT.md` dans le dossier du job »** — `FineTuningService` n'écrit aucun
  rapport. Le champ `FineTuningJob.reportPath` est **toujours `null`** et pourtant propagé dans
  le DTO, l'entité JPA (`FineTuningJobEntity:35`), toutes les méthodes `with*`, et jusqu'au type
  TypeScript du frontend (`FineTuning.tsx:31`). Soit la fonctionnalité est à implémenter, soit le
  champ est à supprimer — en l'état il documente une capacité inexistante.
- **Montage `./scripts`** (ligne 1749) et **diagramme C4** (`c4-level-2-containers.fr.md:42`) —
  voir F1.
- `docs/tech/llama-cpp.fr.md:308` mentionne encore « simulation disponible dans
  `scripts/train.sh` ».

---

## 7. Points mineurs

- **Annulation puis resoumission → 409.** `cancelJob` positionne `FAILED` et tue le process, mais
  ne libère pas `trainingRunning` — c'est le `finally` du thread asynchrone qui s'en charge.
  L'utilisateur qui annule et relance immédiatement reçoit un 409 transitoire, sans explication.
- **Réponse de création mal typée côté frontend.** `POST /api/fine-tuning` renvoie
  `{jobId, status}` (une `Map<String,String>`), mais `onFormSubmit` la traite comme un
  `FineTuningJob` complet et la passe à `setActiveJob`. `status`, `modelName`, `totalEpochs` sont
  `undefined` jusqu'au prochain sondage ; `stepIndex(undefined)` renvoie `-1` et la barre d'étapes
  s'affiche vide dans l'intervalle.
- **Message d'erreur 409 non affiché.** Le contrôleur renvoie `{"error": "Un entraînement est
  déjà en cours"}` ; le frontend lit `err.response.data.detail` et retombe sur
  `err.message` (« Request failed with status code 409 »). Le message utile est perdu.
- **ORPO sans interface.** ORPO est implémenté de bout en bout (DTO, service, `train.sh`,
  `train_host.py`) mais n'a aucun contrôle dans `FineTuning.tsx` — il n'est atteignable que par
  appel API direct.
- **Divergence de validation du nom de modèle.** Le schéma zod du frontend impose
  `/^[a-z0-9-_]+$/` (minuscules uniquement) là où `FineTuningRequest` accepte
  `[A-Za-z0-9][\w./@:-]*`. Sans conséquence (le frontend est plus strict), mais un nom accepté par
  l'API est refusé par l'UI.
- **Volume des logs SSE.** `runProcess` republie **chaque ligne** de sortie du process dans le
  sink (buffer 500). Avec `logging_steps=1`, une époque sur quelques centaines d'exemples suffit à
  faire tourner tout le buffer. Un échantillonnage, ou un filtrage sur les lignes de progression,
  serait plus lisible.
- **`parseTrainingOutput` recompile deux regex par ligne** de sortie et déclenche sur toute ligne
  contenant `epoch`. Sans gravité, mais les motifs gagneraient à être des constantes `Pattern`.

---

## 8. Priorisation

| # | Constat | Gravité | Effort | Statut |
|---|---|---|---|---|
| F1 | Script/Python absents de l'image `spectra-api` (fine-tuning inopérant en Docker) | Bloquant | Élevé — décision d'architecture | Ouvert |
| F2 | `train.sh` sans bit exécutable | Bloquant | Trivial | ✅ corrigé |
| F3 | Gabarit de conversation en dur, faux pour 3 bases sur 4 (dont le défaut `phi3`) | Critique | Moyen | ✅ corrigé |
| F4 | Le packing supprime le masquage du prompt | Critique | Faible | ✅ corrigé |
| F7 | Ré-entraînement automatique branché sur le mauvais jeu de données, et jamais déployé | Élevé | Faible | ✅ corrigé |
| F8 | Trois formats de prompt SFT / DPO / service | Élevé | Moyen (même correctif que F3) | ✅ corrigé |
| F9 | Pas de split de validation → aucun signal de sur-apprentissage | Élevé | Faible | ✅ `valSplit` livré |
| F6 | `loraAlpha` figé à 128 quel que soit le rang | Moyen | Trivial | ✅ corrigé |
| F5 | `--max-length` ignoré sur GPU | Moyen | Trivial | ✅ corrigé |
| F11 | Dépendances non bornées, `torch`/`peft` manquants | Moyen | Trivial | ✅ corrigé |
| F12 | Téléchargement non épinglé de `convert_hf_to_gguf.py` | Moyen | Faible | ✅ corrigé |
| F13 | Aucun test du moteur d'entraînement | Moyen | Moyen | ✅ corrigé (`chat_format` + orchestration) |
| F14 | Distribution d'entraînement ≠ distribution de service | Moyen | Moyen (conception) | ✅ corrigé |
| F10 | Arguments positionnels fragiles | Faible | Faible | ✅ `TrainingSpec` (lot 4a) |
| §6 | Documentation décrivant un système différent du code | Faible | Faible | Ouvert |

**Ordre suggéré.** Tous les constats de correction de l'entraînement sont traités (F3, F4, F5,
F8, F9), ainsi que F2, F6, F7, F10 et F11. **F1 est fermé** : le contrôle de disponibilité
suggéré ici a été livré au lot 4a, sous une forme plus stricte que « au démarrage » — il est
consulté à *chaque* soumission, avant toute création de job.

Ce qui n'est **pas** résolu, et doit rester lisible : le fine-tuning demeure inexécutable via
`docker compose up`, faute de Python dans l'image. La différence est que l'API le dit maintenant
(503 motivé) au lieu de l'accepter puis d'échouer à mi-course. Le rendre *exécutable* en
conteneur est le lot 4b, dont l'urgence a baissé depuis le retrait de Kubernetes.

Ensuite, par ordre de valeur : ~~compléter F13~~ **fait** — l'orchestration est couverte de bout en
bout, mutations à l'appui — et ~~F14~~ **fait** : le dataset comporte désormais une part de paires
ancrées, sous la forme exacte du prompt servi, citations et abstention comprises.

~~F12~~ **fait** : le convertisseur est cherché d'abord sur le disque, et l'image du trainer
l'embarque depuis sa construction — un export en environnement cloisonné ne dépend plus d'un accès
sortant. ~~§6~~ **fait** : le champ `reportPath`, toujours `null` et propagé jusqu'au type
TypeScript, est supprimé, et la documentation ne décrit plus ni « simulation » ni `REPORT.md`.

**Aucun constat de cet audit ne reste ouvert.**

**Ce que F14 ne prouve pas.** Le dataset a la bonne forme, et des tests le figent des deux côtés.
Que cette forme *améliore* la qualité servie reste à mesurer sur un vrai entraînement : c'est
précisément ce que l'évaluation enchaînée (`autoEvaluate`) rend possible sans quitter la page.
