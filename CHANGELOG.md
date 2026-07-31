# Changelog — Spectra

Toutes les modifications notables sont documentées dans ce fichier.
Format : [Keep a Changelog](https://keepachangelog.com/fr/1.0.0/)
Versionnage : [Semantic Versioning](https://semver.org/lang/fr/)

---

## [Non publié]

### Corrigé — les documents Word modernes étaient classés comme du XML

Le type MIME officiel d'un DOCX est `application/vnd.openxmlformats-officedocument.wordprocessingml.document` : il **contient la sous-chaîne `xml`**. La condition XML étant évaluée avant celle du DOCX, tout document Word moderne recevait l'icône, le libellé et le groupe du XML dans la GED.

Le défaut a survécu parce que `getDocumentType` vivait à l'intérieur de `Documents.tsx` — 1 569 lignes, fonction non exportée — donc hors d'atteinte du moindre test. Il est apparu à la **première exécution** du premier test écrit contre cette logique une fois extraite.

- `frontend/src/lib/documentTaxonomy.ts` accueille la taxonomie et le regroupement des documents, avec 11 tests. Les formats les plus spécifiques sont désormais testés d'abord.
- `Documents.tsx` passe de 1 569 à 1 495 lignes. Le gain en lignes est secondaire : l'essentiel est qu'une logique décidant de ce que voit l'utilisateur soit devenue vérifiable.

### Windows — `stop.bat` n'arrêtait pas tout ce qu'il prétendait arrêter

`stop.sh` active tous les profils Compose et passe `--remove-orphans`, ce qui stoppe aussi les services optionnels (layout-parser, reranker, Kafka). `stop.bat` ne faisait ni l'un ni l'autre : un utilisateur Windows croyait avoir arrêté Spectra alors que ces services continuaient de tourner, ports et mémoire pris.

`pipeline.bat` acceptait par ailleurs `--orpo` sans le déclarer dans sa ligne « Usage » — l'option existait, personne ne pouvait le savoir en lisant l'entête.

`scripts/tests/test_windows_scripts_parity.py` compare désormais les options déclarées par les six paires `.sh` / `.bat`. **Ce qu'il ne couvre pas est dit explicitement** : il compare les interfaces annoncées, pas les comportements. C'est précisément pour cela qu'il n'aurait pas attrapé le défaut de `stop.bat` — celui-là a demandé de lire les deux scripts.


### Simplifications — juge LLM unique, outillage complété, code mort retiré

**Un seul juge, un seul barème.** Deux services notaient des réponses avec deux prompts distincts produisant tous deux un « score sur 10 » affiché comme tel : `EvaluationService` appliquait un barème explicite (Exactitude 0-4, Complétude 0-3, Clarté 0-3), `QualityBenchmarkService` demandait un score de 1 à 10 sans barème. Comparer les deux revenait à comparer deux instruments gradués pareil. `LlmJudge` porte désormais le barème explicite, seul retenu.

Deux écarts sont apparus en unifiant, et sont corrigés :

- Le benchmark qualité appelait `chat(...)` en génération libre — il n'avait **jamais reçu** le décodage contraint mis en place ailleurs. Son juge pouvait répondre en prose.
- Sur échec de parsing, il **substituait 5,0** et conservait l'item : une note fabriquée entrait dans la moyenne et la tirait vers le milieu, d'autant plus que le juge était instable. Un jugement qui n'aboutit pas n'est pas un jugement moyen — l'item est désormais écarté, comme le faisait déjà l'évaluation.

**`verify.sh` rejoue enfin tout ce qu'il annonce.** Livré la veille, il ignorait silencieusement deux suites de la CI (`services/docparser` et `services/reranker`) — le défaut même contre lequel il avait été écrit. La section manquante est ajoutée, et `test_verify_covers_ci.py` relie désormais `ci.yml` au script : ajouter un job sans contrepartie locale fait échouer la CI, et une exemption doit porter sa raison.

**312 lignes de code mort retirées.** `scripts/llama-autostart.sh`, `Dockerfile.llama` et `Dockerfile.llama.cuda` n'avaient plus aucun consommateur depuis le retrait de Kubernetes : aucun workflow ni fichier Compose ne construisait ces images. Le test de parité de leurs paliers de dimensionnement disparaît avec elles.


### Retiré — le support Kubernetes et GKE

> **Rupture** : les déploiements Kubernetes ne sont plus fournis ni maintenus. Docker Compose reste le mode de déploiement supporté.

Supprimés : `deploy/k8s/` (28 fichiers, manifestes de base, overlays GPU/GKE/monitoring, seeding des modèles), les workflows `k8s-validate.yml` et `deploy-gke.yml`, et les scripts `gke-create-cluster.sh` / `gke-seed-models.sh`. Environ 2 100 lignes.

**Ce qui reste, et pourquoi.** `scripts/llama-autostart.sh` et les images `Dockerfile.llama` / `Dockerfile.llama.cuda` sont conservés : l'entrypoint est intégré à ces images Docker autonomes, il n'appartenait pas à Kubernetes. Sa documentation et ses commentaires ne le rattachent plus à un orchestrateur.

`scripts/check-model-defaults.sh` perd les deux fichiers Kubernetes de sa liste ; la garantie de cohérence porte désormais sur douze fichiers opérationnels au lieu de quatorze. Les chapitres « Déployer » des deux documentations pédagogiques sont réécrits autour de Compose, en conservant l'enseignement qui restait valable — n'exposer que l'interface et l'API, garder base vectorielle et serveurs d'inférence sur le réseau interne.

**Les archives ne sont pas réécrites.** Les entrées de CHANGELOG et les notes de version antérieures décrivent un état passé qui a bien existé. Seuls leurs liens vers les fichiers supprimés sont retirés, pour ne pas laisser croire que ces fichiers sont encore là.

### Outillage — une commande pour vérifier, un test pour la parité des manuels

**`scripts/verify.sh`** rejoue localement les contrôles de la CI. Celle-ci exécute huit commandes réparties sur quatre écosystèmes (Maven, npm, pytest, shell) ; sans point d'entrée commun, savoir si un changement passe supposait d'ouvrir `.github/workflows/ci.yml` et de rejouer les commandes à la main.

Le point de conception : **un contrôle sauté est signalé comme tel, jamais compté comme réussi.** `shellcheck` n'était pas installé dans les environnements de développement, et son absence passait totalement inaperçue — le lint des scripts n'était donc jamais exercé avant le push, alors que la CI le fait échouer. Le hook `SessionStart` l'installe désormais, et le script le signale s'il manque quand même.

**`BilingualManualParityTest`** compare la structure des documents publiés en deux langues. Il a immédiatement trouvé sa raison d'être : le manuel utilisateur français décrivait l'interface du Playground en quatre sous-sections — panneau latéral, étapes visibles en direct, actions sous chaque réponse, panneau Trace — **absentes de la version anglaise**. Un lecteur anglophone ne pouvait pas savoir qu'il lui manquait quelque chose. Les sections manquantes ont été écrites.

Le test ne compare que la **séquence des niveaux de titres** — les intitulés sont traduits. C'est volontairement grossier : il n'atteste pas que le contenu est équivalent, seulement que le plan l'est. Une traduction périmée passera ; une section ajoutée d'un seul côté ne passera plus.

**Septième copie du taux caractères/token, en TypeScript.** `frontend/src/lib/ragPipeline.ts` estimait à 4 caractères par token, avec un commentaire affirmant suivre « la convention du backend » — devenu faux dès que le backend est passé à 3,5. Le panneau Trace affichait donc une barre de budget décalée de 14 % par rapport au calcul réel. Aligné, avec un test qui le rappelle explicitement.

### Simplifications — un seul taux caractères/token, un seul bornage, un rattrapage spéculatif retiré

**La conversion caractères → tokens était définie six fois, avec deux valeurs différentes.** Ce n'était pas qu'une redondance : les budgets de contexte étaient *vérifiés* à 3,5 caractères par token (`ContextBudgetValidator`) et *dépensés* à 4 (`RagService`, `AgenticRagService`, `RagAblationService`, plus deux estimations de métriques). La marge de sécurité de 15 % était donc consommée par le seul écart de taux de change :

```
fenêtre 2048 → marge sûre 1740 tokens → budget chunks 1240 (500 réservés à la réponse)
  caractères acceptés : 1240 × 4   = 4960
  coût réel           : 4960 ÷ 3,5 = 1417 tokens
  total réel          : 1417 + 500 = 1917, soit 94 % de la fenêtre au lieu des 85 % visés
```

`TokenEstimator` devient propriétaire unique de cette conversion, à 3,5 — la valeur calibrée pour le français, volontairement pessimiste. Un test vérifie l'invariant qui était violé : ce qu'un budget autorise, recompté, doit y tenir.

> **Effet de bord assumé** : les estimations de tokens de `EvaluationService`, `BenchmarkService` et `RagAblationService` passent elles aussi de 4 à 3,5. Les chiffres rapportés (nombre de tokens, débit en tokens/s) augmentent donc d'environ 14 % à performance identique. Les valeurs de référence citées dans les manuels datent de l'ancien diviseur et ne sont pas directement comparables aux prochaines mesures.

**Le bornage des budgets était incohérent avec lui-même**, moins de 24 h après son introduction : la classification signalait sa réduction, les deux services RAG rognaient en silence. Un opérateur voyait son extrait annoncé comme réduit, jamais son contexte agentique. `clampContextTokens` / `clampExcerptChars` portent désormais la journalisation, une fois par couple (réglage, valeur), et les trois appelants perdent leur logique propre.

**`StartupOrchestrator` tentait d'installer le modèle d'embedding via llmfit**, qui ne gère que les modèles de génération. Le code était spéculatif — ses commentaires l'admettaient (« Assuming it can handle huggingface repos », « would go here if llmfit supports it ») — et échouait silencieusement, laissant croire à un rattrapage automatique inexistant. Il est remplacé par un avertissement qui désigne le vrai chemin d'acquisition (`setup.sh`, étape 5/6) et dit ce que coûte son absence. Les deux blocs chat/embedding, quasi identiques, se réduisent à deux méthodes de résolution et un prédicat partagé.

### CI — la base NVD est mise en cache, et cesse d'être restaurée vide

Le job `depcheck` échouait par intermittence sur `NvdApiException: 503` suivi de `NoDataException: No documents exist`. Le second est le décisif : la base de vulnérabilités était **vide**, donc l'analyse ne pouvait pas se poursuivre malgré l'avertissement « using local data instead » qui la précède.

Le mécanisme n'était pas l'absence de cache, mais un cache mal partagé. La base NVD vit par défaut sous `~/.m2/repository/org/owasp/dependency-check-data`, donc **dans le cache Maven de `actions/setup-java`**, keyé sur le hash des `pom.xml` et partagé par quatre jobs. Or un cache GitHub est **immuable par clé** : il est écrit par le premier job qui termine — jamais `depcheck`, qui est le plus lent. La base était ainsi restaurée vide à chaque exécution, retéléchargée intégralement, et toute indisponibilité du service NVD devenait fatale.

- `dataDirectory` sort de `~/.m2` (`${user.home}/.dependency-check-data`, surchargeable par `-Dnvd.data.directory=`), ce qui lui donne droit à un cache dédié.
- Ce cache utilise une clé tournant chaque semaine, avec `restore-keys` pour rattraper les semaines antérieures : la base n'est jamais vide, et une indisponibilité du NVD redevient ce qu'elle devrait être — un avertissement sur la fraîcheur des données, pas un échec de build.
- `nvdValidForHours` passe de 4 (défaut) à 24 : les rafales de CI d'une même journée n'interrogent plus le service du tout.

**Ce n'est pas un substitut au secret `NVD_API_KEY`.** Le cache ne protège qu'une fois peuplé ; sa première constitution reste soumise à la limitation de débit imposée au trafic anonyme. Les deux mesures sont complémentaires.

### Contexte — les trois budgets s'ajustent, la quatrième copie du calcul est alignée, la documentation cesse de mentir

Suite et fin du travail sur la fenêtre de contexte. Le précédent correctif n'en traitait qu'un tiers, et le reste s'est révélé pire que prévu.

**Les deux autres budgets étaient restés au stade de l'avertissement.** `AgenticRagService` et `RagService` (long-contexte) lisent un budget en tokens et s'y tiennent scrupuleusement — mais ce budget n'était confronté à rien. Un contexte agentique de 3000 tokens respecté à la lettre déborde tout autant d'une fenêtre de 2048. Les deux sont désormais bornés par la fenêtre servie, via le même helper que la classification (`ContextBudgetValidator.clampContextTokens`). Le cas du long-contexte est instructif : son repli protégeait déjà contre un corpus plus gros que le budget, mais pas contre un budget plus gros que la fenêtre — un corpus de 2500 tokens passait le contrôle face à un budget de 3000, pour être ensuite tronqué par llama.cpp.

**Il y avait quatre implémentations du dimensionnement, pas trois.** `scripts/llama-autostart.sh`, entrypoint du pod d'embedding Kubernetes, portait ses propres paliers — la moitié des valeurs alignées (32 Go → 4096 au lieu de 8192) — et traitait `-c` comme une fenêtre par requête alors que c'est un total. Il dérive maintenant le total du parallélisme, et ses paliers du mode chat sont ceux de la bibliothèque, vérifiés par `test_llm_sizing.py` comme ceux de `detect-env.bat`. Le mode embed garde délibérément une fenêtre fixe de 2048 : un chunk fait 512 tokens, une fenêtre plus large ne servirait qu'à consommer du cache KV par slot.

**La documentation prescrivait des variables qui n'existent nulle part.** `LLAMA_CHAT_CONTEXT_SIZE`, `LLAMA_CHAT_PARALLELISM`, `LLAMA_CHAT_NGL`, `LLAMA_CHAT_THREADS`, `LLAMA_CHAT_CPUSET`, `LLAMA_CHAT_FLASH_ATTN` apparaissaient dans les deux manuels utilisateur et deux documents techniques. Aucune n'existe dans le code — la stack utilise `LLM_*`, et le chemin Kubernetes `LLAMA_*` sans le segment `CHAT`. Le pire cas : *« La requête RAG retourne "contexte dépassé" → augmentez `LLAMA_CHAT_CONTEXT_SIZE` »*, soit exactement le symptôme traité dans cette série de correctifs, avec un remède sans effet. Tous les tableaux de variables sont refaits d'après le code, et la section de `technical-doc.fr.md` qui décrivait `llama-autostart.sh` comme l'entrypoint de Docker Compose porte désormais sa portée réelle.

**Deux passe-plats manquants dans Compose.** `LLM_CHAT_EXTRA_ARGS` et `LLM_EMBED_EXTRA_ARGS` n'étaient transmis que par l'overlay GPU : les renseigner dans `.env` restait sans effet, alors que la documentation les présentait comme le moyen de surcharger l'offload GPU. `check-model-defaults.sh` couvre en outre `llm-embed-entrypoint.sh`, qui lui échappait bien qu'il nomme le GGUF d'embedding.

### Classification — l'extrait s'ajuste à la fenêtre servie au lieu de la dépasser

Le contrôle de budget introduit précédemment a immédiatement produit son diagnostic en conditions réelles, sur le runner d'intégration continue :

```
Fenêtre servie : 2048 tokens par requête. 1 budget(s) trop large(s) :
  • spectra.classification.max-excerpt-chars = 6000 → ~2224 tokens avec le prompt,
    pour une marge sûre de 1740. Valeur tenable : ~4305 caractères.
```

Ce qu'il révèle n'est pas un incident mais **un défaut de conception du réglage lui-même** : le défaut livré de 6000 caractères n'entre que dans une fenêtre de 4096 tokens, donc à partir de 16 Go de RAM. Sur une machine de 8 à 16 Go, la classification échouerait sur chaque document — llama.cpp tronquant le début de la requête, c'est-à-dire les consignes de format et la taxonomie.

`max-excerpt-chars` devient donc une **borne haute** et non une promesse. Avant chaque classification, le budget est ramené à ce que la fenêtre servie peut porter. Envoyer moins de texte dégrade la qualité ; en envoyer trop détruit la fonction — l'arbitrage n'a qu'un sens. Plutôt que d'exiger de l'utilisateur qu'il accorde sa configuration à un dimensionnement qu'il ne voit pas (en déploiement Docker, le backend ne connaît ni `LLM_CONTEXT` ni `LLM_PARALLEL`), on s'y ajuste.

- **Une seule formule** : `ContextBudgetValidator.affordableExcerptChars(...)` sert à la fois à suggérer une valeur dans l'avertissement de démarrage et à borner l'extrait à l'exécution. Un test vérifie que les deux coïncident — deux calculs distincts finiraient par diverger, et l'avertissement annoncerait alors une valeur que le code n'applique pas.
- **Sans information du serveur, la valeur configurée s'applique** : mieux vaut le comportement demandé qu'une restriction fondée sur une fenêtre supposée.
- **La fenêtre servie est mémorisée** côté client llama.cpp, et invalidée au changement de modèle — seule opération qui puisse relancer le serveur avec un autre `--ctx-size`. Sans cela, un lot de mille documents produirait mille appels à `/props` pour une valeur qui ne bouge pas. L'avertissement de bornage suit la même logique : journalisé une fois par valeur, pas une fois par document.

### Tests — le contexte Spring est enfin démarré au moins une fois

Aucun test du dépôt ne démarrait l'application. Les suites existantes instancient leurs classes à la main ou passent par `@WebMvcTest` : le graphe de dépendances réel n'était jamais assemblé avant le déploiement. Trois familles de défauts passaient donc entre les mailles.

- **La dérive de schéma n'était pas détectée.** `schema.sql` est la source de vérité et `ddl-auto: validate` est censé signaler les écarts — mais cette validation n'était jamais déclenchée en intégration continue. Une colonne ajoutée à une entité JPA sans l'être au DDL ne se manifestait qu'au démarrage en production. C'est vérifié : en ajoutant une colonne fantôme à une entité, le test échoue désormais avec `Schema validation: missing column [...]`.
- **Le câblage impossible à satisfaire** — cycle de dépendances, `@Lazy` oublié, injection optionnelle mal déclarée, bean absent derrière un `@ConditionalOnProperty` — n'échoue qu'au moment où le conteneur assemble réellement les beans.
- **Les sondes de démarrage** qui interrogent des services externes doivent dégrader proprement quand ceux-ci sont absents ; dans ce test ils le sont tous.

`ApplicationContextSmokeTest` n'a besoin d'aucun service externe : le profil `smoke` pointe LLM, ChromaDB, reranker et browserless sur un port fermé de la boucle locale — connexion refusée immédiatement plutôt qu'attente d'un timeout — et la base est une H2 en mémoire alimentée par la vraie `schema.sql`. Coût : ~18 s.

Une dépendance cachée est apparue en l'écrivant : `StartupOrchestrator` déclenchait le téléchargement du GGUF par défaut (~4,7 Go) sur le seul critère « le fichier n'est pas là », sans interrupteur. C'est le bon comportement pour une installation ordinaire, jamais pour un test ou une CI. `spectra.startup.auto-install-models` (défaut `true`, donc comportement inchangé) permet de s'en abstraire.

### Contexte LLM — la fenêtre par requête devient la grandeur dimensionnée, et elle est vérifiée

`LLM_CONTEXT` (comme `--ctx-size`) est le contexte **total** du serveur, que llama.cpp répartit entre ses slots parallèles : une requête ne voit que `contexte / parallélisme` tokens. Trois endroits calculaient ce dimensionnement indépendamment, et **tous les trois** fixaient le total tout en faisant croître le parallélisme avec les cœurs. Conséquence contre-intuitive : plus la machine était puissante, plus la fenêtre par requête rétrécissait — 512 tokens sur une machine 32 Go / 16 cœurs, alors que le RAG agentique en budgète 3000 à lui seul.

Le dépassement ne produit aucune erreur : llama.cpp tronque le **début** de la requête, c'est-à-dire le prompt système. Le modèle perd ses consignes de format et répond hors format ; l'appelant voit un échec de parsing, jamais sa cause. C'est ce qui faisait échouer la classification automatique.

- **On dimensionne désormais la fenêtre par requête** — la seule qui compte fonctionnellement — puis on en déduit le total, en plafonnant le parallélisme pour tenir l'enveloppe mémoire. Sur du CPU, deux conversations à 4096 tokens valent mieux que huit à 512. Une machine 16 Go passe de 2048 à 4096 tokens par requête, une machine 32 Go de 4096 à 8192.
- **Une seule formule, extraite et testée** : `scripts/lib/llm-sizing.sh`, couverte par `scripts/tests/test_llm_sizing.py`. Les invariants portent sur la propriété qui compte (la fenêtre par requête ne dépend que de la RAM, ne décroît jamais, le total reste dans l'enveloppe), pas sur des valeurs intermédiaires. `detect-env.sh` s'y branche ; `detect-env.bat` ne peut pas sourcer du bash, un test compare donc ses paliers à ceux de la bibliothèque.
- **Le mode natif était le plus touché** : `ResourceAdvisorService` recommandait un total, et sa cascade de `if/else` GPU pouvait même *réduire* le contexte d'une machine 32 Go équipée d'une petite carte. Sa recommandation est désormais exprimée **par slot** ; l'orchestrateur et l'entrypoint la multiplient chacun par le parallélisme qu'ils appliquent. La clé du fichier de hints est renommée `RECO_CONTEXT` → `RECO_SLOT_CONTEXT`, précisément pour que la confusion ne puisse pas se reformer.
- **`ContextBudgetValidator`** confronte au démarrage les budgets configurés (extrait de classification, RAG agentique, long-contexte) à la fenêtre **réellement servie**, lue sur `/props` du serveur d'inférence. En déploiement Docker le backend ne connaît ni `LLM_CONTEXT` ni `LLM_PARALLEL` — ce sont des variables du conteneur d'inférence — et ces budgets étaient donc choisis à l'aveugle. Le contrôle sonde jusqu'à obtenir la valeur (le chargement d'un GGUF prend du temps), avertit avec une valeur tenable à la place d'un simple « réduisez », puis se désarme.

### Modèle de chat par défaut — Qwen2.5-7B-Instruct Q4_K_M

> **Action requise à la mise à jour** : le fichier GGUF change. Récupérez-le avant de relancer la stack (`./setup.sh --download-chat`), sinon `model-init` bloque le démarrage. L'ancien fichier peut être supprimé de `data/models/`.

Le défaut était `Phi-4-mini-reasoning-UD-IQ1_S.gguf` : un modèle **de raisonnement** en quantification **1 bit**. Deux mauvais choix pour ce produit.

- **Le raisonnement est contre-productif ici.** Un tel modèle dépense son budget de sortie en chaîne de pensée avant de répondre — dans une fenêtre de 2048 tokens, cela laisse peu de place à la réponse elle-même, et c'est ce qui a causé l'échec de la classification automatique. Spectra a besoin de suivi d'instruction et de sortie structurée, pas de déduction : `instruct` est la famille adaptée.
- **La quantification 1 bit dégrade fortement le suivi d'instruction.** Or toute la valeur du produit repose sur de la sortie structurée (classification, génération de paires d'entraînement) et du jugement (LLM-as-a-judge). `Q4_K_M` est le compromis standard.
- **Un seul modèle partout.** `setup.sh --download-chat` téléchargeait un *troisième* modèle (Phi-3.5-mini) puis réécrivait l'alias pour ne pas mentir sur son étiquette. Le « chemin facile » installait donc un modèle que `docker-compose` ne chargeait pas par défaut. Les deux scripts servent désormais le modèle par défaut, et la réécriture d'alias disparaît avec sa raison d'être.
- Le contrôle de cohérence couvre maintenant aussi `setup.sh` / `setup.bat` — c'est cette lacune qui avait laissé le troisième modèle s'installer.

**Coût.** Le fichier passe d'environ 1 Go à ~4,7 Go : téléchargement initial plus long, et premier passage de CI plus lent (le cache est ensuite réutilisé, sa clé a été renouvelée). Côté mémoire, un 7B en Q4_K_M demande ~6 Go pour les poids — la documentation annonçait déjà 16 Go minimum et 32 Go recommandés pour les modèles 7B.

### CI — cohérence des modèles GGUF par défaut, et correction d'une dérive d'embedding

Le nom des fichiers GGUF est répété dans **quatre langages de configuration** — shell, batch, YAML (Compose et Kubernetes) et Java — qu'aucune variable commune ne peut unifier : un manifest Kubernetes ne « source » pas un fichier shell. La duplication est irréductible ; ce qui ne l'est pas, c'est qu'elle dérive en silence.

- **Dérive réelle corrigée** : la CI téléchargeait le modèle d'embedding en `Q4_K_M`, Kubernetes en `Q4_0`. Deux quantifications différentes produisent des **vecteurs différents** — un index construit sous Docker se dégradait donc à l'interrogation depuis Kubernetes. C'est précisément le type d'incident que `EmbeddingConsistencyChecker` détecte a posteriori ; il n'aurait pas dû pouvoir se produire. Kubernetes est aligné sur `Q4_K_M`, valeur documentée et déjà utilisée partout ailleurs.
- **`scripts/check-model-defaults.sh`** : vérifie que tout fichier `.gguf` nommé dans un fichier opérationnel correspond bien au défaut de chat ou d'embedding (ou à un artefact amont explicitement déclaré), et qu'un même modèle est téléchargé depuis une **URL unique** partout. Exécuté par la CI sur chaque push et chaque PR, sans dépendance ni téléchargement.
- **`.env.example` est déclaré source de vérité** : la marche à suivre pour changer de modèle y est écrite, avec le rappel que la CI refusera un alignement partiel.

Le script ne supprime pas la duplication — il rend son oubli impossible à manquer, au lieu de produire un bug qui n'apparaît que sur un seul environnement.

### LLM — décodage contraint : le JSON est garanti, plus seulement demandé

Jusqu'ici, chaque endroit qui attendait du JSON le **demandait dans le prompt** puis rattrapait les écarts du modèle au parsing. Cette approche est structurellement fragile : elle suppose d'avoir anticipé toutes les façons dont un modèle peut dévier. Deux d'entre elles ont déjà causé des pannes en production — préambule en prose après troncature du contexte, bloc de réflexion d'un modèle « reasoning » contenant un JSON d'exemple.

- **`LlmChatClient.chatJson(...)`** : nouvelle variante dont la validité JSON est imposée au **décodeur**. Sur llama.cpp, le champ `response_format: {"type":"json_object"}` est compilé en grammaire et restreint l'échantillonnage — le modèle ne peut littéralement pas émettre de préambule, de bloc de réflexion ou de bloc Markdown. L'implémentation par défaut de l'interface retombe sur une génération libre, donc un provider sans cette capacité reste fonctionnel.
- **Sept sites de parsing basculés** : classification documentaire, les quatre prompts de génération de dataset (Q/R, résumé, classification, exemples de refus) et les deux juges LLM-as-a-judge (notation, A/B head-to-head). Ce sont exactement les endroits où une réponse hors format se traduisait par une paire perdue, un score manquant ou un document non classifié — le plus souvent en silence.
- **Le parsing défensif reste en place.** Le décodage contraint garantit un JSON *valide*, pas un JSON *conforme au schéma attendu* : les clés peuvent manquer et les types varier. Il supprime une classe de défauts, il ne dispense pas de vérifier ce qu'on reçoit.
- Le chat conversationnel n'est pas contraint : seuls les appels dont la sortie est parsée le sont.

### Migration « full Java » — audit de la surface Python, et premiers lots

Nouvel audit [`docs/process/audit-python-java.fr.md`](docs/process/audit-python-java.fr.md) : les 13 fichiers Python du dépôt (1 745 lignes) et les dépendances Python indirectes de la pile (images, healthchecks compose, convertisseurs llama.cpp téléchargés à l'exécution, jobs de CI) sont inventoriés, puis un plan de migration en quatre lots est proposé. Constat structurant : les quatre blocs Python ne sont pas de même nature — reranking, extraction PDF et outillage sont substituables en Java, l'entraînement QLoRA ne l'est pas (l'outil `llama-finetune` de llama.cpp ne fait que du full finetune FP32, sans LoRA ni DPO/ORPO). La cible retenue est donc « Java pur sur le chemin de requête, entraînement sorti du produit dans un worker appelé par HTTP » — ce qui clôt au passage le constat F1 de l'audit fine-tuning, seul blocage de déploiement encore ouvert.

**Lot 0 — trois correctifs de découplage, sans changement de comportement**

- **L'état du reranker ne dépend plus de son implémentation.** `checkHealth()` remonte dans l'interface `RerankerClient`, et `/api/status` comme `/api/health/services` injectent l'interface au lieu de la classe concrète `CrossEncoderRerankerClient`. Un moteur de reranking exécuté dans la JVM aurait sinon disparu des deux endpoints sans qu'aucun test n'échoue.
- **Convertisseurs GGUF épinglés.** `export_gguf.py` et `export_lora_gguf.py` tiraient `convert_hf_to_gguf.py` / `convert_lora_to_gguf.py` depuis la branche `master` de llama.cpp **au moment de l'exécution** : deux exports faits à quelques semaines d'intervalle n'utilisaient pas le même convertisseur — donc pas la même quantification ni les mêmes métadonnées GGUF — et rien ne le signalait. Les deux scripts délèguent désormais à un module partagé, à révision épinglée (`b9828`, alignée sur le tag de l'image llama.cpp servie par le compose) et surchargeable via `LLAMA_CPP_REVISION`. Le fichier mis en cache porte la révision dans son nom, si bien qu'une copie de `master` laissée par l'ancienne version ne gèle plus la conversion.
- **`base_models.json` là où il appartient.** Le manifeste unique des modèles de base passe dans `backend/src/main/resources/`, et la `<resource>` par laquelle `backend/pom.xml` lisait `../scripts` disparaît. Le couplage est inversé : ce sont les scripts d'entraînement, périphériques et optionnels, qui viennent chercher le manifeste dans le backend — avec repli sur un fichier voisin et surcharge `SPECTRA_BASE_MODELS_MANIFEST`, pour rester utilisables copiés seuls sur une machine d'entraînement.

**Lot 1 — le contrôle des liens de la documentation quitte Python**

- `scripts/check-doc-links.py` et le workflow `docs-links` sont remplacés par `DocumentationLinksTest` (suite de tests du backend) : même expression rationnelle, mêmes répertoires élagués, mêmes URL ignorées, mais l'élagage se fait à la descente de l'arborescence et le contrôle tourne dans le job de build existant — un lien cassé échoue donc **avant** le push, avec `fichier:ligne -> cible`. Une chaîne d'outils de moins imposée aux contributeurs.

**Lot 2, étape 1 — le harnais de comparaison du reranking, avant le portage**

Une régression de reranking ne casse rien : elle dégrade les réponses. Elle est donc invisible sans référence prise sur le service Python **avant** qu'une implémentation Java existe.

- **Corpus dérivé, pas inventé.** Les 20 questions du benchmark qualité embarqué (`benchmarks/highway_benchmark.jsonl`) deviennent les requêtes et ses 14 réponses de référence forment le vivier de passages candidats : chaque requête affronte un passage pertinent et treize distracteurs du même domaine et du même registre — le cas où un reranker se distingue d'une recherche vectorielle. Le corpus porte une empreinte SHA-256, si bien qu'une évolution du benchmark déclare la référence obsolète au lieu de la comparer en silence à autre chose.
- **Capture et vérification** (`RerankerParityTest`, désactivées par défaut) : `-Dreranker.parity.capture=<url>` écrit la référence, `-Dreranker.parity.verify=<url>` compare. Le nom du modèle est lu sur `/health`, donc celui **réellement servi** et non celui que la configuration annonce. Ordre et scores sont rapportés séparément : `-Dreranker.parity.scores=ignore` permet d'assumer un changement d'échelle (sigmoïde → logit brut) sans renoncer au contrôle de l'ordre, seul l'ordre déterminant ce que le RAG met dans son contexte.
- **Le comparateur est lui-même testé** avec des rerankers factices (classement identique, inversé, dérive de score sous et au-delà de la tolérance, nombre de résultats différent) : sans cela il pourrait rendre « aucun écart » par construction et donner une fausse assurance le jour du portage.
- **Le reranker n'avait aucun test.** `CrossEncoderRerankerClientContractTest` (12 tests, `MockWebServer`) fige le contrat à reproduire : forme de la requête, ordre des documents transmis, préservation de l'ordre du service, scores négatifs acceptés (un logit brut l'est presque toujours), et réponse vide alors que des documents ont été soumis → exception, jamais un classement identité à scores nuls.

### Reranker Python — image de nouveau constructible hors ligne, dépendances bornées

Sur une machine sans accès à `huggingface.co`, l'image du service reranker ne se construisait plus : `did not complete successfully: exit code: 1` à l'étape de pré-téléchargement du modèle. L'échec survenait après un `pip install` réussi et avec un code de sortie 1 (exception Python, non un manque de mémoire) — c'est bien le téléchargement de ~500 Mo depuis le Hub qui bloquait.

- **Pré-téléchargement rendu non fatal.** Le commentaire du `Dockerfile` le décrivait lui-même comme une optimisation (« so startup is instant »), alors que c'était la seule chose rendant l'image inconstructible hors ligne — et ce pour re-télécharger un modèle **déjà présent** dans le volume persistant `reranker-model-cache`. Le build aboutit désormais même sans Hub, et le modèle est chargé au démarrage depuis ce cache. Contrepartie assumée : sans cache *et* sans réseau, le service échoue au démarrage — avec une traceback Python lisible plutôt qu'une erreur BuildKit opaque à mi-build.
- **Dépendances transitives épinglées.** `services/reranker/requirements.txt` figeait `sentence-transformers` et `torch` mais laissait `transformers` et `huggingface-hub` libres, ces bibliothèques étant déclarées sans borne supérieure utile par `sentence-transformers`. Deux constructions de l'image à quelques mois d'écart n'installaient donc pas le même code, sans que rien ne le signale — exactement le défaut corrigé par F11 pour `scripts/requirements.txt`, que `services/` n'avait jamais reçu. Épinglés aux versions en vigueur à la publication de `sentence-transformers 5.5.0`. Même traitement pour `docling`, seule dépendance non bornée du service docparser.
- **Environnements contraints pris en charge** : `HF_ENDPOINT` (miroir d'entreprise), `HF_HUB_OFFLINE=1` (aucun accès réseau, cache exclusif) et `HF_TOKEN` sont transmis au conteneur. Forme sans valeur volontaire — une variable absente reste absente, là où `${VAR:-}` aurait injecté une chaîne vide que `huggingface_hub` prendrait pour une URL d'endpoint.

Ce blocage a par ailleurs tranché une question restée ouverte du lot 2 : l'artefact ONNX du reranker **s'exporte hors ligne** depuis ce même cache local (`optimum-cli export onnx` sur le chemin du snapshot, `HF_HUB_OFFLINE=1`), sans dépendre d'une publication ONNX en amont — et cela garantit que le modèle exporté est bien celui qui était servi, condition de validité de la comparaison de parité. Procédure dans [l'audit](docs/process/audit-python-java.fr.md).

**Lot 2, étape 2 — le moteur de reranking exécuté dans la JVM**

Un Cross-Encoder n'est qu'un encodeur à tête de classification : on tokenise la paire (requête, passage), un passage avant, un logit. Rien là-dedans n'exige Python ni un conteneur dédié.

- **`spectra.reranker.engine=onnx`** active `OnnxCrossEncoderReranker`, qui charge `model.onnx` + `tokenizer.json` depuis `./data/models/reranker` — même convention que les GGUF, un artefact déposé dans le volume des modèles. `engine=http` (le **défaut**, inchangé) conserve le microservice Python : le moteur ONNX exige un artefact local, l'imposer casserait les installations existantes. Les deux implémentations satisfont `RerankerClient`, donc bascule et retour arrière sont une propriété de configuration.
- **ONNX Runtime est appelé directement**, sans l'abstraction DJL. Le barème des scores, la troncature de la paire et les entrées fournies au graphe sont précisément ce qui déplace un classement sans lever d'erreur : les confier à une couche de conventions rendrait la comparaison à la référence de parité ininterprétable. DJL n'est gardé que pour `tokenizers`, binding JNI de la bibliothèque Rust utilisée par `transformers` — donc les mêmes `input_ids`, condition des mêmes scores. La bibliothèque native est extraite du jar, sans téléchargement à l'exécution.
- **`activation=sigmoid` par défaut**, ce qui reproduit l'échelle de `sentence-transformers` et laisse les `rerankScores` publiés par l'API comparables aux campagnes de benchmark antérieures ; `logit` publie le logit brut. Le barème ne change jamais l'ordre (la sigmoïde est monotone) — propriété vérifiée par un test.
- **Trois détails silencieux traités** : seules les entrées déclarées par le graphe sont fournies (les modèles XLM-R, dont le multilingue par défaut, n'exposent pas toujours `token_type_ids`) ; le rembourrage se fait au plus long du lot avec masque d'attention à 0, sans quoi le score d'un passage dépendrait des autres passages du lot ; une sortie qui n'est pas `[lot, 1]` ou `[lot]` est rejetée explicitement plutôt que réduite à une valeur plausible mais fausse.
- **Modèle absent = dégradation visible, pas d'échec au démarrage** : l'application démarre, `/api/status` rapporte l'indisponibilité *et sa cause*, `rerank` lève et le RAG retombe sur l'ordre vectoriel avec `rerankApplied=false`. En revanche une **configuration invalide** (activation inconnue) échoue immédiatement, comme `PipelineConfigValidator` : retomber en silence sur un barème par défaut changerait l'échelle des scores sans que rien ne l'indique.
- Le harnais de parité sait viser le moteur local : `-Dreranker.parity.verify=onnx:./data/models/reranker`. C'est ce lancement qui validera le portage — l'inférence sur modèle réel n'est pas exercée en CI (artefact de ~0,5 Go), tout le reste l'est : 33 tests sur le barème, le classement, l'encodage, le rembourrage, la configuration et la dégradation.

### Reranker — plus d'exception sur un vivier de passages vide

`CrossEncoderRerankerClient.rerank` interrogeait le service même avec une liste de documents vide, puis transformait sa réponse légitime (`results: []`) en `IllegalStateException` au message absurde (« sans résultats pour 0 document »). Le seul appelant (`RagService`) gardant déjà l'appel derrière `!allChunks.isEmpty()`, ce n'était pas atteignable — le client est néanmoins aligné sur le service, car une implémentation exécutée dans la JVM renverrait naturellement une liste vide et ne doit pas avoir à reproduire ce défaut. Économise au passage un aller-retour HTTP inutile.

Note technique : quatre nouvelles suites de tests verrouillent ces invariants — `RerankerHealthReportingTest`, `DocumentationLinksTest`, `test_llama_cpp_convert.py` (interdit le retour à une branche mobile et vérifie que la révision épinglée reste alignée sur le tag de l'image servie) et `test_base_models.py` (emplacement canonique du manifeste et mapping alias → repo, en miroir de `BaseModelCatalogTest`). Un job de CI Python sur trois est supprimé.

### RAG — la personnalisation enregistrée avec un modèle pilote enfin la génération

Le registre persiste depuis toujours une persona (`systemPrompt`) et des paramètres (`parameters`) par modèle — enregistrés par le fine-tuning, llmfit ou `POST /api/fine-tuning/models/register`. Ces champs n'étaient toutefois que de la **traçabilité** : le RAG servait une persona figée dans le code et une température issue d'un défaut du DTO. Un modèle personnalisé était donc interrogé sous une identité et des réglages qui n'étaient pas les siens.

- **Persona du modèle actif appliquée.** Le prompt système RAG est désormais composé à la génération : persona du modèle actif, puis consignes de citation, puis contexte. Un modèle sans `systemPrompt` enregistré conserve la persona canonique Spectra (`AssistantPersona`) — et les modèles issus du fine-tuning enregistrent précisément cette persona, donc la cohérence entraînement ↔ service est préservée. Les consignes de citation et le bloc de contexte ne sont **jamais** remplacés : le RAG continue de citer ses sources quelle que soit la persona. S'applique aux chemins standard, direct, Self-RAG et streaming.
- **Paramètres du modèle actif appliqués.** `temperature` et `top_p` (ou `topP`) lus dans les `parameters` du registre servent de défauts de génération. Précédence : **valeur explicite de la requête > paramètres du modèle > défauts Spectra (0.7 / 0.9)** — les curseurs du Playground et les harnais d'ablation restent donc souverains. Les clés non numériques de la carte (`jobId`, `baseModel`…) sont ignorées.
- **`GET /api/config/model` expose le profil effectif.** En plus de l'alias actif, la réponse porte `systemPrompt` (la persona réellement servie), `personaSource` (`model` ou `spectra-default`) et `parameters` (température et top-P effectifs) — la personnalisation devient vérifiable via l'API. Ajout rétro-compatible : le champ `model` est inchangé.

Note technique : `QueryRequest.temperature`/`topP` ne sont plus défaultés dans le DTO (un `null` doit rester distinguable d'une valeur explicite, sans quoi les paramètres du modèle seraient toujours masqués) ; ils sont arbitrés à l'entrée du pipeline puis figés sur la requête propagée, de sorte que le reste de la chaîne les lit toujours non nuls. Nouveau bean `ActiveModelProfileService`.

### Perf — Playground découpé en chunks chargés à la demande

- **Lazy-loading des panneaux lourds** : le dialogue de comparaison A/B (`RagComparisonDialog`) et le panneau Trace (`RagTracePanel`) sont extraits dans `components/playground/` et chargés via `React.lazy` — ils n'entrent dans le bundle que lorsque l'utilisateur les ouvre. Le chunk d'entrée du Playground passe de **~240 kB à ~59 kB** ; les deux panneaux (~8 kB et ~14 kB) sont différés. Types et constantes partagés isolés dans `playground/ragTypes.ts` pour éviter toute dépendance circulaire. Aucun changement fonctionnel.

### UI — compteurs animés sur les écrans d'évaluation

- **CountUp / SpotlightCard étendus** : le score global, la latence et le débit (tok/s) de **Comparison** s'animent au chargement (`CountUp`) ; les cartes de modules d'**Optimization** et la taille de dataset d'un job de **Fine-Tuning** reçoivent halo au survol / compteur animé. Les valeurs en direct (perte, progression d'entraînement) restent statiques pour ne pas ré-animer à chaque tick.

### RAG — citations en ligne sur le chemin Agentic (couverture universelle)

- **Citations pour l'Agentic RAG** : la boucle ReAct numérote désormais chaque passage du contexte (`[1]`, `[2]`, …) et ses prompts (ReAct + fallback) demandent au modèle de citer ses sources avec ces marqueurs — même convention que le chemin STANDARD (`sources[i]` ↔ `[i+1]`). Les puces de citation cliquables du Playground fonctionnent maintenant aussi sur les réponses agentiques (questions complexes multi-hop), sans changement côté front. Comble le dernier chemin où les citations manquaient.

### UI — effet « déchiffrement » sur l'écran de démarrage

- **StartupOverlay animé** : le titre « Spectra Core » se révèle par un effet de déchiffrement caractère par caractère (composant `DecryptedText`, sans dépendance, cohérent avec la persona). Respecte `prefers-reduced-motion` (texte final immédiat) et reste accessible (vrai texte via `aria-label`, bruit visible en `aria-hidden`).

### UI — animations discrètes (compteurs, révélation, halo au survol)

Trois primitives d'animation **sans dépendance** (inspirées de [React Bits](https://reactbits.dev), réécrites pour le design system : tokens de thème, Tailwind v4, `prefers-reduced-motion`), ajoutées au kit `components/ui` :

- **`CountUp`** — un chiffre qui s'anime jusqu'à sa valeur (et ré-anime depuis l'affichage courant quand la donnée change au polling). Câblé sur les métriques du **Dashboard** (chunks, paires, confiance, catégories, cycle de personnalisation) et les grands nombres du **panneau Trace** du Playground (itérations agentiques, chunks de contexte).
- **`AnimatedContent`** — révélation en fondu + glissement à l'entrée dans le viewport (grille « RAG Capabilities » du Dashboard).
- **`SpotlightCard`** — halo radial suivant le curseur au survol, sensible au thème (cartes « RAG Capabilities » du Dashboard, cartes « Optimizations Triggered » du Trace).

Toutes dégradent proprement : sans `IntersectionObserver`/`matchMedia` (ou en mode « réduire les animations »), le contenu s'affiche directement. Logique pure (`easeOutCubic`, `countUpValue`, `formatCount`) extraite dans `lib/animation.ts` et testée ; impact bundle négligeable (aucune lib d'animation tirée).

### Playground — visibilité RAG approfondie : citations en ligne, entonnoir de récupération, budget de tokens

Trois compléments qui ouvrent le « comment » du pipeline là où l'utilisateur ne voyait que le « quoi » :

- **Citations en ligne.** Le backend numérote désormais chaque passage du contexte (`[1]`, `[2]`, …) et demande au modèle de citer ses sources avec ces marqueurs. Côté Playground, les `[n]` de la réponse deviennent des puces **cliquables** qui déplient et défilent jusqu'à la source correspondante ; la liste des sources est numérotée et les sources **réellement citées** sont mises en évidence (« N cited »). Dégrade proprement : sans marqueur (mode DIRECT, réponse non citée), le rendu est inchangé. `RagService` (contexte numéroté + consigne de citation), `rehypeCitations` (rendu Markdown), `parseCitations` (extraction).
- **Entonnoir de récupération.** Nouveau panneau du Trace : `Récupérés → après Corrective → après Compression → contexte final`, avec le nombre de chunks retirés par chaque étape filtrante. Rend visible **où** et **par quoi** les chunks disparaissent, là où la timeline ne montrait que les durées. Reconstruit côté client depuis les compteurs de la timeline serveur (`buildFunnel`) — aucun surcoût backend.
- **Budget de tokens.** L'événement `done` porte la taille du contexte injecté (`contextChars`) ; le Trace affiche une barre **contexte récupéré (entrée) vs réponse générée (sortie)**, estimée à ~4 caractères/token (convention déjà utilisée pour les budgets long-contexte/agentique). Répond à « combien du budget est parti dans le contexte plutôt que dans la réponse ? ». `tokenBudget`/`estimateTokens`.

### Correctif — dashboard Grafana : panneaux dupliqués supprimés

- **Dédoublonnage du dashboard** (`deploy/k8s/monitoring/grafana-dashboard.yaml`, depuis retiré) : un merge côté `main` (PR #264/#265) avait introduit une **seconde copie** de quatre panneaux (« Circuit Breakers (State) », « Erreurs (Logs ERROR/WARN) », « HikariCP - Connexions », « JVM Threads (incl. Virtual) »). Le JSON importé par le sidecar Grafana affichait donc ces graphes en double. La copie superflue est retirée ; le dashboard revient à 12 panneaux uniques (ids 1 à 12), sans changement fonctionnel.

### RAG — état serveur des modules exposé (toggles et Advisor fidèles au déploiement)

Comble l'écart entre le RAG Advisor (qui recommande des variables d'environnement, donc un redéploiement) et les toggles du Playground (jusqu'ici purement navigateur, sans savoir ce qui est réellement déployé) :

- **`GET /api/config/rag`** : renvoie la disponibilité **réelle** de chaque module RAG côté serveur (bean présent = déployé via sa variable d'environnement) — adaptive, conversational, hybrid, rerank, corrective, compression, selfRag, multiQuery, agentic, semanticDedup, longContext. `RagService.moduleAvailability()`.
- **Toggles Playground fidèles** : un module non déployé apparaît **grisé, mention « OFF », interrupteur verrouillé** — plus de faux-semblant qu'on pourrait l'activer par requête (on ne peut que le désactiver s'il est déployé). Les modules déployés restent pilotables.
- **RAG Advisor conscient de l'état** : dans le guide des stratégies, un module déployé porte un badge **« ● active »** (au lieu de seulement « ✓ recommended ») — on distingue ce qui tourne déjà de ce qu'il reste à activer.

### Playground — comparaison A/B → paires DPO (boucle vers le fine-tuning)

- **Vote de préférence dans la comparaison A/B** : le dialogue « Compare » propose désormais « Which is better? » (réponse de référence vs variante sans un module). Le choix humain est enregistré comme **paire DPO** (`chosen`/`rejected`) sur la **même question** — un signal de préférence bien plus propre que l'agrégation 👍/👎. `POST /api/dataset/dpo/preference`.
- **Stockage sans collision** : les préférences vont dans un fichier `dpo_preference_pairs.jsonl` **séparé** de `dpo_pairs.jsonl` (que la génération DPO tronque et réécrit). `DpoGenerationService.getAllPairs()`/`exportJsonl()` fusionnent les deux ; les stats DPO distinguent `generatedPairs` et `preferencePairs`. Ainsi une préférence votée n'est jamais perdue par une régénération.
- **Correctif** : dans le dialogue de comparaison, le rendu de la variante utilisait `requestAnimationFrame` pour batcher les tokens — non fiable hors compositing (la variante pouvait rester vide alors que les métadonnées arrivaient). Aligné sur le même batching `setTimeout` que le chat principal ; la variante s'affiche désormais de façon déterministe.

### Observabilité & feedback — dashboards de latence par étape, analytique du feedback

Boucle les deux signaux ouverts précédemment (métriques d'étapes émises mais non visualisées, feedback enrichi mais non analysé) :

- **Latence RAG par étape dans Grafana** : le timer `spectra.rag.stage` publie désormais un histogramme (`spectra.rag.stage: true` sous `management.metrics.distribution.percentiles-histogram`). Nouveau panneau Grafana « Latence RAG par étape p95 (s) » (une série par étape : retrieval, grading, compression, génération, réflexion, agentic) et alerte Prometheus **`SpectraHighRetrievalLatencyP95`** (p95 retrieval > 5s / 15 min — isole un problème d'infra embedding/ChromaDB, distinct de la génération bornée par le modèle).
- **Analytique du feedback** : `GET /api/query/feedback/stats` agrège `playground_feedback.jsonl` en taux de 👎 **par stratégie** et **par module** (à partir du `ragMeta` enregistré par vote). Le **RAG Advisor** affiche un « Feedback signal » — taux de 👎 global et par module trié du plus problématique au moins, avec code couleur — qui rend ses recommandations data-driven (« le Corrective augmente les 👎 sur ce corpus » se lit directement). `FeedbackService.aggregate()` dégrade gracieusement (fichier absent, lignes corrompues ignorées).

### RAG & Playground — observabilité des étapes, comparaison A/B rigoureuse, feedback enrichi

- **Durées d'étapes exposées en métriques** : la timeline mesurée côté serveur alimente désormais un timer Micrometer `spectra.rag.stage{stage=…}` (retrieval, grading, compression, génération, réflexion, boucle agentique). La chronologie par requête devient de l'**observabilité agrégée** (p95 retrieval vs génération) dans Prometheus/Grafana, sans surcoût — la durée était déjà mesurée.
- **Comparaison A/B « toutes choses égales par ailleurs »** : chaque réponse mémorise les **paramètres effectifs** de sa requête (température, top-p, top candidates, surcharges de modules). La comparaison A/B rejoue à partir de **cette** configuration, pas des réglages courants de la session — la variante ne diffère plus que par le module comparé, même si l'utilisateur a changé un réglage depuis.
- **Feedback 👍/👎 enrichi** : le signal envoyé au backend joint le **pipeline de la réponse** (`ragMeta` : stratégie, drapeaux appliqués) et les **surcharges actives**. Un 👎 devient corrélable à la configuration RAG effective (« les pouces rouges arrivent surtout quand le corrective a tout filtré »), et le corpus DPO (`playground_feedback.jsonl`) s'en trouve enrichi. `FeedbackRequest`/`FeedbackService` acceptent ces champs, optionnels et rétrocompatibles.

### Documentation — guide Playground à jour (visibilité du pipeline)

- **Manuel utilisateur** ([user-manual.fr.md](docs/user/user-manual.fr.md)) : section Playground réécrite pour couvrir toutes les fonctionnalités livrées — étapes du pipeline visibles en direct, badges et bouton Trace, timeline mesurée côté serveur avec compteurs, question reformulée, toggles par module (surcharges `RagOverrides`), comparaison A/B, mode expert, RAG Advisor et export.
- **README (EN/FR)** : le bloc « Ask / Questions » met en avant la transparence du pipeline (étapes en direct, timeline, toggles, comparaison A/B).
- **C4 composants** ([c4-level-3-components.fr.md](docs/tech/c4-level-3-components.fr.md)) : libellé du composant Playground actualisé (streaming SSE, toggles, Trace/timeline/A-B).
- **Captures d'écran** : `docs/assets/playground.png` (et sa copie Hugging Face) régénérée — elle montre désormais les badges du pipeline, le % de pertinence des sources (avec l'étiquette BM25) et les métriques ; nouvelle capture `docs/assets/playground-trace.png` du panneau Trace (timeline mesurée côté serveur avec compteurs), intégrée au manuel utilisateur.
- Les références techniques (streaming SSE, événements `stage`/`replace`, champ `stages`, surcharges par requête) étaient déjà à jour côté `technical-doc.fr.md`.

### Playground — logique RAG extraite et testée (`lib/ragPipeline`)

- La logique pure du pipeline RAG côté Playground (calcul de pertinence `relevancePct`/`isBm25Only`, construction des surcharges `overridesFromDisabled`, modules appliqués `appliedModules`, formatage de la timeline `formatStageCounts`/`fmtMs`, registre `RAG_MODULES`) est extraite de `Playground.tsx` vers un module dédié [`frontend/src/lib/ragPipeline.ts`](frontend/src/lib/ragPipeline.ts), testable indépendamment du composant.
- **23 tests unitaires** ([`ragPipeline.test.ts`](frontend/src/lib/ragPipeline.test.ts)) couvrent les cas limites : chunks BM25-only (distance sentinelle 1.0), bornage de la pertinence, surcharges (jamais forcer ON, dédup du module A/B), adaptive listé seulement en AGENTIC, compteurs de timeline (`avant→après (−N)`, itérations, absence de compteur). Aucun changement de comportement — refactor à iso-fonctionnalité.

### Playground — timeline du pipeline, compteurs, toggles par module et comparaison A/B

Quatre ajouts qui approfondissent la visibilité du RAG et rendent le pipeline explorable depuis l'interface :

- **Timeline du pipeline (panneau Trace)** : chaque étape est désormais **mesurée côté serveur** (durée réelle, sans jitter réseau) et remontée dans l'événement SSE `done` (champ `stages`). Le panneau Trace affiche un waterfall — routing, retrieval, grading, compression, boucle agentique, génération, réflexion — qui répond à « où est parti le temps ? » là où le TTFT global restait muet.
- **Compteurs par étape** : la timeline porte les cardinalités (retrieval : N chunks ; corrective grading : `avant→après` avec le nombre écarté ; compression : idem ; boucle agentique : nombre d'itérations). Un badge CORR binaire devient « Corrective grading 5→3 chunks (−2) ».
- **Toggles par module** : la section « Advanced » de la configuration RAG expose des interrupteurs par module (Hybrid Search, Cross-Encoder, Multi-Query, Corrective, Compression, Self-RAG, Adaptive routing). Décocher **force le module OFF pour la requête** via les surcharges `RagOverrides` (déjà utilisées par l'ablation), désormais acceptées par `POST /api/query/stream` et persistées localement. On ne peut pas forcer ON un module absent du serveur — sémantique tri-état exacte de `RagOverrides.resolve`.
- **Comparaison A/B dans le chat** : un bouton « Compare » sur une réponse propose de la **rejouer sans un module qui a réellement agi** (menu construit depuis les drapeaux de la réponse). La référence et la variante (streamée en direct, un module désactivé) s'affichent côte à côte avec leurs badges pipeline et leurs sources — l'apport du module devient visible sur la question que l'utilisateur vient de poser, pas seulement en batch d'ablation.
- Backend : `QueryRequest` porte un champ `RagOverrides overrides` (constructeur de compatibilité conservé) ; `runStreamPipeline` résout chaque module via `RagOverrides.resolve` (parité avec le chemin non-streaming) et accumule la timeline serveur.

### Documentation & CI — audit sécurité, contrainte mono-instance, CI kustomize épinglée

- **Manuel utilisateur en anglais** ([docs/user/user-manual.en.md](docs/user/user-manual.en.md)) : traduction complète du manuel utilisateur (prérequis, démarrage, pipeline en 4 étapes, guide de l'interface, gestion des modèles, dépannage). Les deux versions se renvoient l'une à l'autre (bandeau « 🌍 »), et le hub docs / les README pointent la version anglaise avec un lien `FR` de repli.
- **Pipeline RAG en anglais** ([docs/tech/rag-pipeline.en.md](docs/tech/rag-pipeline.en.md)) : traduction du « pourquoi de chaque étape » (chunking, Multi-Query, fusion RRF, re-ranking Cross-Encoder, compression de contexte, long-context bypass). Blocs de code, chemins et clés de configuration conservés tels quels ; les deux versions se renvoient l'une à l'autre et le hub docs / le README anglais pointent la version EN.
- **Audit sécurité** ([docs/process/audit-securite.fr.md](docs/process/audit-securite.fr.md), remplace l'ancien `SECURITY_AUDIT.md` supprimé) : constat technique de la surface auth/exposition/DoS. Points saillants — pas d'identité par utilisateur (le paramètre `?actor=` de la GED est déclaratif → audit trail non probant), auth désactivée par défaut, l'activation de `SPECTRA_API_KEY` casse le SSE (`EventSource` ne peut pas envoyer d'en-tête), actuator exposé. Les entrées « externes » (SSRF, ZIP-bombs, désérialisation, traversée de chemin) sont, elles, bien durcies. Rapport d'analyse : aucun correctif d'auth appliqué (choix d'architecture à trancher).
- **Contrainte mono-instance documentée** : `spectra-api` n'est pas conçu pour tourner en plusieurs réplicas (index BM25 en mémoire, registres de tâches, H2 fichier, fan-out SSE non partagés). Nouvelle section [architecture § Scaling & Operational Constraints](docs/architecture.en.md#scaling--operational-constraints) et item de suivi dans reliability.
- **Release : garde-fou de convention de tag** (`release.yml`) : le workflow échoue si le tag ne commence pas par `v` (ex. `v0.7.1`) et avertit si les notes curées `.github/release-notes/vX.Y.Z.md` sont absentes. La release `0.7` avait été taguée sans `v`, contournant silencieusement le fichier de notes → corps générique recyclé de v0.5. Notes curées `v0.7.1.md` préparées pour la prochaine release.
- **CI kustomize épinglée** (`k8s-validate.yml`) : la validation installait kustomize « latest » depuis master — non reproductible (un nouveau kustomize peut casser un manifeste valide sans changement dans le dépôt, ce qui a fait surgir un bug de `seed` à un moment arbitraire). Version figée à `v5.8.1`, comme le pin de `LLAMA_CPP_IMAGE_TAG`.

### RAG — pipeline complet porté au streaming SSE (Adaptive, Agentic, Self-RAG) + étapes visibles en direct

Le chemin streaming (`POST /api/query/stream`, utilisé par le Playground) n'exécutait ni le routage adaptatif, ni la boucle agentique, ni le Self-RAG — réservés au chemin non-streaming que l'UI n'appelle jamais. Le pipeline streaming est désormais complet :

- **Adaptive RAG en streaming** : la question est classifiée (DIRECT / STANDARD / AGENTIC) avant le retrieval. Une question générale saute l'index et streame une réponse directe ; une question complexe déclenche la boucle agentique.
- **Agentic RAG en streaming** : la boucle ReAct s'exécute avec une **visibilité en direct** — chaque recherche complémentaire décidée par le LLM émet un événement SSE `stage` (`agentic_search`, avec numéro d'itération et requête reformulée) affiché dans la bulle de réponse. La réponse finale (produite par la boucle) est émise en un bloc ; `done` transporte `agenticIterations` et `agenticStopReason`, affichés dans le panneau Trace.
- **Self-RAG en streaming** : le brouillon est streamé normalement (TTFT préservé) puis auto-évalué (ISREL/ISSUP/ISUSE). S'il est jugé insuffisant, un événement `replace` demande au client d'effacer le brouillon avant de streamer la version raffinée. Les scores de réflexion sont exposés (`selfRagScores`) dans le tooltip du badge SELF et le panneau Trace.
- **Étapes du pipeline visibles en direct** : le backend émet des événements `stage` (`routing`, `rewriting`, `retrieval`, `grading`, `compression`, `reflection`, `refining`…) que le Playground affiche sous le curseur de streaming (« Searching the knowledge base… », « Agentic search #2… »). Ces événements servent aussi de keep-alive : ils réarment la garde d'inactivité de 120 s du frontend, qui aurait sinon coupé une boucle agentique longue sur CPU.
- Architecture : `queryStream` passe d'un `Mono.fromCallable` (muet pendant tout le setup) à un émetteur bloquant `Flux.create` sur `boundedElastic`, capable d'émettre au fil du pipeline ; `AgenticRagService` accepte un `SearchProgressListener` optionnel ; `SelfRagService` expose son évaluation décomposée (`evaluate` / `requiresRefinement` / `refineSystemPrompt`) pour le brouillon streamé.

### Playground — audit : visibilité du pipeline RAG, correctifs et fluidité (streaming)

Correctifs et améliorations issus d'un audit de la page Playground, avec pour objectif de rendre le fonctionnement du RAG visible pour l'utilisateur :

- **Badges pipeline visibles pour tous** : les badges RAG (CONV, CORR, HYB, RRNK…) et le bouton « Trace » ne sont plus réservés au mode expert — chaque réponse montre les étapes réellement appliquées. Le mode expert conserve les distances brutes et les métriques de latence.
- **Question reformulée exposée** (Conversational RAG) : l'événement SSE `done` inclut désormais `rewrittenQuestion` (question autonome utilisée pour le retrieval) et `chunkCount` (chunks injectés dans le contexte). Le panneau Trace affiche la reformulation et le nombre de chunks ; le tooltip du badge CONV montre la question réécrite.
- **Scores de retrieval affichés** : les scores Cross-Encoder (`rerankScore`) et BM25 (`bm25Score`) envoyés par le backend étaient ignorés par l'UI — ils apparaissent dans le détail des sources (mode expert) et le panneau Trace.
- **Sources BM25-only correctement étiquetées** : un chunk retrouvé uniquement par mot-clé porte la distance sentinelle 1.0 et s'affichait « 0% relevance » — il est désormais étiqueté « BM25 » (liste de sources, Trace, export Markdown).
- **Panneau Trace complété** : cartes Conversational RAG et Long-Context RAG ajoutées à la grille des optimisations (elles étaient absentes).
- **Régénération sans ancrage** : « Regenerate » renvoyait l'ancienne réponse dans l'historique conversationnel — le modèle avait tendance à la répéter. L'historique s'arrête maintenant avant le tour régénéré.
- **Historique assaini** : les messages locaux (« Welcome… », « Discussion cleared ») ne sont plus envoyés dans l'historique Conversational RAG, et le compteur « N messages in history » reflète ce qui est réellement transmis.
- **Saisie préservée** : appuyer sur Entrée avec le modèle offline (ou pendant une génération) effaçait le texte tapé sans l'envoyer.
- **Rendu du streaming fluidifié** : les tokens sont regroupés et affichés au plus toutes les ~80 ms au lieu d'un re-parse Markdown complet par token (rendu O(n²) sur les longues réponses).
- **Fin de flux sans `done` gérée** : une connexion SSE coupée proprement laissait la bulle en STREAMING (curseur clignotant à vie) — les statuts transitoires sont désormais débloqués.
- **JSON SSE toujours valide (backend)** : les événements `done`/`error` sont sérialisés via Jackson — un message d'erreur contenant `"`, `\` ou un retour à la ligne cassait le parsing côté client (le Playground affichait alors un message générique).

### GED — version, dates d'ingestion et d'archivage dans la fiche document

- La fiche document (page Database) affiche désormais la **version** (incrémentée à chaque ré-ingestion `force`), la **date d'ingestion** et — pour les documents archivés — la **date d'archivage** (`archivedAt`, base de la purge de rétention). Ces champs étaient renvoyés par l'API depuis l'audit ingestion/GED mais absents de l'UI.

### Observabilité — alertes et panneaux sur la cohérence des index

- **Deux alertes Prometheus** (`deploy/k8s/monitoring/prometheus-rules.yaml`) : `SpectraIndexDivergence` (warning — divergence FTS/ChromaDB persistante > 2h sur une collection, c.-à-d. que la réparation automatique horaire ne converge pas) et `SpectraChromaEmptyButGedPopulated` (critical — ChromaDB vide alors que la GED déclare des documents : volume perdu/reset, le RAG ne répond plus sur le corpus).
- **Deux panneaux Grafana** : chunks par magasin (ChromaDB / BM25 / GED) et divergence par collection — le tableau de bord montre d'un coup d'œil si les trois sources de vérité comptent pareil.
- **Test d'intégration multi-collections** (`ChromaDbConsistencyIntegrationTest`, Testcontainers) : la réconciliation reconstruit l'index BM25 depuis un **vrai** ChromaDB pour la seule collection divergente, en laissant intacte une collection cohérente — le chemin multi-collections (corrigé pendant l'audit) n'était couvert que par des tests mockés.
- **Instantané de cohérence au démarrage** (`ConsistencyReconciliationService.snapshotOnStartup`, `ApplicationReadyEvent`) : au boot, les comptes DB / ChromaDB / BM25 sont comparés une fois et journalisés (INFO si cohérent, WARN par collection divergente, ERROR si ChromaDB est vide alors que la GED déclare des chunks — volume vectoriel perdu/reset). Les gauges Prometheus sont peuplées immédiatement au lieu d'attendre le premier cycle de réconciliation (T+2 min). Purement informatif : ne déclenche aucune reconstruction et ne bloque jamais le démarrage (ChromaDB peut encore être indisponible au boot).

### UI — erreurs d'ingestion par fichier visibles (succès partiels)

- **Live Ingestion Stream** : une tâche terminée dont certains fichiers ont échoué n'apparaît plus comme un succès plein — la ligne passe en avertissement « N chunks · partiel » (icône et barre en couleur d'erreur) avec le détail de chaque échec (`fileErrors`) sous le fichier concerné, et un toast signale la fin de tâche partielle. Le backend remontait ces erreurs depuis l'audit ingestion/GED ; l'UI les ignorait.
- **Panneau global des tâches** : les échecs par fichier d'une ingestion partielle sont repris dans la ligne de la tâche (champ erreur), visibles depuis n'importe quelle page.
- **Relance d'une ingestion échouée / partielle** (page Ingestion) : un bouton « Relancer » apparaît sur chaque ligne en échec ou en succès partiel et ré-injecte la source d'origine encore en mémoire (fichier uploadé ou URL). La déduplication SHA-256 côté serveur rend la relance sûre — les fragments déjà ingérés sont ignorés, seuls les fichiers en échec retentent leur chance. Auparavant, une erreur transitoire (timeout LLM, circuit ChromaDB ouvert) imposait de re-sélectionner les fichiers à la main.
- **Relance inter-pages d'une ingestion d'URLs** (centre d'activité global) : une tâche d'ingestion d'URLs échouée porte un bouton « Relancer » depuis n'importe quelle page — les URLs (toutes les entrées `files` sont des URLs http(s)) sont ré-injectées côté serveur. Un upload de fichier n'expose pas ce bouton (octets non conservés, non ré-injectables sans re-sélection).
- **Validation fail-fast de la configuration au démarrage** (`PipelineConfigValidator`) : les combinaisons incohérentes (overlap de chunking ≥ taille de chunk → aucune progression, lot d'embedding ≤ 0, re-ranker/multi-query/long-context activés avec un paramètre invalide, `max-active-ingestions` < 0) refusent le démarrage avec un message clair listant **toutes** les erreurs, au lieu d'échouer plus tard à la première ingestion/requête. Une configuration par défaut passe sans bruit.
- **Contre-pression à la soumission d'ingestion** (`spectra.pipeline.max-active-ingestions`, défaut `0` = illimité) : au-delà du plafond de tâches actives (PENDING/PROCESSING), une nouvelle soumission est rejetée en **HTTP 429** *avant* toute écriture temporaire. Le sémaphore bornait déjà le traitement concurrent, mais rien ne bornait le nombre de tâches en attente — un flot de soumissions empilait fichiers temporaires et entrées de registre (en mémoire, mono-instance) plus vite que le sémaphore ne les draine. Le 429 porte un en-tête **`Retry-After`**, incrémente le compteur Prometheus **`spectra.ingestion.rejected`**, et l'UI Ingestion affiche un toast « Serveur occupé » non alarmant (la ligne reste relançable).
- **Authentification API compatible SSE** (`ApiKeyFilter`) : la clé est désormais résolue depuis le header `X-API-Key`, sinon le **paramètre de requête `apiKey`**, sinon le **cookie `X-API-Key`**. L'API navigateur `EventSource` ne peut pas envoyer d'en-tête personnalisé ; sans ce repli, activer `SPECTRA_API_KEY` renvoyait 401 sur tous les flux SSE (audit sécurité S3). ⚠️ Le paramètre de requête peut apparaître dans les journaux ; préférez le cookie ou le header hors SSE. (Le mécanisme de saisie de la clé côté UI reste à faire.)

---

## [1.13.0] — 2026-07-18

### Qualité & performance — tests d'intégration ChromaDB réel, lot d'embedding ×3

- **Tests d'intégration contre un vrai ChromaDB** (`ChromaDbConsistencyIntegrationTest`, Testcontainers) : les scénarios critiques de l'audit — ré-ingestion forcée sans duplication, suppression purgeant vecteur + BM25, homonymes protégés par l'identité `sha256` — sont désormais vérifiés de bout en bout contre un conteneur `chromadb/chroma` jetable (même image que la stack). En CI le conteneur démarre automatiquement ; sans Docker le test est ignoré, ou peut viser un serveur existant via `SPECTRA_TEST_CHROMA_URL`. Ces bugs étaient invisibles aux tests unitaires (ChromaDB mocké partout).
- **Lot d'embedding par défaut 10 → 32** (`SPECTRA_EMBEDDING_BATCH_SIZE`) : 500 chunks = 16 requêtes HTTP au lieu de 50. Abaisser sur CPU très lent.
- **`SPECTRA_EMBEDDING_TIMEOUT` enfin câblé au client d'embedding** : la variable documentée n'alimentait que `spectra.pipeline.embedding-timeout-seconds`, que rien ne consommait — le client llama.cpp utilisait son propre défaut (30 s). Elle pilote désormais le timeout réel des requêtes `/v1/embeddings` (défaut relevé à 60 s pour couvrir un lot complet sur CPU lent).

### Ingestion & GED — cohérence des index, suppression unifiée, erreurs visibles (audit)

Correctifs issus de l'[audit ingestion/GED](docs/process/archive/audit-ingestion-ged.fr.md) (PR #244, #249) :

- **Ré-ingestion `force=true` = remplacement** : les anciens chunks sont purgés (ChromaDB + BM25) avant la ré-indexation — chaque force dupliquait auparavant tous les chunks du document dans les réponses. C'est aussi la voie de réparation d'un document partiellement indexé.
- **Identité `sha256` des chunks** : les suppressions/remplacements ciblent le contenu, plus le nom de fichier — deux documents homonymes ne partagent plus leur sort (repli `sourceFile` pour les chunks historiques).
- **Suppression unifiée** : `DELETE /api/documents/{sourceFile}` supprime désormais aussi la fiche GED (la dédup SHA-256 ne bloquait plus la ré-ingestion d'un document devenu invisible du RAG) ; la purge de rétention passe par la suppression complète (DB + index) au lieu de laisser les chunks servis à vie.
- **Erreurs par fichier** : nouveau champ `fileErrors` dans le suivi des tâches d'ingestion (upload, exécuteur, URLs) ; une tâche dont tous les fichiers échouent finit `FAILED` au lieu d'un faux `COMPLETED` à 0 chunk. Nouvelle colonne `ingestion_tasks.file_errors` (migration idempotente).
- **Garde-fous** : limite de taille décompressée appliquée aussi aux uploads directs ; l'ingestion URL/batch passe par le sémaphore de concurrence et la réservation in-flight (heartbeat pour les ingestions plus longues que le TTL).
- **Rétention sur la date d'archivage** : nouvelle colonne `ingested_files.archived_at` (posée à l'archivage, effacée au retour) — la purge n'éliminait plus un vieux document fraîchement archivé ; `incrementVersion` rafraîchit `ingestedAt`.
- **Score de qualité atteignant 1.0** : l'ancienne pondération plafonnait le score réel à 0.86 — un seuil d'auto-qualification ≥ 0.9 ne qualifiait jamais rien.
- **Cycle de vie `TRAINED` automatique** : en fin de fine-tuning réussi, les documents sources du dataset sont liés au modèle (`TRAINED_ON`) et avancent vers `TRAINED` via la machine à états — ces liens n'étaient posés que manuellement.
- **FTS/BM25** : le rebuild fusionne avec l'index vivant au lieu de l'écraser, l'index disque est validé contre ChromaDB (fraîcheur), le flush passe à 30 s ; réconciliation étendue à toutes les collections (GED + flux Kafka) avec gauges par collection.
- **Divers** : locale de chunking configurable (`SPECTRA_CHUNK_LOCALE`), formats `.md`/`.markdown`/`.csv` supportés, profondeur d'aplatissement JSON bornée, delete ChromaDB par filtre `where`, suppressions SQL en masse, tri des tâches, filtre tag échappé.

### Documentation — renommage kebab-case, liens réparés, référence de configuration complète

Correctifs issus de l'[audit documentation](docs/process/audit-documentation.fr.md) :

- **Convention de nommage unifiée** : les documents de `docs/` passent en kebab-case suffixé langue (`getting-started.en.md`, `technical-doc.fr.md`…) — les 30 liens internes cassés (dont toute la section Documentation des READMEs) sont réparés.
- **`getting-started` exécutable tel quel** : chemins réels (`scripts/`, `deploy/docker`, `deploy/k8s`), URL du dépôt, renvois GKE redirigés vers `deploy/k8s/README.md`.
- **Java 25 réaligné partout** : `pom.xml` et la CI, rétrogradés à 21 par un commit d'optimisation CI sans trace, reviennent à la cible **25** — conformément à la migration documentée ici même et aux images Docker (Temurin 25). Prérequis contributeur : JDK 25. Suite de tests et SpotBugs validés sous JDK 25.
- **`configuration.en.md` complet** : ~40 variables ajoutées (bloc Kafka entier, llmfit, gardes-fous d'ingestion, `SPECTRA_CHUNK_LOCALE`, reranker, évaluation…) ; `spectra.ged.auto-retrain-threshold` désormais câblée dans `application.yml` comme les autres propriétés.
- **Exactitude** : liste des formats unifiée (table de référence unique dans `technical-doc`), pipeline d'ingestion corrigé (dédup SHA-256, BM25 toujours indexé, machine à états réelle), sémantique `force`/URLs corrigée dans le manuel, section « fiche document / cycle de vie » ajoutée au manuel.

### Model Hub — GGUF orphelins supprimables, rétention de l'historique, doc à jour

- **Suppression des GGUF orphelins depuis l'UI** (`DELETE /api/models/hub/storage/files?file=…` + bouton dans le panneau Stockage) : un fichier présent dans `data/models/` mais absent du registre (déposé à la main, laissé par un incident) était visible dans le rapport de stockage mais insupprimable sans shell — la suppression de modèle exige un alias enregistré. Garde-fous : nom simple uniquement (anti-traversée), fichier directement dans `models-dir`, refus en 409 s'il est encore référencé par le registre (retirer le modèle dans ce cas).
- **Rétention de l'historique des installations** (`InstallationRetentionService`, propriété `llmfit.installations-retention-days`, env `LLMFIT_INSTALL_RETENTION_DAYS`, défaut `0` = conserver) : cron nocturne purgeant les jobs **terminaux** (COMPLETED/FAILED/CANCELLED) plus vieux que N jours — même convention que les rétentions GED et Kafka. Les jobs non-terminaux ne sont jamais purgés (la réconciliation au démarrage les traite d'abord).
- **Documentation utilisateur à jour** : le manuel (`user-manual.fr.md` § Gestion des modèles) documente le panneau Stockage (volume + cache llmfit, purge des doublons, suppression des orphelins), l'historique des installations (bouton Réessayer, rétention) et le badge du modèle actif ; la documentation pédagogique corrige « copié » → « déplacé » et décrit le cycle de vie du stockage. Variables `LLMFIT_CACHE_DIR` / `LLMFIT_INSTALL_RETENTION_DAYS` ajoutées à `.env.example` et transmises par docker-compose.

### UI — relance des installations échouées, badge modèle actif cliquable

- **Bouton « Réessayer » dans l'historique des installations** : un téléchargement FAILED ou CANCELLED se relance en un clic avec les mêmes paramètres (modèle, quantisation, auto-activation) — le job les porte déjà. Le serveur répond 409 si un téléchargement du même modèle est déjà en cours.
- **Badge du modèle actif cliquable** : le nom du modèle affiché dans le header ouvre le Playground, où l'on change de modèle actif.
- **Logique de préremplissage extraite et testée** (`lib/fineTuningPrefill.ts`) : `suggestModelName`, `resolveTrainableBase` (métadonnée → hfRepo → nom) et `shouldReplace` (« ne jamais écraser une saisie utilisateur ») sont désormais des fonctions pures couvertes par 12 tests vitest.

### UI — modèle actif visible et formulaire de fine-tuning prérempli

- **Modèle actif affiché dans le header** : le nom du modèle de chat actif apparaît désormais en clair à côté de l'indicateur « Chat » (il n'était visible qu'en infobulle), sur toutes les pages.
- **Fine-tuning : champs préremplis d'après le modèle actif** : à l'ouverture du formulaire, le nom du modèle à produire est suggéré depuis le modèle actif (`<actif>-ft`, conforme au schéma de nommage) et le **modèle de base entraînable** est résolu automatiquement — métadonnée `baseModel` d'un modèle déjà fine-tuné, correspondance `hfRepo` avec le catalogue `base_models.json`, ou alias du catalogue contenu dans le nom. Le GGUF servi n'étant pas ré-entraînable, c'est bien la base du catalogue qui est proposée, avec un bandeau « Modèle actif : … » explicitant le préremplissage. Une valeur saisie par l'utilisateur n'est jamais écrasée (seuls les défauts génériques et les suggestions précédentes le sont).

### Model Hub — cache llmfit visible et purgeable ; catalogue des bases dans le formulaire

- **Le rapport de stockage inventorie le cache llmfit** (`GET /api/models/hub/storage`, section `llmfitCache`) : chaque GGUF du cache de téléchargement (`llmfit.cache-dir`, défaut `~/.llmfit`, env `LLMFIT_CACHE_DIR`) avec sa taille et un drapeau `duplicate` quand un fichier de même nom **et** même taille existe déjà dans `data/models/`. Cet espace — doublons hérités d'avant le passage copie → déplacement, téléchargements partiels d'installations annulées — était totalement invisible.
- **Purge des doublons du cache llmfit** (`POST /api/models/hub/storage/llmfit-cache/purge` + bouton dans le panneau Stockage du Model Hub) : supprime uniquement les doublons sûrs (même nom + même taille dans `data/models/`) et conserve les téléchargements partiels (réutilisables par llmfit au prochain essai). Garde-fou : purge refusée si le cache et `data/models/` se recouvrent (les « doublons » seraient les fichiers servis eux-mêmes).
- **Fine-tuning : catalogue des bases sous le champ « Modèle de base »** : le champ propose désormais les alias de `base_models.json` (datalist avec descriptions — taille, GPU requis…) tout en conservant la saisie libre d'un repo HuggingFace complet, avec un rappel des alias valides sous le champ. Fini les 400 « modèle de base inconnu » à l'aveugle.

### Model Hub — fin du doublon du cache llmfit et des faux « COMPLETED »

- **Le GGUF téléchargé est déplacé, plus copié** (`LlmFitService.moveToSharedVolume`) : quand `llmfit` télécharge dans son propre cache (`~/.llmfit/…`), le fichier était copié vers le volume des modèles et l'original restait — chaque modèle occupait **deux fois sa taille**, et cet espace était invisible puisque le rapport de stockage n'inventorie que `data/models/`. Le fichier est désormais déplacé (`Files.move`, rename instantané sur le même système de fichiers) avec repli copie + suppression best-effort de la source (avertissement dans les logs si elle subsiste).
- **GGUF introuvable après un exit 0 = FAILED, plus COMPLETED** : si `llmfit` sortait en succès sans qu'aucun fichier `.gguf` ne soit détecté (ni dans sa sortie, ni par scan de `models-dir`), le job était marqué COMPLETED (« non enregistré ») et s'affichait **en vert** dans l'historique alors que le modèle n'était ni copié, ni enregistré, ni activable. Le job passe désormais en **FAILED** avec un message actionnable, et le flux SSE signale l'erreur au lieu d'émettre 100 % + succès.

### Déploiement k8s/GKE — le chat suit le modèle actif (fin de « modèle actif ≠ modèle servi »)

- **Superviseur piloté par le registre en k8s** : `llama-cpp-chat` lance désormais `scripts/llm-chat-entrypoint.sh` (intégré aux images `Dockerfile.llama` / `Dockerfile.llama.cuda`) au lieu de servir un fichier figé. Il lit le pointeur `active-chat-model` du volume des modèles et redémarre llama-server à chaud à chaque changement (POST /api/config/model, activation post-fine-tuning, installation llmfit auto-activée) — même convergence automatique qu'en docker-compose, plus de redéploiement manuel.
- **Volume des modèles partagé avec le chat** : `06-llama-chat.yaml` monte le PVC `models` (lecture seule) et non plus le PVC `fine-tuning` ; le modèle de chat par défaut et les modèles installés/fine-tunés vivent tous à côté du registre. `02-pvc.yaml` documente la nouvelle contrainte de co-scheduling (ou RWX multi-nœuds).
- **Variables alignées sur docker-compose** : `spectra-api-config` renseigne `SPECTRA_LLM_CHAT_FILE` / `SPECTRA_LLM_EMBEDDING_FILE` (source des modèles par défaut → pointeur servable dès le 1er boot) ; `llama-chat-config` passe aux variables du superviseur (`LLM_CHAT_MODEL_FILE/NAME`, `LLM_PORT`, `MODELS_DIR`, hints `LLM_*`, `LLM_CHAT_EXTRA_ARGS` pour le GPU). L'overlay GPU règle l'offload via `LLM_CHAT_EXTRA_ARGS` (et non plus `LLAMA_NGL`).
- **Seeding cohérent** : `k8s/seed/seed-models.yaml` télécharge le modèle de chat dans le volume `models` (et non plus dans `fine-tuning/merged/model.gguf`), avec un nom de fichier aligné sur `SPECTRA_LLM_CHAT_FILE`. Docs (`k8s/README.md`, `scripts/gke-seed-models.sh`) mises à jour.

### Model Hub — boucle « comparatif → qualité mesurée »

- **Comparaison qualité asynchrone et suivie** (`QualityBenchmarkService.submitCompare`, `QualityCompareJob`, `POST /api/quality-benchmark/compare/async`, `GET /compare/{jobId}`, `GET /compare`) : le benchmark tenu à l'écart est lent (plusieurs appels LLM par question, ×2 modèles), donc piloté comme un job de fond suivi (PENDING → RUNNING → COMPLETED/FAILED) plutôt qu'en requête HTTP bloquante. Une seule comparaison à la fois (bascule du modèle actif), persistée en JSON et réconciliée au démarrage (jobs orphelins → FAILED), à l'image d'`EvaluationService`.
- **Le Model Hub relie fit matériel et qualité réelle** : après une installation **auto-activée**, l'API mémorise le modèle actif remplacé (`InstallationJob.previousActiveModel`) et l'UI propose directement de lancer le benchmark qualité du nouveau modèle (candidate) contre le précédent (baseline) — sur **votre corpus**. Le composant `QualityBenchmarkCta` sonde le job et affiche le face-à-face (exactitude /10, taux d'hallucination, refus corrects) avec deltas et verdict. On choisit ainsi sur des chiffres mesurés, pas seulement sur le score de compatibilité de llmfit.

### Model Hub — installations persistantes (reprise après redémarrage)

- **Suivi persisté des téléchargements** (`installation_jobs` en H2, `InstallationJob`/`InstallationJobEntity`/`InstallationJobRepository`) : un redémarrage de l'API tuait le sous-processus `llmfit` et effaçait tout le suivi (les sinks SSE ne vivaient qu'en mémoire). Chaque installation est désormais persistée de bout en bout (PENDING → DOWNLOADING → REGISTERING → COMPLETED/FAILED), progression comprise — même pattern que les jobs de fine-tuning.
- **Réconciliation au démarrage** (`LlmFitService.reconcileInterruptedInstallations`, `@PostConstruct`) : tout job resté non-terminal (orphelin de l'ancienne JVM) est marqué **FAILED** (« Interrompu par un redémarrage du serveur ») au lieu de rester figé. L'historique redevient honnête.
- **Historique interrogeable** : `GET /api/models/hub/installations` (liste, plus récentes d'abord) et `GET /api/models/hub/installations/{jobId}`. Panneau repliable « Installation history » dans le Model Hub (statut, progression, erreur), rafraîchi tant qu'un téléchargement est en cours.

### Ingestion streaming Kafka — enrichir le RAG au fil de l'eau (données vivantes)

- **Consumer Kafka** (`KafkaIngestionListener`, `KafkaConfig`) : source d'ingestion continue en plus des uploads/URLs. **Désactivé par défaut** (`spectra.kafka.enabled=false`) — aucun bean Kafka créé, démarrage inchangé. Commit **manuel** des offsets après indexation (*at-least-once*), retries + **Dead Letter Topic** `<topic>.DLT`, concurrence et sécurité SASL/SSL configurables.
- **Upsert par identité métier** (`IngestionService.upsertFromStream`) : la clé du message devient `sourceFile = kafka://<topic>/<key>` ; une nouvelle version **remplace** l'ancienne (`deleteBySource` sur ChromaDB *et* BM25, puis réindexation). Valeur nulle = **tombstone** (suppression). **Idempotence** par empreinte de contenu (absorbe les rejeux). Suivi d'état en base (`kafka_stream_source` : `content_hash`, `version`, `last_updated_at`).
- **Correctif** : `ChunkingService` propage désormais `sourceFile` dans la métadonnée des chunks, quel que soit le format — `ChromaDbClient.deleteBySource` (filtre `where sourceFile == X`) ne fonctionnait auparavant que pour les fichiers TXT, cassant silencieusement la suppression/upsert côté vecteur pour PDF/DOCX/JSON/Avro/XML.
- **Mapping de champs configurable** (`KafkaPayloadMapper`) : payload brut par défaut ; `content-field` (nom simple ou pointeur JSON) pour n'indexer qu'un champ d'un événement structuré, `metadata-fields` pour recopier des champs en métadonnées.
- **Fraîcheur temporelle** : chaque chunk du flux porte `ingestedAt` et `eventTime` (timestamp Kafka), exploitables pour un filtrage/tri par récence.
- **Rétention** (`KafkaStreamRetentionService`) : cron nocturne purgeant les sources non mises à jour depuis `retention-ttl-days` jours (0 = désactivé).
- **Métriques Micrometer** : `spectra.kafka.messages{topic,result}` et `spectra.kafka.processing{topic}` sur `/actuator/prometheus`.
- **Déploiement** : profil Docker `kafka` (Apache Kafka mode KRaft mono-nœud) dans `docker-compose.yml`, variables `SPECTRA_KAFKA_*` (`.env.example`). Dépendance `spring-kafka` (gérée par le BOM Spring Boot).
- **Documentation** : `docs/design-kafka-streaming-upsert.fr.md` (design détaillé), sections dédiées dans le README, la doc technique, le manuel utilisateur et le mini-livre pédagogique.

### Évaluation — mesure des gains des enrichissements LLM

- **Ablation A/B des enrichissements** (`POST /api/ablation`, `RagAblationService`) : mesure le gain marginal du **RAG** et du **fine-tuning** de bout en bout. Contrairement à `/api/quality-benchmark` (modèle brut), chaque question du benchmark tenu à l'écart passe dans le **pipeline RAG complet**, et plusieurs configurations (**bras**) sont comparées sur le même jeu. Chaque bras reporte trois familles de métriques : génération (exactitude LLM-juge, hallucination, refus), retrieval et latence (`avgLatencyMs`, `p50LatencyMs`). Corps vide = matrice par défaut « LLM seul vs RAG » ; chaque bras peut fixer un `model` (base vs fine-tuné) et `useRag`.
- **Ablation module par module** : surcharges par requête (`RagOverrides`, tri-état) threadées dans `RagService.query`/`retrieveContext` — chaque module d'optimisation (rerank, hybride, multi-query, corrective, compression, self-RAG, adaptive, conversational) peut être forcé actif/inactif par bras pour mesurer son apport marginal. Presets `cumulative` et `leave-one-out`.
- **Métriques de retrieval déterministes** (`RetrievalMetrics`) : **Hit@k**, **MRR** et **Recall@k**, calculées sans LLM à partir des sources renvoyées et d'un champ optionnel `expectedSources` dans le benchmark JSONL. Isolent la qualité de la *récupération* de celle de la *génération*.
- **Validation** des options : chaque bras renvoie `appliedCounts` (nombre de requêtes où chaque module a réellement agi), pour confirmer que la surcharge a pris effet.
- **Écran « Optimisation »** (frontend) : page dédiée et pédagogique — explication de chaque option, presets (gain du RAG, ablation cumulative, leave-one-out, gain du fine-tuning), tableau de deltas couleur, badges des modules déclenchés et légende des métriques. **Export CSV** du tableau (valeurs brutes, BOM UTF-8 pour Excel).
- **Confiance statistique** : paramètre `runs` (répétitions par bras) → moyenne ± écart-type par métrique (`stdDev`), et **deltas non significatifs grisés** dans l'UI (≤ σ combiné) pour ne pas sur-interpréter le bruit sur un petit benchmark.
- **Coût en tokens** : `avgContextTokens` par bras (estimation déterministe du contexte injecté), en complément de la latence (bruitée) — colonne dédiée + axe du nuage coût/qualité.
- **Graphiques** (recharts) : barres d'exactitude avec barres d'erreur (±σ), nuage coût/qualité (tokens vs exactitude, frontière de Pareto), waterfall du gain marginal par module.
- Requêtes d'ablation émises à température 0 pour des deltas reproductibles.
- `QualityBenchmarkService` : extraction de `judgeAnswer`, `aggregate` et `loadBenchmark` (réutilisés par l'ablation, découplage production/notation de la réponse).
- **Benchmark annoté + corpus aligné** : `highway_benchmark.jsonl` enrichi d'`expectedSources` sur les 14 questions répondables, et nouveau corpus `examples/highway/` (4 documents : procédures, événements, nomenclature, réglementation) qui répond à ces questions — ingérez-le pour activer Hit@k/MRR/Recall@k sans configuration.

---

## [1.12.0] — 2026-06-25

### Infrastructure & déploiement

- **Migration Java 25 (LTS)** : niveau de compilation et JDK de build passés de 21 à 25 (le runtime était déjà `eclipse-temurin:25-jre`). Spring Boot 4.1 supporte le JDK 25.
- **Script de création du cluster GKE** : `scripts/gke-create-cluster.sh` — idempotent (active les APIs, crée le cluster, récupère les credentials), node pool dimensionné pour l'empreinte des manifests, node pool GPU optionnel.
- **Seeding automatique des modèles GGUF** : `k8s/seed/` + `scripts/gke-seed-models.sh` — un Job télécharge les modèles directement sur les PVC (idempotent), à la place de la copie manuelle `kubectl cp`.
- **Ingress GKE natif + TLS managé** : overlay `k8s/overlays/gke/` — `ManagedCertificate` (TLS auto, sans cert-manager), redirection HTTP→HTTPS (`FrontendConfig`), `BackendConfig` avec `timeoutSec: 3600` pour ne pas couper les flux SSE, frontend en NEG/ClusterIP.

### Observabilité

- **Alertes Prometheus + dashboard Grafana** : overlay `k8s/monitoring/` — `ServiceMonitor`, `PrometheusRule` (API down, taux 5xx, latence RAG p95, heap JVM), dashboard Grafana auto-importé. Exploite les métriques `/actuator/prometheus` (tag `application=spectrallm`) de la v0.6.
- **Pas d'HPA sur `spectra-api`** (volontaire) : le backend est *stateful* (H2 fichier, BM25 en mémoire, PVC RWO en écriture, `Recreate`) et doit rester à 1 réplica ; l'autoscaling se fait au niveau des nœuds. Rationale dans `docs/DEPLOY_GKE.md` §9.

### Documentation

- `DEPLOY_GKE.md` : nouvelles sections seeding (§7), TLS managé (§8), observabilité (§9).
- README (EN/FR) + `k8s/README.md` : section déploiement enrichie (seeding, overlays GPU/GKE/monitoring) ; correction du chemin `kubectl apply -k k8s/base`.
- Commentaires pédagogiques (Javadoc) sur les classes cœur du backend (ingestion, RAG, ChromaDB, chunking, dataset, extraction…).

### CI

- `k8s-validate` : `kustomize build` + `kubeconform` étendus aux overlays `gke`, `seed` et `monitoring`.

---

## [1.11.0] — 2026-06-25

### Nouvelles fonctionnalités — Déploiement Cloud (GKE)

- **Déploiement automatisé sur Google Kubernetes Engine** : workflow `.github/workflows/deploy-gke.yml` — authentification **Workload Identity Federation** (OIDC, sans clé JSON de compte de service), build & push des images `spectra-api` / `spectra-frontend` / `spectra-llama-cpp` vers Artifact Registry, puis `kustomize`-apply de `k8s/` sur push vers `main`. `concurrency` annule le run précédent sur le même ref ; l'étape de rollout attend `llama-cpp-embed` / `llama-cpp-chat` (timeouts généreux pour le chargement du modèle).
- **`docs/DEPLOY_GKE.md`** : guide complet de mise en place GCP (Artifact Registry, compte de service deployer, Workload Identity Federation) et liste exacte des secrets/variables à créer.
- **`Dockerfile.llama`** : nouvelle image `spectra-llama-cpp` avec l'entrypoint `llama-autostart.sh` intégré (l'ancien `--target llama_cpp_runtime` n'existait plus dans le `Dockerfile` racine).

### Nouvelles fonctionnalités — Accélération GPU (opt-in)

- **`Dockerfile.llama.cuda`** : variante CUDA de l'image llama (base `ghcr.io/ggml-org/llama.cpp:server-cuda`) avec le même entrypoint autostart.
- **Overlay kustomize `k8s/overlays/gpu/`** : patche le ConfigMap (`LLAMA_NGL=-1`) et ajoute `nvidia.com/gpu: 1` + toleration au déploiement `llama-cpp-chat`. Appliqué via `kubectl apply -k k8s/overlays/gpu`. Le déploiement reste **CPU par défaut** ; l'embedding GPU est laissé en option commentée (2ᵉ GPU requis). Section GPU ajoutée à `docs/DEPLOY_GKE.md` (création du node pool GPU, build de l'image CUDA, dépannage).

### Nouvelles fonctionnalités — Observabilité

- **Scrape Prometheus réel** : annotations `prometheus.io/scrape|port|path` sur les pods `spectra-api` (`k8s/07-spectra-api`) — `/actuator/prometheus` est désormais effectivement collecté.
- **`ObservabilityConfig`** : bean `TimedAspect` (active `@Timed` sur les beans) + `MeterRegistryCustomizer` ajoutant le tag commun `application=spectrallm` à toutes les métriques (robuste au changement de package Spring Boot 4).
- **Histogrammes** : `http.server.requests` et `spectra.rag.query` exposent des percentiles (SLO HTTP 50 ms…5 s) côté Prometheus/Grafana ; `RagService.query` annoté `@Timed("spectra.rag.query")` isole la latence RAG de l'overhead HTTP, métrique unifiée pour toutes les stratégies.

### Fiabilité — ChromaDB cosinus

- **Création de collection robuste à la version** : `ChromaDbClient.getOrCreateCollection()` crée les collections avec une configuration HNSW explicite (`space=cosine`, `ef_search=100`, `ef_construction=200`) au lieu du défaut L2 de ChromaDB. Le cosinus rend les scores de similarité interprétables sur `[0,1]` (vecteurs normalisés de llama.cpp), cohérent avec la métrique par message. Tente l'API 1.x (`configuration.hnsw`), repli sur métadonnées `hnsw:*` (versions antérieures), puis création simple — le cosinus est appliqué quelle que soit la version sans jamais casser la création (4xx → repli, autres erreurs → propagées au circuit breaker/retry). *La distance est figée à la création : les collections existantes conservent leur config (ré-ingestion requise pour basculer en cosinus).*

### Fiabilité — Kubernetes / auto-réglage

- **Fallback cgroup v1** : `llama-autostart.sh` détecte CPU et RAM en cgroup v2 *puis* v1 (`cpu.cfs_quota_us`/`cfs_period_us`, `memory.limit_in_bytes`), avec garde sur la valeur « illimité » (~`LLONG_MAX`). Couvre les node pools non-COS où l'ancien code retombait sur les ressources du nœud entier (sur-threading / contexte trop grand).
- **QoS Guaranteed** : pods `llama-cpp-embed`, `llama-cpp-chat` et `spectra-api` en `requests == limits` (init containers compris) → réservation stable, pas de throttling CPU sous contention, et l'auto-réglage voit la taille réellement allouée.

### Performance

- **`LLAMA_PARALLELISM=4`** sur `llama-embed-config` (`k8s/01-configmap`) : le serveur d'embedding démarrait avec 1 slot (vs 4 en docker-compose), sérialisant les batches et bridant l'ingestion. Gain même sur CPU.

### Améliorations frontend — GED

- **Liste de documents scrollable** : la vue groupée et la vue plate de la GED (Pipelines) sont enveloppées dans un conteneur `max-h-[70vh] overflow-y-auto` ; en-têtes de colonnes et pagination restent hors zone de défilement (en-têtes visibles).
- **Rendu paresseux natif** : `content-visibility: auto` (`.cv-auto`) sur les lignes — effet « virtualisation » sans dépendance ni refactor — et en-têtes de colonnes collants (sticky) pour les longues listes.

### CI / Tests

- **Workflow `k8s-validate`** (PR + push) : `kustomize build` de la base et de l'overlay GPU, validés par `kubeconform`. Attrape un manifeste cassé avant le merge.
- **`ChromaDbClientTest`** : via MockWebServer, vérifie que le payload de création de collection contient `configuration.hnsw.space=cosine` — verrou anti-régression (tout retrait silencieux du cosinus casse le test). Dépendance de test `okhttp3 mockwebserver` ajoutée.
- **CI GKE durcie** : authentification WIF-only (suppression du chemin `credentials_json`), variables d'environnement pour la config GCP non sensible, Node.js 22 → 24.

### Correctifs

- **k8s 05/06** : champ `entrypoint:` invalide remplacé par `command:` — le spec de conteneur était rejeté par l'API Kubernetes.
- **Comptage de chunks** : correction de cohérence du compte de chunks en fin d'ingestion.

### Documentation

- **Guide pédagogique réécrit** : `documentation-pedagogique.fr.md` réorganisé en « mini-livre » des idées et algorithmes ; cross-links EN/FR ajoutés depuis le README.

---

## [1.10.0] — 2026-06-12

### Correctifs — Chat / RAG streaming

- **SSE tokens vides (`event:token` silencieux)** : `LlamaCppChatClient` — racine identifiée dans le `ServerSentEventHttpMessageReader` de Spring qui supprime le préfixe `data: ` avant d'émettre. Le filtre `.filter(l -> l.startsWith("data: "))` ne matchait donc jamais. Corrigé : filtre remplacé par `.filter(data -> !data.equals("[DONE]"))` ; méthode de parsing renommée `extractTokenFromJson` (sans dépouillement du préfixe).
- **Toggle `useRag` ignoré** : champ `Boolean useRag` ajouté à `QueryRequest` (défaut `true`). `RagService.query()` et `queryStream()` : court-circuit vers le LLM direct quand `useRag=false`, émettant `sources:[]` puis `ragStrategy:"DIRECT"`.

### Nouvelles fonctionnalités — Résilience et opérations

- **Annulation de tâches asynchrones** : `DELETE /api/ingest/{taskId}`, `DELETE /api/dataset/generate/{taskId}`, `DELETE /api/evaluation/{evalId}`, `DELETE /api/fine-tuning/{jobId}` — endpoint d'annulation pour les 4 services async. Un `Set<String> cancelledTaskIds` est vérifié à chaque itération de boucle.
- **Nettoyage mémoire planifié** : `@Scheduled(fixedDelay = 3_600_000)` sur les 4 services — purge horaire des tâches `COMPLETED`/`FAILED`/`CANCELLED` âgées de plus d'une heure (évite la fuite mémoire des `ConcurrentHashMap`).
- **Circuit breakers** : `@CircuitBreaker(name = "chroma")` sur `ChromaDbClient.getOrCreateCollection()` et `.query()` ; `@CircuitBreaker(name = "embed")` sur `LlamaCppEmbeddingClient.embed()`. Fallbacks typés (`ChromaDbUnavailableException`, `EmbeddingUnavailableException`). Configuration Resilience4j dans `application.yml` (`sliding-window-size`, `failure-rate-threshold`, `wait-duration-in-open-state`).
- **Dégradation gracieuse du multi-query** : bloc multi-query dans `RagService` enveloppé dans un try/catch avec fallback automatique vers le retrieval simple si la génération de variantes échoue.
- **`GET /api/health/services`** : nouveau `HealthController` agrégeant les `checkHealth()` de tous les services externes (LLM chat, embedding, ChromaDB, layout-parser, reranker). `healthApi.getServices()` ajouté dans `api.ts`.

### Améliorations frontend

- **Confirmation d'ingestion active** : `Datasets.tsx` — `window.confirm()` avant de lancer la génération si une tâche d'ingestion est en cours (`PENDING` ou `PROCESSING`), pour éviter un dataset incomplet.
- **Indicateurs d'erreur par service** : `Dashboard.tsx` — `statsErrors: string[]` tracke les rejets de `Promise.allSettled`. Icône `warning` affichée à côté des headers de section concernés (`Knowledge Base`, `Documents & Annotations`, `Cycle de Personnalisation`) en cas d'échec de fetch.

### Fiabilité — Schéma base de données

- **`ddl-auto: update` → `validate`** : `application.yml` — Hibernate ne modifie plus silencieusement le schéma au démarrage. Tout écart entre entité et base provoque un échec explicite au boot.
- **`schema.sql`** : DDL complet (`CREATE TABLE IF NOT EXISTS`) des 7 tables (`ingested_files`, `ingestion_tasks`, `generation_tasks`, `article_comments`, `ged_audit_log`, `document_model_links`, `fine_tuning_jobs`). Exécuté avant la validation Hibernate (`spring.sql.init.mode: always`). Idempotent — safe sur une base existante comme sur H2 fraîche.
- **`application-dev.yml`** : profil `dev` (`SPRING_PROFILES_ACTIVE=dev`) conservant `ddl-auto: update` pour le développement d'entités ; workflow : implémenter → valider avec profil dev → reporter dans `schema.sql`.
- **Timeout upload multipart** : `TomcatUploadConfig` — `disableUploadTimeout=false` + `connectionUploadTimeout=120000 ms`. Un fichier de 50 Mo depuis un client lent ne peut plus bloquer une connexion indéfiniment.

### Tests

- **`QueryControllerTest`** (5 tests MockMvc) : `POST /api/query` — requête valide → 200, question vide → 400, champ manquant → 400 ; `POST /api/query/stream` — dispatch async + `text/event-stream`, question vide → 400.
- **`RagServiceStreamTest`** (7 tests StepVerifier) : chemin direct `useRag=false` (sources → tokens → done, `ragStrategy=DIRECT`) ; LLM erreur réactive (`Flux.error`) → `sources` puis `event:error` ; LLM exception synchrone → `event:error` seul ; ChromaDB indisponible (circuit breaker) → `event:error` seul ; embedding indisponible → `event:error` seul ; `query()` + ChromaDB down → `ChromaDbUnavailableException` propagée.

---

## [1.9.0] — 2026-04-22

### Correctifs — Bugs, sécurité, fiabilisation

#### Pipeline chat / RAG

- **Interface Utilisateur : Améliorations Pédagogiques**
  - **Playground (Mode Trace)** : Ajout d'une fenêtre modale permettant de visualiser la stratégie RAG utilisée, les optimisations déclenchées, et les extraits sources finaux envoyés au LLM.
  - **Dashboard** : Remplacement de la simple liste des évaluations récentes par un graphique d'évolution (`recharts`) permettant d'apprécier la progression de la qualité du modèle au fil des cycles de fine-tuning.
  - **Optimisation** : Remplacement des info-bulles basiques par des info-bulles riches expliquant de manière pédagogique le sens des métriques (Hit@k, MRR, Taux d'hallucination).
  - **Documentation** : Ajout d'un onglet "Théorie & Algorithmes" dans l'UI reprenant les éléments clés de la documentation pédagogique (RAG, Embeddings, Recherche Hybride, filtre Jaccard).
- **`POST /api/query/stream`** : nouvel endpoint SSE manquant — le Playground était entièrement cassé (404 à chaque message)
  - `RagService.retrieveContext()` extrait la phase retrieval (embed → ChromaDB → re-rank → build sources) ; `query()` et `queryStream()` s'appuient dessus
  - `queryStream()` émet les events SSE `sources → token* → done | error` via `LlmChatClient.chatStream()`
  - Timeout de garde `Flux.timeout(generateTimeout)` côté backend + `AbortController(120s)` côté frontend
- **Temperature & Top-P câblés** : les sliders du Playground étaient sauvegardés mais jamais transmis au LLM
  - `QueryRequest` : nouveaux champs optionnels `temperature` (0.0–2.0, défaut 0.7) et `topP` (0.0–1.0, défaut 0.9)
  - `LlmChatClient.chatStream(String, String, float, float)` : nouvelle surcharge (default fallback dans l'interface)
  - `LlamaCppChatClient` transmet `temperature` et `top_p` à llama-server

#### Ingestion

- **URL encoding** : `UrlFetcherService` — URL de browserless encodée via `UriComponentsBuilder` (fix injection via query param)
- **Validation de schéma URL** : rejet des schémas non-http/https avant tout appel réseau
- **Markdown tables** : `TextCleanerService` préserve les séparateurs de tableaux Markdown lors du nettoyage
- **ZIP depth** : `IngestionService` — limite à 3 niveaux d'imbrication pour prévenir les ZIP bombs

#### GlobalExceptionHandler

- `LlmUnavailableException` → HTTP **503** (était 500 via handler générique)
- `MethodArgumentNotValidException` → HTTP **400** avec détail champ par champ (était 500)

#### ChromaDB

- Cache `ConcurrentHashMap` nom → collectionId : élimine un aller-retour réseau par requête RAG
- `deleteBySource()` : filtre `where` ChromaDB pour ne charger que les IDs concernés (était fullscan)
- Timeouts différenciés : `TIMEOUT_ADD=60s`, `TIMEOUT_QUERY=15s`, `TIMEOUT_BULK_GET=30s`, `TIMEOUT_DEFAULT=10s`
- Null-guard sur `getOrCreateCollection()` + validation du nom (3-63 chars, pattern ChromaDB)

#### Fine-tuning & Dataset

- `DatasetGeneratorService.generatedPairs` : reset complet + réécriture du fichier JSONL à chaque `submit()` (évite l'accumulation de doublons entre runs)
- `POST /api/dataset/generate?maxChunks=N` : paramètre `maxChunks` désormais fonctionnel (était ignoré)
- Protection contre la génération concurrente : `AtomicBoolean generationRunning` → HTTP 409 si déjà en cours
- `DatasetGeneratorService` : persistance JSONL au démarrage + confiance dynamique des paires

#### Asynchrone

- `AsyncConfig` : `ThreadPoolTaskExecutor` → `SimpleAsyncTaskExecutor` avec `setVirtualThreads(true)` — les tâches `@Async` utilisent désormais les virtual threads Project Loom cohérents avec `spring.threads.virtual.enabled: true`

#### Frontend — robustesse

- **Playground** : historique localStorage limité à 50 messages + catch `QuotaExceededError`
- **Datasets** : tous les `setInterval` de polling trackés dans un `useRef` + cleanup complet au unmount du composant
- **Datasets / Comparison** : arrêt automatique du polling après 5 échecs réseau consécutifs

#### Autres correctifs

- `LlamaCppChatClient.checkHealth()` : `activeModelLoaded=false` → HTTP 200 avec status `model-not-loaded` (était HTTP 500)
- `StatusController` : utilise `LlmChatClient` (interface) + `EmbeddingClient` au lieu de `LlmClient` (legacy)
- `ConfigController.setModel()` : catch `IllegalStateException` → HTTP 400 (était 500)
- `LlmFitService.installModel()` : vérification `process.exitValue()` après timeout forcibly destroy (était NPE)
- `FineTuningService` : `ProcessBuilder.directory(workDir)` pour les scripts d'entraînement
- `DpoGenerationService` / `EvaluationService` : null-guard sur self-injection `@Lazy` (`self != null ? self : this`)
- `FineTuningRequest.baseModel` : annotation `@Pattern` pour bloquer les injections de commande

---

## [1.8.0] — 2026-04-15

### Infra — Séparation chat/embed, ChromaDB v2, healthchecks

#### Docker Compose

- `llm-server` → deux services dédiés : **`llm-chat`** (port 8081, chat) et **`llm-embed`** (port 8082, embeddings)
- Nouveau service **`model-init`** : vérifie la présence et la taille (>1 Mo) des fichiers GGUF avant de démarrer les serveurs LLM ; affiche les commandes `huggingface-cli` / `wget` et interrompt la stack si un modèle est absent
- Variables renommées : `LLM_MODEL_FILE` → `LLM_CHAT_MODEL_FILE` + `LLM_EMBED_MODEL_FILE` ; `LLM_MODEL_NAME` → `LLM_CHAT_MODEL_NAME` + `LLM_EMBED_MODEL_NAME`
- `SPECTRA_LLM_PROVIDER=llama-cpp` désormais explicite dans `.env` et `docker-compose.yml` (était absent → fallback silencieux sur `ollama`)
- `SPECTRA_LLM_CHAT_BASE_URL=http://llm-chat:8081` et `SPECTRA_LLM_EMBEDDING_BASE_URL=http://llm-embed:8082` ajoutés
- Chaîne de dépendances complète : `model-init` → `llm-chat` + `llm-embed` (healthy) + `chromadb` (started) → `spectra-api` (healthy) → `frontend`
- `docker-compose.gpu.yml` mis à jour pour surcharger `llm-chat` et `llm-embed` avec l'image CUDA
- `detect-env.sh` et `detect-env.bat` mis à jour pour inclure toutes les nouvelles variables dans le `.env` généré automatiquement

#### ChromaDB API v2

- `ChromaDbClient.java` migré de l'API v1 (supprimée, HTTP 410) vers **l'API v2**
- Toutes les URLs passent par `/api/v2/tenants/default_tenant/databases/default_database/collections/…`
- Heartbeat : `/api/v1/heartbeat` → `/api/v2/heartbeat`
- Healthcheck ChromaDB : `curl` absent de l'image → remplacé par `/dev/tcp` bash natif sur `/api/v2/heartbeat`

#### Healthchecks

- `spectra-api` : `curl` absent dans `eclipse-temurin:21-jre` → remplacé par `wget -qO-` sur `/actuator/health`
- `application.yml` : valeurs par défaut `llm-server:8081` → `llm-chat:8081` ; provider par défaut `ollama` → `llama-cpp` ; ajout des blocs `chat.base-url` et `embedding.base-url`

---

## [1.7.0] — 2026-04-12

### Ajouté — Agentic RAG / Boucle ReAct (I4)

#### I4 — Agentic RAG (boucle de raisonnement ReAct)

- `AgenticRagService` : boucle THOUGHT → ACTION (SEARCH | ANSWER) activée via `SPECTRA_AGENTIC_RAG_ENABLED=true`
- Le LLM reçoit un prompt structuré en deux formats exclusifs (`ACTION: SEARCH` + `QUERY: ...` ou `ACTION: ANSWER` + `RESPONSE: ...`)
- Sur `SEARCH` : embed de la requête affinée → retrieval vectoriel ou hybride (I2) → déduplication par texte (`Set<String>`) → enrichissement du contexte
- Sur `ANSWER` : extraction du bloc `RESPONSE:` → sortie de boucle
- Boucle bornée par `max-iterations` (défaut 3) ; fallback sur génération directe si budget épuisé
- Garde-fous : format LLM inattendu → réponse brute utilisée ; `SEARCH` sans `QUERY` → sortie propre ; contexte vide → message d'indisponibilité
- Compatible I1 (re-ranking) et I2 (hybrid search) : le pipeline d'enrichissement initial s'exécute avant la boucle ; les chunks enrichis sont transmis en entrée à `AgenticRagService`
- `RagService` injecte `Optional<AgenticRagService>` et délègue après le retrieval/re-ranking quand le bean est présent
- `QueryResponse` : nouveaux champs `agenticApplied` (boolean) et `agenticIterations` (int)
- Config : `spectra.agentic-rag.{enabled, max-iterations, initial-top-k}` + variables d'env `SPECTRA_AGENTIC_RAG_ENABLED`, `SPECTRA_AGENTIC_MAX_ITERATIONS`, `SPECTRA_AGENTIC_INITIAL_TOP_K`
- Désactivé par défaut — aucun impact sur les déploiements existants

---

## [1.6.0] — 2026-04-12

### Ajouté — Layout-Aware Parsing PDF (I3)

#### I3 — Parsing PDF avec conscience de la mise en page

- Nouveau microservice Python `docparser/` (FastAPI + `pymupdf4llm`) — convertit les PDF en Markdown structuré : titres `#`/`##`, tableaux `| col |`, listes, blocs de code
- Upgrade optionnel Docling (IBM) via `USE_DOCLING=true` (modèles IA, ~500 Mo, meilleure précision sur tableaux complexes)
- `LayoutParserClient` — client HTTP Spring WebClient multipart (`POST /parse`), timeout 120 s configurable, 50 Mo d'in-memory buffer
- `LayoutAwarePdfExtractor` — remplace `PdfExtractor` quand `spectra.layout-parser.enabled=true` ; fallback automatique vers PDFBox si docparser indisponible
- `PdfExtractor` rendu conditionnel (`@ConditionalOnProperty havingValue="false" matchIfMissing=true`) — aucun conflit de factory, comportement par défaut inchangé
- Métadonnée `parser` (valeur : `pymupdf4llm` | `docling` | `pymupdf4llm-fallback`) ajoutée à chaque chunk issu d'un PDF traité par docparser
- Métadonnée `layoutAware: true` distingue les chunks avec parsing structuré des chunks PDFBox
- Service `docparser` ajouté dans `docker-compose.yml` (port hôte **8003**, healthcheck)
- Activation : `SPECTRA_LAYOUT_PARSER_ENABLED=true` (désactivé par défaut — aucun impact sur les déploiements existants)
- Config : `spectra.layout-parser.{enabled, base-url, timeout-seconds}` + variables d'env

---

## [1.5.0] — 2026-04-12

### Ajouté — Hybrid Search BM25 + Vecteurs (I2)

#### I2 — Recherche hybride (Reciprocal Rank Fusion)

- `BM25Index` : implémentation BM25Okapi en Java pur, thread-safe (`ReentrantReadWriteLock`), tokeniseur Unicode adapté au français (accents, ligatures)
- `FtsService` : gère un index BM25 par collection ChromaDB — rebuild asynchrone depuis ChromaDB au démarrage (`@PostConstruct`), mis à jour à chaque ingestion/suppression
- `HybridSearchService` : lance en parallèle via `CompletableFuture` la recherche vectorielle (ChromaDB) et la recherche BM25 (`FtsService`), fusionne via RRF (k=60, poids BM25 configurable)
- `IngestionTaskExecutor` : appelle `FtsService.indexChunks()` après chaque ajout dans ChromaDB
- `DocumentController` : appelle `FtsService.removeBySource()` à chaque suppression
- `QueryResponse` : nouveau champ `hybridSearchApplied` (boolean) ; `Source` enrichi de `bm25Score` (Float)
- Activation : `SPECTRA_HYBRID_SEARCH_ENABLED=true` (désactivé par défaut)
- Compatible avec le re-ranking I1 : hybrid search → re-ranking Cross-Encoder s'enchaînent automatiquement si les deux sont activés
- Config : `spectra.hybrid-search.{enabled, top-bm25, bm25-weight}` + variables d'env correspondantes

---

## [1.4.0] — 2026-04-12

### Ajouté — Re-ranking Cross-Encoder (I1)

#### I1 — Re-ranking post-retrieval

- Nouveau microservice Python `reranker/` (FastAPI + `sentence-transformers`) : modèle Cross-Encoder configurable via `RERANKER_MODEL` (défaut : `cross-encoder/ms-marco-MiniLM-L-6-v2`, compatible CPU)
- `RerankerClient` interface + `CrossEncoderRerankerClient` implémentation HTTP (activée uniquement si `spectra.reranker.enabled=true`)
- `RagService` modifié : récupère `topCandidates` chunks de ChromaDB (défaut 20), les re-classe via le service Cross-Encoder, ne retient que les `maxContextChunks` meilleurs pour le LLM
- `QueryRequest` : nouveau champ `topCandidates` (1–100, défaut 20)
- `QueryResponse` : nouveau champ `rerankApplied` (boolean) ; `Source` enrichi d'un champ `rerankScore` (Float)
- Service `reranker` ajouté dans `docker-compose.yml` (port hôte **8002**, healthcheck Python)
- Activation : variable d'environnement `SPECTRA_RERANKER_ENABLED=true` (désactivé par défaut — aucun impact sur les déploiements existants)
- `SpectraProperties.RerankerProperties` : `enabled`, `baseUrl`, `model`, `timeoutSeconds`, `topCandidates` — tous configurables via `application.yml` ou variables d'environnement

### Corrigé
- `ModelHubController.installModel` : troisième argument `autoActivate=false` manquant
- `BenchmarkService.query` : appel `QueryRequest` mis à jour pour correspondre à la nouvelle signature (4 champs)

---

## [1.3.0] — 2026-04-11

### Ajouté — Observabilité, benchmarks et déploiement K8s

#### Benchmark API
- `GET /api/benchmark/embedding` : mesure le débit de vectorisation (chunks/s, ms/chunk)
- `GET /api/benchmark/llm` : mesure la latence de génération LLM pure (tokens/s, time-to-first-token)
- `GET /api/benchmark/rag` : mesure la latence RAG bout-en-bout (embed + ChromaDB + LLM)
- `GET /api/benchmark` : suite complète — retourne les 3 mesures dans un seul appel
- `BenchmarkService` : logique de mesure isolée, configuré pour ne pas impacter la production

#### SSE temps réel
- `GET /api/sse/system-load` : flux SSE émettant toutes les secondes l'utilisation CPU/heap JVM
- `GET /api/sse/training-logs` : flux SSE des logs de fine-tuning en temps réel
- `TrainingLogBroadcaster` : canal `Sinks.Many` multicast avec buffer 500 messages ; `FineTuningService` publie chaque ligne stdout/stderr du script

#### Configuration à chaud
- `GET /api/config/model` : retourne le modèle chat actif (depuis `ModelRegistryService`)
- `POST /api/config/model` : bascule le modèle chat sans redémarrage (met à jour `registry.json`)

#### Mode batch programmatique
- `BatchService` : orchestre le pipeline complet ingest-local → dataset → fine-tuning depuis le code Java
- `BatchRunner` : CLI `--batch` pour déclencher le pipeline en ligne de commande (utilisé par `pipeline.bat` / `pipeline.sh`)

#### Déploiement Kubernetes
- Manifests `k8s/` : 10 fichiers YAML + `kustomization.yaml` pour un déploiement complet sur tout cluster K8s ≥ 1.26
  - Namespace, ConfigMap, 4 PVCs, 6 Deployments (spectra-api, spectra-frontend, llm-chat, llm-embed, chromadb, browserless)
  - Ingress unique — seul le frontend est exposé ; les services internes restent en ClusterIP
- `k8s/README.md` : procédure de déploiement + commandes `kubectl apply -k` / rollback

#### Setup automatisé
- `setup.sh` / `setup.bat` : création des répertoires `data/`, détection du profil serveur (via `detect-env`), aide au téléchargement du modèle GGUF initial
- `scripts/requirements.txt` : dépendances Python versionnées (unsloth, trl, transformers, datasets, bitsandbytes, accelerate) pour le fine-tuning QLoRA

---

## [1.2.0] — 2026-04-06

### Ajouté — Fonctionnalités Axolotl (H1–H4)

#### H1 — Alignement DPO (Direct Preference Optimization)
- `DpoGenerationService` : génère des paires (choisi/rejeté) en demandant au LLM une réponse intentionnellement erronée
- Nouveaux endpoints : `POST /api/dataset/dpo/generate`, `GET /api/dataset/dpo/generate/{taskId}`, `GET /api/dataset/dpo/stats`
- `train_host.py` + `train.sh` : flag `--dpo` → `DPOTrainer` (trl), fallback SFT automatique si trl < 0.4
- Checkbox "Alignement DPO" dans le formulaire Fine-Tuning
- `FineTuningService` : export dataset DPO si `dpoEnabled=true`

#### H2 — Évaluation automatique LLM-as-a-judge
- `EvaluationService` : échantillonne 5 % du dataset (min 5, max 50 paires), interroge le modèle actif, utilise le même modèle comme juge (note 1–10 + justification JSON)
- Résultats persistés dans `evaluations.json` (survive au redémarrage)
- Nouveaux endpoints : `POST /api/evaluation`, `GET /api/evaluation`, `GET /api/evaluation/{evalId}`
- Page `Comparison.tsx` entièrement réécrite : score global, barres par catégorie, détail question/réponse/justification

#### H3 — Multipacking
- `PackedDataset` dans `train_host.py` : greedy bin-packing des séquences courtes, affiche le ratio d'utilisation
- Flag `--packing` dans `train_host.py` et variable `$8` dans `train.sh`
- `SFTTrainer` avec `packing=True` (GPU)
- Champ `packingEnabled` dans `FineTuningRequest` + checkbox dans l'interface

#### H4 — Recettes d'entraînement YAML
- `RecipeController` : `GET /api/fine-tuning/recipes`, `GET /api/fine-tuning/recipes/{name}`, `POST /api/fine-tuning/recipe/export`
- 3 recettes prédéfinies : `cpu-rapide.yml`, `gpu-qualite.yml`, `dpo-alignement.yml`
- Sélecteur de recettes dans `FineTuning.tsx` + bouton Export (télécharge `.yml`)
- Sérialisation SnakeYAML (dépendance déjà présente via Spring Boot)

### Corrigé
- `LlamaCppRuntimeOrchestrator` : `--flash-attn on` au lieu du flag nu `--flash-attn` (llama-server attend une valeur)

### Modifié
- `pipeline.bat` : support des flags `--packing` et `--dpo` (transmission à `train_host.py`)
- Documentation : `IMPROVEMENTS.md`, `README.md`, `user-manual.fr.md` mis à jour avec H1–H4

---

## [1.1.0] — 2026-04-02

### Ajouté — Migration llama-cpp
- Inférence chat et embedding migrées de Ollama vers llama-server (llama-cpp-turboquant)
- `LlamaCppChatClient` + `LlamaCppEmbeddingClient` : clients HTTP OpenAI-compatible
- `LlamaCppRuntimeOrchestrator` : auto-détection CPU/RAM/GPU → paramètres llama-server optimaux
- `GET /api/config/resources` + `POST /api/config/resources/refresh`
- Healthchecks Docker : `wget` sur `/health` (llama-server), retry avec `start_period`
- Cache KV f16 pour le serveur d'embedding
- Streaming SSE (`/api/query/stream`) : `sources` → `token*` → `done | error`
- Circuit breaker sur les appels LLM (3 tentatives, backoff exponentiel)
- Sélecteur de modèle dans le Playground

### Ajouté — Ingestion URL
- `UrlFetcherService` : HEAD → content-type → téléchargement direct (PDF/TXT) ou rendu JS (HTML via browserless/chrome)
- `POST /api/ingest/url` avec `{"urls": [...]}`
- Service `spectra-browserless` dans docker-compose.yml

### Corrigé
- Healthchecks Docker alignés sur les contraintes réelles de chaque image (pas de `curl` dans certains conteneurs)

---

## [1.0.0] — 2026-04-01

### Ajouté — Audit complet et corrections

#### Fiabilité (A1–A6)
- **A1** Persistance H2 : tâches d'ingestion, jobs fine-tuning, paires générées survivent au redémarrage
- **A2** Déduplication SHA-256 à l'ingestion (`?force=true` disponible)
- **A3** Timeout LLM configurable (`spectra.ollama.generate-timeout-minutes: 10`)
- **A4** Support `.doc` (HWPFDocument/POI Scratchpad) en plus de `.docx`
- **A5** Race condition `generatedPairs.clear()` : liste locale par tâche, fusion atomique en fin
- **A6** Pagination ChromaDB `getAllDocuments()` (limit=500/offset)

#### Performance (B1–B4)
- **B1** Cache de l'ID de collection ChromaDB (évite un aller-retour HTTP par requête)
- **B2** Parallélisation des appels LLM (résumé + classification + cas négatif en Virtual Threads)
- **B3** Taille de batch d'embeddings configurable (`spectra.ollama.embedding-batch-size`)
- **B4** Constante collection `"spectra_documents"` centralisée dans `ChromaDbProperties`

#### Qualité dataset (C1–C4)
- **C1** `sourceFile` correctement propagé dans les métadonnées ChromaDB
- **C2** Parsing JSON robuste : nettoyage balises Markdown avant extraction
- **C3** Filtre de qualité RAG : seuil distance cosinus configurable (`spectra.rag.max-distance-threshold: 0.8`)
- **C4** Équilibrage des paires par source (max 20 % par fichier source)

#### Observabilité (D1–D2)
- **D1** Métriques Micrometer/Prometheus : `spectra.ingestion.chunks.total`, `spectra.rag.query.duration`, etc.
- **D2** Logs heap mémoire rétrogradés en DEBUG

#### Sécurité (E1–E3)
- **E1** Filtre `ApiKeyFilter` sur `/api/**` si `SPECTRA_API_KEY` défini
- **E2** Limite upload : `max-file-size: 100MB`, `max-request-size: 500MB`
- **E3** Sanitisation des noms de fichiers uploadés

#### Fonctionnalités (F1–F4)
- **F1** `GET /api/documents` + `DELETE /api/documents/{sourceFile}`
- **F2** Reprise de génération interrompue (WIP JSONL + progress JSON)
- **F3** Support Avro (Apache Avro 1.12.0)
- **F4** Collections multiples ChromaDB (`?collection=` sur ingest et query)

#### Infrastructure (G1–G3)
- **G1** `.gitattributes` : LF pour scripts/java/yml, CRLF pour .bat, binaire pour .gguf
- **G2** `.gitignore` : données, modèles, artefacts Python
- **G3** `GET /api/status/deep` : healthcheck fonctionnel ChromaDB + LLM

---

## [0.9.0] — 2026-03-25

### Ajouté — Fonctionnalités initiales
- Pipeline complet : ingestion → dataset → fine-tuning → RAG
- Inférence via Ollama (phi3, mistral, llama3)
- ChromaDB v2 pour le stockage vectoriel
- Interface React (Vite + Tailwind) : Dashboard, Datasets, Fine-Tuning, Playground
- Scripts : `start.bat`, `stop.bat`, `adddoc.bat`, `pipeline.bat`
- Docker Compose multi-services
- Swagger UI

---

*Spectra — Transformez vos documents en intelligence artificielle locale.*
