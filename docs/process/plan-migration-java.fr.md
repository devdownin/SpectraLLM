# Plan de migration — sortir Python du chemin de requête

> **Ce document est un plan d'exécution, pas un audit.** L'analyse, les alternatives écartées et
> les justifications techniques vivent dans [`audit-python-java.fr.md`](audit-python-java.fr.md).
> Ici : quoi faire, dans quel ordre, avec quel critère d'arrêt et quel retour arrière.
>
> **Périmètre : le code Python de production.** Les 1 051 lignes de test sont hors périmètre par
> décision (audit §0). La cible est donc **1 284 lignes**, dont **222 sur le chemin de requête**.

---

## 1. Objectif

| | Aujourd'hui | Après le plan |
|---|---|---|
| Python traversé par une requête utilisateur | 222 lignes, 2 conteneurs | **0** |
| Images Docker Python construites par le dépôt | 2 (`docparser`, `reranker`) | **0** |
| Jobs Python en CI | 2 | 1 (tests d'outillage, conservé) |
| Fine-tuning sans runner configuré | échoue à mi-course (`IOException`) | 503 actionnable (**F1 clos**) |
| Python de production dans le dépôt | 1 284 lignes | **0** |

**Ce que le plan ne fait pas** : supprimer les tests d'outillage Python (363 lignes, décision
audit §0), remplacer ChromaDB (hors périmètre, audit §11), ni réécrire l'entraînement QLoRA en
Java (impossible sans régression, audit §8).

---

## 2. Principes directeurs

Quatre règles qui expliquent la forme de chaque lot. Les enfreindre, c'est transformer une
migration réversible en pari.

1. **La bascule est une propriété de configuration, jamais un remplacement de code.** Chaque
   composant migré coexiste avec son prédécesseur derrière une clé (`spectra.reranker.engine`,
   `spectra.pdf.layout-aware`). On bascule le défaut *après* validation, on supprime l'ancien
   *après* une version de coexistence. Retour arrière = une variable d'environnement.
2. **Aucune bascule sans mesure de parité prise sur l'implémentation sortante.** La référence
   doit être capturée pendant que le Python est encore la vérité. Une régression de reranking ou
   d'extraction ne casse rien — elle dégrade silencieusement les réponses. C'est le mode de
   panne le plus coûteux du projet et le seul qu'aucun test unitaire n'attrape.
3. **L'observabilité précède la bascule.** Un composant migré doit être mesurable *avant* de
   devenir le défaut, sinon la comparaison avant/après est impossible et la régression
   invisible (cf. P15).
4. **Un lot livre une valeur autonome.** Chaque lot est mergeable et déployable seul. Aucun
   « grand merge » en fin de parcours.

---

## 3. Séquence

Deux pistes **indépendantes**, exécutables en parallèle par deux personnes différentes.

```mermaid
flowchart TD
    subgraph A["Piste A — chemin de requête"]
        A0["Lot 2bis · Métriques Micrometer<br/>effort : 0,5 j"] --> A1
        A1["Lot 2 · Reranker ONNX<br/>effort : 2 j, dont 1 opérationnel"] --> A2
        A2["Lot 3 · MarkdownPdfExtractor<br/>effort : 8-12 j"]
    end
    subgraph B["Piste B — entraînement"]
        B0["Lot 4a · TrainingRunner + 503<br/>effort : 2 j — ferme F1"] --> B1
        B1["Lot 4b · spectra-trainer<br/>effort : 5 j"]
    end
    A2 -.->|"P14.2 : JOB_TO_SECTION"| X["CI cohérente"]
    B1 -.->|"P14.2 : JOB_TO_SECTION"| X
```

**Ordre recommandé si une seule personne** : 2bis → 4a → 2 → 3 → 4b.

Le lot 4a est placé tôt malgré son appartenance à la piste B : c'est **2 jours qui ferment F1**,
le seul défaut de cet audit qui casse une fonctionnalité pour un utilisateur aujourd'hui. Les
lots 2 et 3, eux, améliorent une architecture qui fonctionne.

---

## 4. Lot 2 bis — Métriques Micrometer sur le reranking ✅ *livré*

**Pourquoi d'abord.** P15 : le moteur ONNX était livré sans aucune métrique, alors que le service
Python expose `/metrics`. Basculer en l'état échangeait un composant observé contre un composant
muet — et supprimait au passage toute possibilité de comparer les deux.

> **Portée élargie depuis la levée du contrôle de parité.** Ces compteurs ne sont plus une
> commodité de comparaison : ils sont désormais le **seul** signal indiquant que le reranking
> s'exécute et n'échoue pas en silence.

**Prérequis** : aucun. **Effort** : 0,5 j. **Risque** : nul (ajout pur).

### Tâches

1. Créer `backend/src/main/java/fr/spectra/service/reranker/MeteredRerankerClient.java` —
   décorateur `@Primary` implémentant `RerankerClient`, qui enveloppe le bean actif quel qu'il
   soit.

   > **Pourquoi un décorateur plutôt qu'instrumenter les deux implémentations.** C'est ce qui
   > garantit des métriques *identiques* sur les deux moteurs — condition pour que la comparaison
   > avant/après la bascule ait un sens. Instrumenter séparément, c'est risquer deux définitions
   > de « latence » qu'on comparerait sans le savoir.

2. Métriques exposées (nommage Micrometer, tag `engine` = `http` | `onnx`) :

   | Métrique | Type | Tags | Ce qu'elle sert à voir |
   |---|---|---|---|
   | `spectra.reranker.requests.total` | `Counter` | `engine`, `outcome` | le taux d'échec, donc la fréquence du repli `rerankApplied=false` |
   | `spectra.reranker.duration` | `Timer` | `engine`, `outcome` | la latence HTTP vs in-process — l'argument chiffré de la migration. Tag `outcome` inclus : un échec rapide et un succès lent ne doivent pas se moyenner |
   | `spectra.reranker.documents` | `DistributionSummary` | `engine` | la taille des lots réellement soumis, qui conditionne la latence |

3. Test : `MeteredRerankerClientTest` — vérifie que le compteur `failure` s'incrémente **et que
   l'exception est propagée**. Une métrique qui avalerait l'exception transformerait le repli en
   panne silencieuse, exactement ce que §10.2 interdit.

### Ce qui a été livré

- `MeteredRerankerClient` — décorateur, et `RerankerMetricsConfig` qui l'installe en `@Primary`.
  Les deux moteurs sont nommés explicitement dans la configuration plutôt qu'injectés par leur
  interface : un `@Bean` de type `RerankerClient` recevant un `RerankerClient` dépendrait de
  l'exclusion des auto-références par Spring — comportement réel mais implicite, qu'aucun test de
  contexte de ce dépôt ne savait vérifier.
- `MeteredRerankerClientTest` (8 tests). Les deux premiers portent sur autre chose que les
  compteurs, et c'est délibéré : **l'exception doit traverser** (le repli de `RagService` en
  dépend) et **`isAvailable()` doit être délégué** (le garde-fou de l'option (b) en dépend). Un
  décorateur manquant l'un des deux produirait de belles métriques sur un système cassé.
- `ApplicationContextSmokeTest` gagne une assertion de câblage : le bean `RerankerClient` exposé
  **est** un `MeteredRerankerClient`. Sans elle, le démarrage du contexte prouvait seulement
  l'absence d'explosion — pas que le décorateur avait pris la main.

### Critère de sortie

- [x] les trois métriques sont enregistrées avec le tag `engine` issu de la configuration
- [x] l'échec est compté **et** relancé ; la taille du lot est enregistrée même en cas d'échec
- [x] `isAvailable()` et `checkHealth()` délégués
- [ ] `curl localhost:8080/actuator/prometheus | grep spectra_reranker` vérifié sur une pile réelle

### Retour arrière

Retirer `RerankerMetricsConfig`. Aucun appelant ne référence le décorateur (il satisfait
`RerankerClient`), les moteurs redeviennent directement injectables.

---

## 5. Lot 2 — Reranker ONNX dans la JVM

**État réel : le code est écrit.** Sont livrés le moteur (`OnnxCrossEncoderReranker`), le
harnais de parité, six classes de test et trois de support. **Ce lot n'est pas un lot de
développement — c'est un lot de production d'artefact et de retrait.**

**Prérequis** : lot 2 bis + une machine ayant le modèle en cache (accès à `huggingface.co`, ou
volume `reranker-model-cache` d'une installation ayant déjà servi).
**Effort** : ~0,5 j. **Risque** : moyen, et **non couvert** depuis la levée du contrôle de
parité — voir ci-dessous.

### Parité ONNX : contrôle levé

**Décision.** La comparaison d'ordre entre le service Python et le moteur ONNX (étapes 1 et 3
ci-dessous) **ne sera pas faite**. Arbitrage pris en connaissance de cause : il n'y a pas encore
de base d'utilisateurs à protéger, et le coût du contrôle — lever la pile Python, construire une
image de 2,5 Go, capturer une référence — n'est pas justifié par le risque encouru à ce stade.

**Ce que ce contrôle protégeait, et qui n'est plus couvert.** Une divergence de classement entre
les deux moteurs ne provoque **aucune erreur** : le reranking ne tombe pas en panne, il classe
moins bien. C'est le mode de panne le plus discret du projet — pas d'exception, pas de log, pas
d'alerte, juste des réponses un peu moins pertinentes. C'était précisément la raison d'être du
harnais.

**Ce qui reste comme filet, à coût nul.** Le benchmark qualité (`spectra.benchmark`,
`QualityBenchmarkService`) mesure la pertinence de bout en bout et détecterait une régression
grossière. Il est plus lâche que la comparaison d'ordre — il ne verra pas une permutation
mineure — mais il ne demande aucune infrastructure Python. **Le lancer avant et après la
publication de l'artefact est la vérification recommandée en remplacement.**

**Ce qui est conservé.** `RerankerParityTest` et son corpus **restent dans le dépôt** : ils ne
coûtent rien (leurs garde-fous tournent déjà en CI contre des rerankers factices) et redeviennent
utilisables tels quels le jour où une base installée justifiera la mesure — avant une release
large, ou à un changement de modèle. Les étapes 1 et 3 ci-dessous sont conservées pour ce
scénario, marquées comme facultatives.

### Étape 1 — Capturer la référence *(facultative — contrôle levé)*

`backend/src/test/resources/reranker-parity/` n'existe pas. Tant qu'il est absent,
`RerankerParityTest` **se neutralise par `Assumptions`** : la suite est verte sans avoir rien
comparé. C'est le point le plus important de ce lot.

```bash
# Depuis la RACINE du dépôt.
docker compose --project-directory . -f deploy/docker/docker-compose.yml \
  --profile reranker up -d reranker
curl http://localhost:8002/health          # attendre {"status":"ok","model":"…"}

cd backend && mvn test -Dtest=RerankerParityTest \
  -Dreranker.parity.capture=http://localhost:8002 \
  -Dreranker.parity.scale=sigmoid
```

Le fichier produit **se versionne avec le code**. Le nom du modèle est lu sur `/health` — donc
celui réellement servi, pas celui que la configuration annonce (cf. audit §6.7, trois défauts de
modèle incohérents).

### Étape 2 — Produire l'artefact ONNX

Depuis le cache HuggingFace du volume Compose, **sans accès réseau** (procédure détaillée en
audit §6.3 bis) :

```bash
docker run --rm -v spectra_reranker-model-cache:/cache alpine \
  find /cache -name config.json | head
docker run --rm -v spectra_reranker-model-cache:/cache -v "$PWD/data/models:/out" \
  python:3.11-slim bash -c "pip install -q optimum[exporters] && \
    HF_HUB_OFFLINE=1 optimum-cli export onnx --model /cache/<snapshot> \
      --task text-classification /out/reranker"
```

Exporter depuis le modèle **déjà servi** garantit que l'artefact publié est bien celui que la
stack utilisait — et, si la parité est mesurée un jour, que la comparaison porte sur le même
modèle.

**C'est la seule étape réellement obligatoire du lot** : sans artefact, il n'y a rien à publier,
et l'URL par défaut continuera de renvoyer une 404.

Une fois l'artefact produit, un contrôle de fumée à coût nul vaut la peine — il ne prouve pas la
parité, mais il prouve qu'`OrtSession.run` s'exécute sur ce modèle plutôt que d'échouer au
premier rerank :

```bash
# Reranking actif par défaut : démarrer la pile suffit.
curl -s localhost:8080/api/status | grep -A3 reranker   # available=true attendu
```

### Étape 3 — Vérifier la parité *(facultative — contrôle levé)*

```bash
mvn test -Dtest=RerankerParityTest -Dreranker.parity.verify=onnx:./data/models/reranker
```

C'est ce lancement qui transformerait « code livré » en « code prouvé ». **Il ne sera pas fait**
(voir « Parité ONNX : contrôle levé ») ; la commande est conservée pour le jour où la mesure
redeviendra justifiée. Critères de lecture, inchangés :

- Écart d'**ordre** → bloquant. Diagnostiquer avant d'aller plus loin.
- Écart de **score** seul → acceptable si l'activation diffère ; relancer avec
  `-Dreranker.parity.scores=ignore` pour l'assumer explicitement. L'inverse — accepter une
  permutation parce que « les scores sont proches » — est une régression invisible.

### Étape 4 — Mesurer *(devenue la vérification principale)*

Rejouer le benchmark qualité (`spectra.benchmark`) **avant et après** la publication de
l'artefact, et relever `spectra.reranker.latency` (lot 2 bis). **Publier l'écart dans la PR** :
c'est la justification chiffrée de la migration, et la seule trace qui survivra à la suppression
du service Python.

Le contrôle de parité étant levé, cette étape n'est plus une confirmation parmi d'autres —
**c'est le seul garde-fou restant** contre une régression de pertinence. Il est plus lâche
qu'une comparaison d'ordre (il ne verra pas une permutation mineure), mais il ne demande aucune
infrastructure Python, et il porte sur ce qui compte réellement : la qualité des réponses.

### Étape 5 — Basculer et retirer

| Fichier | Action |
|---|---|
| ~~`application.yml` — défaut du moteur~~ | ✅ **fait** — `enabled: true` + `engine: onnx` (D1/option b) |
| `deploy/docker/docker-compose.yml` | supprimer le service `reranker`, le profil, le volume `reranker-model-cache`, `SPECTRA_RERANKER_URL` |
| `services/reranker/` | supprimer (73 lignes prod + 145 test) |
| `.github/workflows/ci.yml:172` | matrice `[docparser, reranker]` → `[docparser]` |
| ~~`scripts/setup.sh` / `.bat`~~ | ✅ **fait** — `--download-reranker` livré (D1) |
| `docs/configuration.en.md` | documenter `SPECTRA_RERANKER_ONNX_PATH` / `_URL` et le provisionnement |
| Release | publier l'artefact ONNX en asset et renseigner son URL (D1, reste à faire) |

`CrossEncoderRerankerClient` **reste** derrière `engine=http` pendant une version : c'est le
retour arrière.

> **P14.2 ne s'applique pas ici** : le job `python-services` existe toujours (matrice réduite à
> `docparser`), donc `JOB_TO_SECTION` est inchangé. Il s'appliquera au lot 3.

### Critère de sortie

- [ ] artefact ONNX produit et **publié** sous le tag `reranker-onnx-v1`
- [ ] `/api/status` rapporte le reranker `available=true` sur une installation neuve
- [ ] **benchmark qualité stable avant/après**, latence documentée — seul garde-fou restant
- [ ] `services/reranker/` supprimé, aucune image reranker construite
- [ ] ~~`reranker-parity/reference.json` versionné~~ — *sans objet, contrôle levé*
- [ ] ~~parité d'ordre vérifiée sur modèle réel~~ — *sans objet, contrôle levé*

### Retour arrière

`SPECTRA_RERANKER_ENGINE=http` + réactiver le profil Compose. Tant que la version de
coexistence n'est pas passée, aucun code n'est perdu.

---

## 6. Lot 3 — Extraction PDF layout-aware dans la JVM

Le lot le plus lourd, et le seul qui accepte un **compromis de qualité assumé** : Docling
(OCR/ML) n'a pas d'équivalent Java. La stratégie n'est pas de supprimer la capacité mais
**d'inverser les défauts** (audit §7.3).

**Prérequis** : lot 2 (rodage du schéma de bascule). **Effort** : 8-12 j. **Risque** : élevé.

### Tâches

1. **`MarkdownPdfExtractor`** (`backend/.../extraction/`), implémentant `DocumentExtractor`.
   Aucune modification de `DocumentExtractorFactory` n'est nécessaire — elle construit sa table
   depuis `List<DocumentExtractor>` injectée par Spring, sans liste codée en dur. Déclarer le
   bean suffit.
2. **Titres** : sous-classer `PDFTextStripper`, surcharger `writeString(String, List<TextPosition>)`,
   comparer la taille de police à la médiane du document → `#`/`##`.
3. **Colonnes** : clustering 1D des `TextPosition` par abscisse. **Sans cela, un PDF sur deux
   colonnes est extrait entrelacé** — ce qui ne lève aucune erreur et détruit tous les chunks en
   aval. C'est le risque n°1 du lot.
4. **Tableaux** : ajouter `tabula-java` (bâti sur PDFBox, déjà présent en `pom.xml:96`), modes
   *lattice* et *stream*, export Markdown natif.
5. **Nettoyage** : portage **littéral** de `_ARTIFACT_PATTERNS` et `clean_markdown`
   (`services/docparser/app.py:41-60`), avec le commutateur `DOCPARSER_STRIP_PAGE_NUMBERS` →
   `spectra.pdf.strip-page-numbers`. Porter **les tests avec** (P9) : la règle « ligne purement
   numérique bornée à 4 chiffres » est désactivable pour une raison documentée, et se reperd
   typiquement lors d'un portage.
6. **Métadonnées** : réutiliser `PdfExtractor.extractMetadata` — mêmes clés que le service Python
   (`title`, `author`, `subject`, `creationDate`), plus `format=PDF`, `layoutAware=true`,
   `parser=pdfbox-markdown`.

### Validation — obligatoire, pas optionnelle

Ce lot **ne doit pas être fusionné sur la seule foi de tests unitaires**.

1. **Corpus versionné** dans `backend/src/test/resources/pdf-corpus/` : 1 colonne, 2 colonnes,
   avec tableaux, avec en-têtes/pieds répétés, scanné.
2. **A/B** : sortie Java vs sortie `docparser` actuelle, **écart documenté fichier par fichier**.
3. **Impact retrieval** : rejouer le benchmark qualité. C'est la seule mesure qui compte — la
   fidélité du Markdown n'est qu'un proxy.

### Retrait

| Fichier | Action |
|---|---|
| `services/docparser/`, `services/requirements-test.txt`, `services/ruff.toml` | supprimer — `services/` disparaît |
| `deploy/docker/docker-compose.yml` | supprimer le service `docparser` et le profil `layout-parser` |
| `.github/workflows/ci.yml` | **supprimer le job `python-services`** |
| `.github/workflows/ci.yml:195` | `codecov` : retirer `python-services` de `needs:` |
| `scripts/verify.sh` | retirer `services` de `SECTIONS=(…)` et son bloc `wanted services` |
| `scripts/tests/test_verify_covers_ci.py` | **retirer l'entrée `"python-services": "services"`** — cf. ci-dessous |

> ### ⚠️ P14.2 — trois tests échouent si ce retrait est partiel
>
> `test_verify_covers_ci.py` tend un filet **bidirectionnel**. Supprimer le job sans mettre à
> jour le dictionnaire déclenche :
>
> | Test | Se déclenche quand |
> |---|---|
> | `test_the_currently_known_jobs_are_all_accounted_for` | le job disparaît de `ci.yml` en restant référencé |
> | `test_every_mapped_section_actually_exists` | la section disparaît de `SECTIONS=(…)` |
> | `test_each_mapped_section_is_implemented_not_just_declared` | le bloc `wanted services` disparaît |
>
> **Ce n'est pas un défaut du test** — c'est exactement le filet qu'il doit tendre. Le correctif
> est d'une ligne, mais il doit partir **dans le même commit**, sinon le lot 3 sort en CI rouge
> sur un fichier que personne n'associera à la suppression d'un microservice.

**Ce qui est conservé** : `LayoutParserClient` et `spectra.layout-parser.enabled`. Le crochet
HTTP survit pour qui veut brancher `docling-serve` en amont — le produit devient full Java par
défaut **sans amputer** les utilisateurs qui ont besoin d'OCR.

### Critère de sortie

- [ ] `services/` supprimé du dépôt
- [ ] extraction PDF layout-aware par défaut **dans la JVM**
- [ ] écart de qualité A/B mesuré et **publié**
- [ ] benchmark qualité stable
- [ ] crochet `docling-serve` documenté
- [ ] `pytest scripts/tests` vert (P14.2 traité)

---

## 7. Lot 4a — `TrainingRunner` : fermer F1

**Le meilleur rapport effort/valeur du plan** : 2 jours pour transformer une fonctionnalité qui
échoue à mi-course en une fonctionnalité explicitement indisponible.

**Prérequis** : aucun — indépendant des lots 2 et 3. **Effort** : 2 j. **Risque** : faible.

### Le défaut, concrètement

`FineTuningService` lance `./scripts/train.sh` par `ProcessBuilder` (ligne 567) avec douze
arguments **positionnels** (ligne 537), et `python3` pour l'export GGUF (ligne 609). L'image
`spectra-api` est un `eclipse-temurin:25-jre` : **ni Python, ni `scripts/`**. Chaque job soumis
en Docker échoue donc sur une `IOException` après avoir été accepté, généré son dataset et créé
son répertoire de travail.

### Tâches

1. Introduire l'abstraction absente :

   ```java
   public interface TrainingRunner {
       boolean isAvailable();
       TrainingHandle start(TrainingSpec spec);   // dataset, base, LoRA, epochs, DPO/ORPO…
       void cancel(String jobId);
   }
   ```

   `TrainingSpec` remplace les douze arguments positionnels — au passage, **F10** (audit
   fine-tuning) disparaît : un argument inséré au mauvais rang ne peut plus décaler
   silencieusement tous les suivants.

2. `ProcessTrainingRunner` : extraire `runProcess` / `runTrainingProcess` de `FineTuningService`.
   `isAvailable()` teste la présence de `trainingScript` **et** de `pythonBin`.
3. `FineTuningService` cesse de connaître `python3`, `train.sh` et les arguments positionnels.
4. **La soumission renvoie 503 quand aucun runner n'est disponible**, avec un message actionnable
   — au lieu d'accepter le job puis d'échouer.
5. Même traitement pour l'export GGUF (`exportGgufAndRegister`, ligne 609).

### Critère de sortie

- [ ] `grep -rn "python3\|train.sh" FineTuningService.java` ne renvoie rien
- [ ] test : soumission sans runner → 503 + message nommant la cause, **aucun job créé**
- [ ] test : soumission avec `ProcessTrainingRunner` → comportement inchangé (non-régression)
- [ ] F1 clos dans `audit-finetuning.fr.md`

---

## 8. Lot 4b — `spectra-trainer` : sortir Python du dépôt applicatif

**Prérequis** : lot 4a. **Effort** : 5 j. **Risque** : faible (aucune régression fonctionnelle —
QLoRA, DPO, ORPO, packing, NEFTune restent disponibles).

> **Moins urgent qu'annoncé initialement.** L'argument de déploiement de ce lot était le `Job`
> Kubernetes ; le support K8s a été retiré du dépôt (audit P3). `HttpTrainingRunner` ne sert donc
> plus qu'un scénario réel — un conteneur `spectra-trainer` en Compose. À programmer quand le
> besoin se manifeste, **pas** comme suite obligée du lot 4a, qui apporte l'essentiel de la
> valeur à lui seul.

### Tâches

1. `HttpTrainingRunner` implémentant `TrainingRunner`, alimentant `TrainingLogBroadcaster` par
   flux de logs.
2. Image `spectra-trainer` : `scripts/*.py` + `requirements.txt` + une API HTTP minimale.
   Profil Compose dédié, **absent par défaut**.
3. Retrait du dépôt applicatif : `scripts/train_host.py`, `chat_format.py`, `export_gguf.py`,
   `export_lora_gguf.py`, `base_models.py`, `llama_cpp_convert.py`, `scripts/requirements.txt`,
   et leurs trois fichiers de test (`test_chat_format`, `test_base_models`,
   `test_llama_cpp_convert`).
4. **`base_models.json` reste dans `backend/src/main/resources/`** (lot 0, P8). Le trainer le lit
   par HTTP ou reçoit les valeurs résolues dans `TrainingSpec` — ne pas réintroduire le couplage
   inverse.
5. **⚠️ P14.2, deuxième occurrence.** `scripts/tests/` conserve les trois tests d'outillage. Le
   job `training-scripts` **subsiste donc**, mais son nom devient trompeur. Le renommer
   (`tooling-tests`) impose de mettre à jour `JOB_TO_SECTION` **dans le même commit**, sinon
   `test_the_currently_known_jobs_are_all_accounted_for` échoue.

### Critère de sortie

- [ ] `find . -name '*.py' -not -path './scripts/tests/*'` ne renvoie rien
- [ ] fine-tuning fonctionnel via `spectra-trainer` (profil dédié) **et** en mode hôte
- [ ] les 19 tests d'invariants de `chat_format` tournent dans le dépôt du trainer
- [ ] `pytest scripts/tests` vert (P14.2 traité)

---

## 9. Décisions à trancher

Trois questions qui ne relèvent pas de l'exécution et bloqueront un lot si elles ne sont pas
tranchées avant.

### D1 — Comment une installation neuve obtient-elle l'artefact ONNX ? — ✅ **tranchée : option (a)**

`spectra.reranker.enabled` défaute à **`false`** : le reranking est éteint sur une installation
neuve. La bascule `engine=onnx` **ne casse donc rien par défaut** — elle ne concerne que les
installations ayant explicitement activé le reranking, qui devront provisionner
`./data/models/reranker`.

| Option | Effet | Retenue |
|---|---|---|
| **a.** `--download-reranker` dans `setup.sh`/`.bat`, `enabled` reste `false` | l'utilisateur choisit | livrée, puis étendue par (b) |
| **b.** idem + `enabled: true` par défaut | atteint le critère « pile complète » | ✅ **retenue** |
| **c.** ne rien provisionner | critère non atteint | non |

#### Ce qui est livré

- **`setup.sh --download-reranker` / `setup.bat --download-reranker`** — nouvelle étape 7 (sh)
  et 6 (bat), récupérant `model.onnx` et `tokenizer.json` dans `data/models/reranker/`.
- **Quatre comportements distincts**, parce qu'un artefact optionnel absent n'est pas une
  erreur — mais l'est s'il est réclamé par la configuration :

  | Situation | Comportement |
  |---|---|
  | artefact présent | `[OK]` avec sa taille, idempotent |
  | absent, reranking actif (défaut) | **téléchargement automatique** depuis l'URL par défaut |
  | absent, reranking désactivé | `[OK] non requis` — aucun téléchargement |
  | téléchargement **automatique** en échec | `[INFO]` : le RAG sert sans reranking, **aucune erreur comptée** |
  | téléchargement **explicite** (`--download-reranker`) en échec | `[ERREUR]` + erreur comptée — l'utilisateur l'a réclamé, il doit le savoir |

  Cette dernière distinction est ce qui rend l'URL par défaut sûre à livrer **avant** que
  l'asset ne soit publié : tant qu'il manque, un premier lancement affiche une ligne
  d'information et se termine sur « Configuration terminée ». Vérifié de bout en bout —
  l'échec automatique n'ajoute aucune erreur au décompte, l'échec explicite en ajoute
  exactement une.

- **`SPECTRA_RERANKER_ONNX_URL`** — URL de *base* du répertoire distant, avec pour défaut un
  **asset de release du projet** sur un tag dédié
  (`…/releases/download/reranker-onnx-v1`). Tag dédié et non version applicative : l'artefact
  ne change que si le **modèle** change. La variable accepte aussi un miroir interne ou un
  artefact produit hors ligne.
- **Récupération automatique si l'artefact manque** : plus aucune option n'est nécessaire.
  `setup.sh` le télécharge dès lors que le reranking est activé — ce qui est désormais le cas
  par défaut. `--download-reranker` ne sert plus qu'à forcer la récupération quand le reranking
  a été explicitement coupé.
- **`.env.example`** documente enfin le reranking — les quatre variables (`ENABLED`, `ENGINE`,
  `ONNX_PATH`, `ONNX_URL`) en étaient **totalement absentes**, ce qui rendait la fonctionnalité
  indécouvrable autrement qu'en lisant `application.yml`.
- `curl --fail` + suppression du fichier partiel : une 404 ne laisse jamais une page HTML nommée
  `model.onnx`, qu'ONNX Runtime ne rejetterait qu'au premier rerank, longtemps après le setup.

#### Corrigé au passage — `read_env_var` interrompait `setup.sh` sans message

Sous `set -euo pipefail`, `grep` sans correspondance fait échouer le pipeline de `read_env_var` ;
l'affectation `VAR="$(read_env_var CLE_ABSENTE)"` **terminait alors le script silencieusement**,
résumé final compris. Le défaut était latent tant que la seule clé lue (`LLM_CHAT_MODEL_FILE`)
figurait dans `.env.example` — il ne se manifestait que sur un `.env` édité à la main. Lire des
clés optionnelles le rendait systématique. Corrigé (`|| true`), vérifié sur les trois cas : clé
absente, clé présente, `.env` absent.

#### Option (b) — le reranking devient actif par défaut

`spectra.reranker.enabled` passe à **`true`** et `spectra.reranker.engine` à **`onnx`**, dans
`application.yml`, `docker-compose.yml` et `.env.example`. Le critère *« `docker compose up`
sans profil = pile complète, reranking inclus »* est atteint.

**Les deux défauts sont indissociables.** Activer le reranking en laissant `engine: http`
appellerait le conteneur `reranker`, qui vit derrière un profil Compose et n'est donc **pas
démarré** — un appel voué à l'échec à chaque requête. `onnx` n'a aucune dépendance de service :
sans artefact, il se déclare simplement indisponible.

##### Ce qui rend l'activation par défaut sûre — et sans quoi elle serait une régression

Le moteur ONNX enregistre son bean même sans artefact, délibérément, pour que `/api/status`
publie la cause au lieu de taire la panne. Mais `RagService` décidait d'utiliser le reranker sur
la seule **présence** du bean. Combiné à `enabled: true`, cela produisait une régression
permanente sur toute installation sans artefact — à **chaque** requête :

1. sur-extraction de `topCandidates` candidats auprès de ChromaDB (20 au lieu de 5) ;
2. échec du reranking ;
3. troncature au nombre voulu ;
4. un avertissement journalisé.

Le résultat restait *correct* — c'est précisément ce qui rendait le défaut coûteux et invisible.

**Correctif** : `RerankerClient.isAvailable()` (défaut `true`, surchargé par le moteur ONNX pour
refléter le chargement effectif), et `RagService` qui distingue **présent** de **utilisable**.
Une installation sans artefact se comporte désormais exactement comme une installation sans
reranking — sans sur-coût, sans bruit — tout en restant diagnosticable sur `/api/status`.

Le défaut `true` de `isAvailable()` est délibéré : une implémentation distante ne peut pas
répondre sans appel réseau, et doit donc être tentée puis échouer. Le moteur `http` est inchangé.

`RagServiceRerankerAvailabilityTest` (7 tests) fige la propriété. Vérifié en annulant le
correctif : 4 des 7 échouent, dont celui qui mesure la sur-extraction.

##### Effet réel aujourd'hui : aucun — et c'est voulu

L'asset `reranker-onnx-v1` n'étant pas encore publié, une installation neuve tente le
téléchargement, reçoit une 404, affiche une ligne d'information et poursuit. Le reranking reste
donc inactif *de fait*, et le RAG se comporte exactement comme avant.

**Toute la chaîne est en place et s'activera d'elle-même le jour de la publication de
l'asset** — sans nouvelle version des scripts, sans intervention des utilisateurs déjà
installés. C'est ce qui rend la bascule sûre à intégrer maintenant.

`start.sh --first-run` ne passe volontairement **pas** `--download-reranker` : ce serait une
demande explicite, donc un échec bloquant, et tout premier lancement passerait pour incomplet
tant que l'asset manque. `setup.sh` récupère l'artefact de lui-même, en traitant l'échec comme
une information.

#### Reste à faire — une seule étape

1. **Produire et publier l'artefact** sous le tag `reranker-onnx-v1` (fichiers `model.onnx` et
   `tokenizer.json`), par l'export hors ligne d'[audit §6.3 bis](audit-python-java.fr.md).
   Rien d'autre à modifier : l'URL par défaut le désigne déjà.

> **Décision — la validation de parité n'est pas un prérequis.** Ce document en faisait un
> bloquant de release ; l'arbitrage retenu est de s'en passer, faute de base d'utilisateurs à
> protéger au moment de la bascule. Voir « [Parité ONNX : contrôle levé](#parité-onnx--contrôle-levé) ».

##### Empreinte mémoire — à mesurer à l'étape 1

`mMiniLMv2-L12-H384` étant distillé de XLM-R, sa matrice d'embeddings (~250 k × 384) domine sa
taille (~0,5 Go en fp32, à confirmer sur l'artefact réel). ONNX Runtime alloue **en natif** : la
contrainte n'est donc pas `-Xmx` mais la limite mémoire du conteneur `spectra-api`. À relever
pendant la validation de parité, et à documenter avant publication.

### D2 — Quel écart de qualité PDF est acceptable ? *(bloque lot 3, fusion)*

À fixer **avant** de lancer le lot, sinon la mesure A/B sera interprétée après coup en fonction
du travail déjà investi. Proposition : régression du benchmark qualité **≤ 2 points**, et aucune
régression sur les PDF à une colonne (le cas majoritaire). Au-delà, le crochet `docling-serve`
devient la recommandation documentée pour les corpus concernés, plutôt qu'un repli honteux.

### D3 — Le lot 4b est-il seulement souhaité ? *(bloque lot 4b)*

Depuis le retrait de Kubernetes, sa valeur se réduit à « plus de Python dans le dépôt
applicatif ». C'est un objectif d'hygiène légitime, mais **le lot 4a apporte l'essentiel de la
valeur fonctionnelle**. À décider explicitement plutôt qu'à enchaîner par inertie.

---

## 10. Filet de sécurité — ce que la migration ne doit pas casser

À vérifier à chaque lot (détail et justification : audit §10) :

1. `topN < 1` → rejet ; `documents` vide → résultat vide.
2. **Échec du reranker → exception, jamais un classement identité à score 0.** `RagService` doit
   continuer à produire `rerankApplied=false`. Un classement identité fausse silencieusement
   benchmarks et ablations.
3. Fichier non-PDF → 400 ; fichier vide → 400.
4. Clés de métadonnées : `title`, `author`, `subject`, `creationDate`, `page_count`,
   `format=PDF`, `layoutAware=true`, `parser=<nom>`.
5. `/api/status` et `/api/health` continuent de rapporter reranker et docparser — sous une forme
   adaptée (composant in-process), **pas en disparaissant**.
6. Métriques : ce que les services Python exposaient sur `/metrics` réapparaît sur
   `/actuator/prometheus` (lot 2 bis).
7. Le repli extraction layout-aware → texte brut reste, ou est remplacé par un repli équivalent
   au sein de `MarkdownPdfExtractor`.

---

## 11. Suivi

| Lot | Effort | Piste | Bloqué par | Statut |
|---|---|---|---|---|
| 2 bis · Métriques Micrometer | 0,5 j | A | — | ✅ **livré** — `MeteredRerankerClient`, P15 corrigé |
| 2 · Reranker ONNX | 0,5 j | A | 2 bis, modèle en cache | ⬜ code livré — reste à **produire et publier l'artefact** ; ~~D1~~ ✅ tranchée, ~~parité~~ ✅ contrôle levé |
| 3 · MarkdownPdfExtractor | 8-12 j | A | Lot 2, **D2** | ⬜ à faire |
| 4a · `TrainingRunner` + 503 | 2 j | B | — | ⬜ à faire |
| 4b · `spectra-trainer` | 5 j | B | Lot 4a, **D3** | ⬜ à décider |

**Total ≈ 18-22 jours**, dont **2,5 j** (lots 2 bis + 4a) pour fermer F1 et ouvrir la voie à la
bascule du reranker.

### Mesure de l'avancement

Compter les fichiers `.py` restants est un **mauvais indicateur** : il mélange trois natures de
code, et le lot 2 — le plus rentable — ne le bougerait presque pas. Suivre plutôt :

```bash
# Python de production sur le chemin de requête — la seule ligne qui compte (cible : 0)
wc -l services/*/app.py 2>/dev/null | tail -1

# Python de production total (cible : 0)
find . -name '*.py' -not -name 'test_*' -not -name 'conftest.py' \
  -not -path '*/node_modules/*' | xargs wc -l | tail -1

# Images Python construites par le dépôt (cible : 0)
grep -c "context: ./services" deploy/docker/docker-compose.yml
```

| Jalon | Chemin de requête | Production totale | Images |
|---|---:|---:|---:|
| Aujourd'hui | 222 | 1 284 | 2 |
| Après lot 2 | 149 | 1 211 | 1 |
| Après lot 3 | **0** | 1 062 | **0** |
| Après lot 4b | 0 | **0** | 0 |

---

**Documentation :** [index](../README.md) · [Audit Python→Java](audit-python-java.fr.md) ·
[Audit fine-tuning](audit-finetuning.fr.md) · [Architecture](../architecture.en.md) ·
[Configuration](../configuration.en.md)
