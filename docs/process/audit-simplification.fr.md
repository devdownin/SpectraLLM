# Audit de simplification

**Périmètre.** Le code applicatif : backend Java (27 540 lignes), frontend React
(19 495 lignes), services Python (1 086 lignes). Les scripts de déploiement ont déjà été
traités par [l'audit de déploiement](audit-deploiement.fr.md) et n'y reviennent pas.

**Méthode.** Mesure avant lecture. Longueur des méthodes sur les 1 142 méthodes du backend,
détection des régions dupliquées entre fichiers par appariement de séquences, recherche des
symboles jamais référencés, comptage des responsabilités par composant React. Les constats
sont classés par ce que leur correction rapporte, pas par la taille du diff.

---

## Résumé

**Le code est globalement sain, et c'est le résultat le plus important de cet audit.** Sept
méthodes sur 1 142 dépassent 80 lignes. La duplication réelle entre fichiers se limite à
trois auxiliaires. Le frontend a déjà un client d'API partagé et des hooks communs : aucune
page ne réimplémente son propre `fetch`. Il n'y a pas de grand ménage à faire, et prétendre
le contraire ferait perdre du temps.

Le constat qui domine n'est d'ailleurs pas une simplification. C'est **du code mort qui n'est
peut-être pas mort du tout, mais oublié** — et le distinguer change complètement ce qu'il
faut en faire.

| # | Constat | Nature | État |
|---|---|---|---|
| S1 | Deux tranches de persistance complètes branchées sur rien | Fonctionnel | **Corrigé** — dépôts branchés |
| S1b | La génération DPO, sixième famille de tâches, sans persistance du tout | Fonctionnel | **Corrigé** — table créée |
| S2 | `installModel()` : 282 lignes, dont une lambda de 240 | Lisibilité | Proposé |
| S3 | Trois auxiliaires dupliqués à l'identique | Duplication | **Corrigé** — `fr.spectra.util` |
| S4 | Code mort avéré : un DTO, un export d'API, trois types | Mort | **Corrigé** |
| S5 | `Documentation.tsx` : 1 496 lignes de contenu statique | Volume | **Vérifié — à ne pas faire** |
| S6 | Deux pages à état lourd (24 et 18 `useState`) | Complexité | Observé |

---

## S1 — Deux tranches de persistance branchées sur rien

C'est le constat le plus important. Les deux corrections possibles étaient opposées — brancher
ou supprimer — et **c'est brancher qui a été retenu**, conformément à la recommandation
ci-dessous. Le détail de la mise en œuvre est en fin de section.

Le dépôt contient, pour les tâches d'ingestion et de génération de jeux de données, tout
l'appareillage de persistance :

| Couche | Ingestion | Génération |
|---|---|---|
| Table SQL | `ingestion_tasks` | `generation_tasks` |
| Entité JPA | `IngestionTaskEntity` (83 lignes) | `GenerationTaskEntity` (57 lignes) |
| Dépôt Spring Data | `IngestionTaskRepository` | `GenerationTaskRepository` |
| **Injecté quelque part** | **non** | **non** |

Aucun service n'injecte ces dépôts. Aucune requête SQL brute ne touche ces tables. Elles sont
créées au démarrage par `schema.sql`, et rien ne les lit ni ne les écrit.

**Ce qui interdit de conclure « code mort, on supprime ».** Ces tables ont été *maintenues* :

```sql
ALTER TABLE ingestion_tasks  ADD COLUMN IF NOT EXISTS chunks_expected INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ingestion_tasks  ADD COLUMN IF NOT EXISTS file_errors TEXT;
ALTER TABLE generation_tasks ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE;
```

On n'ajoute pas de colonnes à une table qu'on considère morte. Quelqu'un a fait évoluer ce
schéma en même temps que le modèle métier — sans que la couche qui l'utiliserait ait jamais
été branchée.

**La conséquence observable.** Les quatre familles de tâches asynchrones du produit ne sont
pas traitées de la même façon :

| Service | État des tâches | Après un redémarrage |
|---|---|---|
| `LlmFitService` | JPA (`InstallationJobEntity`) | conservé |
| `FineTuningService` | JPA (`FineTuningJobEntity`) | conservé |
| `IngestionService` | `ConcurrentHashMap` | **perdu** |
| `DatasetGeneratorService` | `ConcurrentHashMap` | **perdu** |

Redémarrer l'API pendant une ingestion de plusieurs milliers de chunks efface l'historique de
la tâche : l'utilisateur ne sait plus ce qui a été traité, ni pourquoi cela s'est arrêté. Les
deux autres familles, elles, survivent et sont même réconciliées au démarrage — `LlmFitService`
commente explicitement ce choix (« la base est la seule trace durable »).

L'asymétrie n'est documentée nulle part. Les deux lectures sont défendables :

- **c'était voulu et jamais fini** — la persistance devait être branchée, elle ne l'a pas été,
  et c'est un défaut fonctionnel à corriger (brancher les dépôts, ~30 lignes par service) ;
- **c'était voulu puis abandonné** — l'état d'ingestion est volontairement éphémère, et il
  faut alors supprimer 145 lignes de Java plus deux tables.

**Recommandation : brancher plutôt que supprimer.** Deux des quatre familles persistent déjà,
la réconciliation au démarrage existe, et le schéma a été tenu à jour — tout indique une
intention jamais menée à terme plutôt qu'un renoncement.

### Mise en œuvre

Le point délicat n'est pas d'appeler `save()`, c'est de garantir qu'aucune écriture n'y
échappe. L'état des tâches d'ingestion est muté depuis **deux** endroits : `IngestionService`
et `IngestionTaskExecutor`, à qui la map est passée en paramètre et qui la modifie sur neuf
sites ; côté génération, onze sites du même service. Brancher site par site suppose de tous
les trouver — et que le prochain contributeur qui en ajoute un y pense. C'est le mode de
défaillance que cet audit dénonce partout ailleurs : un branchement qui cesse silencieusement
de fonctionner.

`PersistentTaskMap<V>` décore donc la map elle-même. Toute mutation, présente ou future, passe
par le miroir ; aucun appelant n'a à savoir que la persistance existe, et l'exécuteur n'a pas
été touché.

Trois décisions à noter :

- **La map reste l'autorité à l'exécution.** `computeIfPresent` porte l'atomicité sur laquelle
  repose une correction de course annulation/fin documentée dans `cancelTask()` ; un
  aller-retour en base la réintroduirait. La base sert à survivre au redémarrage, pas à
  arbitrer la concurrence.
- **Un échec d'écriture en base ne fait pas échouer la tâche** — il est journalisé. Perdre
  l'historique est regrettable ; faire échouer une ingestion de plusieurs milliers de chunks
  parce que H2 hoquette le serait davantage.
- **Les vues (`entrySet`, `keySet`, `values`) sont non modifiables.** Écrire la purge horaire
  `entrySet().removeIf(...)` — le réflexe naturel, et ce que faisait le code — viderait la map
  en laissant les lignes en base. Elle lève maintenant `UnsupportedOperationException` ; les
  deux `cleanupOldTasks` passent par `remove()`.

Au démarrage, `reconcileInterruptedTasks()` recharge l'historique et solde en `FAILED` les
tâches restées `PENDING`/`PROCESSING` — miroir exact de
`FineTuningService.reconcileInterruptedJobs()`.

### S1b — il y avait six familles, pas quatre

Le recensement initial en comptait quatre. Il en manquait deux, trouvées en appliquant le
correctif :

| Famille | Persistance | Reprise |
|---|---|---|
| Ingestion, génération SFT | JPA *(branché)* | oui |
| Fine-tuning, installation de modèle | JPA | oui |
| Comparaison qualité | fichier JSON (`persistCompareJobs`) | oui |
| **Génération DPO** | **aucune** | **non** |

La comparaison qualité persiste, autrement — par un fichier JSON — mais elle persiste et se
réconcilie. La génération DPO, elle, ne faisait ni l'un ni l'autre : c'était la dernière
famille dont l'état disparaissait en silence.

Différence avec S1 : aucune table dormante n'existait, il a fallu créer `dpo_tasks`, son entité
et son dépôt. Le branchement lui-même se réduit ensuite à trois lignes, `PersistentTaskMap`
étant déjà là. **Les six familles se comportent maintenant de la même façon.**

---

## S2 — `installModel()` : 282 lignes, dont une lambda de 240

`LlmFitService.installModel()` est de loin la plus longue méthode du backend. La suivante en
fait 179, la médiane est très en dessous.

Sa structure est pourtant nette : un préambule synchrone d'une quarantaine de lignes
(validation des arguments, verrou anti-doublon, création du sink SSE, persistance du job),
puis un `CompletableFuture.supplyAsync` dont la lambda porte à elle seule ~240 lignes —
résolution du nom sur l'API HuggingFace, lancement du sous-processus `llmfit`, lecture de la
progression, repli par scan du répertoire des modèles, activation éventuelle.

Ce n'est pas du code confus : il est abondamment commenté, et les commentaires expliquent des
choix réels (pourquoi le sink est conservé après complétion, pourquoi le job est persisté
avant la tâche async). Le problème est qu'il faut tenir cinq étapes en tête simultanément
pour suivre une seule d'entre elles.

**Proposition.** Extraire la lambda en méthodes privées nommées — `resolveModelId`,
`runLlmFit`, `locateProducedGguf`, `activateIfRequested` — en conservant les commentaires sur
chacune. Aucun changement de comportement, la méthode publique se lit alors comme la liste de
ses étapes. Le préambule synchrone, lui, est à laisser tel quel : son ordre est significatif
et déjà expliqué.

---

## S3 — Trois auxiliaires dupliqués à l'identique

La recherche de régions dupliquées entre fichiers distincts ne remonte que cinq résultats de
plus de dix lignes, dont deux sont des fixtures de test — acceptable. Restent trois
auxiliaires réellement copiés :

| Auxiliaire | Emplacements | Écart |
|---|---|---|
| `extractJson` | `EvaluationService`, `QualityBenchmarkService` | une espace |
| `jaccardSimilarity` | `ArticleCommentService`, `dataset/DpoGenerationService` | aucun |
| `checkHealth` | `CrossEncoderRerankerClient`, `extraction/LayoutParserClient` | le libellé du service |

Les deux premiers sont des fonctions pures de quelques lignes ; le troisième est un sondage
`/health` sur `WebClient` dont seule l'étiquette (`"reranker"` / `"docparser"`) diffère.

Le risque n'est pas le volume — une quarantaine de lignes en tout — mais la dérive : corriger
`extractJson` d'un côté sans l'autre est exactement le genre de correction à moitié appliquée
qui se remarque des mois plus tard.

**Correction.** Trois classes dans un nouveau package `fr.spectra.util` : `LlmJson.extract()`,
`TextSimilarity.jaccard()` et `HealthProbe.probe()`. Les six sites d'appel les utilisent
désormais, et chaque classe a ses tests — ces fonctions n'en avaient aucun, étant privées.

**Un quatrième cas, laissé tel quel — et c'est délibéré.** `DatasetGeneratorService` porte lui
aussi un `extractJson`, que la recherche de duplication n'avait pas remonté : il n'est **pas**
identique aux deux autres. Il renvoie `"{}"` là où ils renvoient `null`, et ne retire la
clôture Markdown que si le texte *commence* par elle. Il a sa propre suite de tests, dont un
cas paramétré qui vérifie qu'il rend toujours un objet JSON valide.

Les fusionner supposerait de choisir un contrat et de corriger les appelants de l'autre : ce
n'est plus de la déduplication, c'est un changement de comportement déguisé en nettoyage. Trois
implémentations dont deux identiques et une divergente, ce n'est pas la même chose que trois
copies — et le raccourci aurait cassé quelque chose.

---

## S4 — Code mort avéré — **corrigé dans cette PR**

Symboles exportés ou déclarés que rien n'importe, ni dans le code, ni dans les tests, ni dans
la configuration :

| Symbole | Emplacement | Taille |
|---|---|---|
| `IngestedDocumentDto` | `dto/` | 10 lignes |
| `documentsApi` | `services/api.ts` | 3 lignes |
| `SystemStatusResponse`, `ConversationMessage`, `QueryResponse` | `types/api.ts` | 32 lignes |

Vérifié un par un avant suppression, sur l'ensemble du dépôt et pas seulement sur le module
concerné — un DTO peut être référencé depuis un test ou un fichier de configuration.

Les dépôts et entités de S1 **ne sont pas** dans ce lot, bien que le même outil les ait
signalés : leur suppression est l'une des deux réponses possibles à S1, pas un nettoyage.

---

## S5 — `Documentation.tsx` : du volume, pas de la complexité

Avec 1 496 lignes, c'est le plus gros fichier du dépôt. La tentation est de le traiter comme
un composant obèse ; la mesure dit autre chose :

- ~301 lignes de contenu textuel en JSX (`<p>`, `<h2>`, `<li>`…)
- ~51 lignes de logique, 4 `useState`, 2 `useEffect`, aucun appel réseau

C'est une page de documentation dont le texte est écrit en dur dans le composant. Le découper
en sous-composants ne simplifierait rien : on obtiendrait dix fichiers contenant toujours le
même texte.

**Proposition initiale.** Sortir le contenu du TSX — fichiers Markdown/MDX, ou structure de
données parcourue par le composant. Le gain n'est pas la taille mais l'édition : modifier un
paragraphe cesserait de demander une modification de code, une revue et un build.

### Vérification : à ne pas faire sous cette forme

La mesure qui fondait ce constat — 301 lignes de texte contre 51 de logique — comptait les
*lignes de texte*. Elle ratait ce dans quoi ce texte est tissé :

| | |
|---|---|
| `className=` | **598** |
| grilles, cartes, bordures | **146** |
| SVG en ligne | 6 |
| **couverture** | **0 / 47 fonctions** |

Ce n'est pas un document, c'est un **écran conçu** : grilles de cartes à icônes, encadrés
colorés, mises en page propres à chaque section. Le convertir en Markdown perdrait la
présentation ; le convertir en MDX demanderait une dépendance de build *et* la réécriture
intégrale d'une page de 1 496 lignes **dont aucune fonction n'est couverte**.

C'est exactement l'arbitrage refusé pour S6 et F4 : échanger de la lisibilité contre du risque,
sur du code sans filet. Et le bénéfice attendu — éditer la documentation sans passer par une
revue — vaut pour un contenu qui bouge souvent, ce qui n'est pas le cas ici.

**Le constat se referme sur une décision, pas sur un correctif.** Si la page devient un jour
un vrai document éditorial, l'extraction se justifiera ; en attendant, elle coûterait plus
qu'elle ne rapporte.

---

## S6 — Deux pages à état lourd

| Page | Lignes | `useState` | `useEffect` |
|---|---|---|---|
| `Playground.tsx` | 1 341 | 24 | 7 |
| `Documents.tsx` | 1 495 | 18 | 4 |
| `FineTuning.tsx` | 1 156 | 19 | 8 |

Vingt-quatre `useState` dans un composant signalent en général plusieurs machines à états
imbriquées dont les invariants ne sont écrits nulle part. C'est le candidat classique au
`useReducer`, ou à l'extraction de hooks métier — le dépôt en a déjà (`useSse`,
`useGlobalTasks`, `useStatus`), le chemin est donc balisé.

Je le laisse en observation plutôt qu'en recommandation ferme : ces pages sont couvertes par
des tests (`FineTuning.*.test.tsx`, `useSse.test.ts`), mais la couverture frontend globale est
de 62 % de lignes et 45 % de fonctions. Refondre l'état d'un composant de 1 300 lignes sous
cette couverture, c'est accepter un risque de régression que le gain de lisibilité ne paie pas
forcément. **Renforcer les tests d'abord, refondre ensuite.**

---

## Ce qui n'a pas été touché, et pourquoi

- **Les interfaces à implémentation unique** (`EmbeddingClient`, `LlmChatClient`). Elles
  ressemblent à de la sur-abstraction, mais elles sont la couture de test des appels réseau :
  les retirer obligerait à mocker des `WebClient`. Elles gagnent leur place.
- **La taille du backend en soi.** 181 fichiers pour 27 500 lignes, soit 152 lignes par
  fichier en moyenne : la découpe est déjà fine. Le problème n'est pas la dispersion.
- **Les 77 avertissements ESLint.** Aucun n'est une erreur, et les traiter en masse
  produirait un diff illisible sans rapport avec la simplification. À faire au fil de l'eau.
- **Les services Python** (1 086 lignes sur trois services). Trop petits pour justifier un
  passage : rien n'y dépasse.

---

## Vérification

```
backend    mvn package        1086 tests, 0 échec, 10 sautés   OK
frontend   tsc --noEmit       aucune erreur                    OK
frontend   eslint             0 erreur, 77 avertissements      OK (inchangé)
frontend   vitest             suite complète                   OK
```

Les suppressions de S4 ont été validées dans les deux sens : aucune référence restante côté
frontend après coup, et compilation TypeScript stricte plus build Maven complet — un symbole
encore utilisé aurait fait échouer l'un ou l'autre.

Le branchement de S1 est couvert par `PersistentTaskMapTest` (8 cas). Les deux régressions
qu'il doit attraper ont été rejouées pour vérifier qu'il se déclenche vraiment : supprimer le
miroir de `computeIfPresent` — la voie par laquelle passent les neuf mutations de l'exécuteur —
et rendre les vues à nouveau modifiables font chacune échouer le cas correspondant.

**Ce qui n'a pas été vérifié :** le diagnostic initial de S1 repose sur l'analyse statique
(aucune injection, aucune requête brute), et sa correction sur des tests unitaires. Le cycle
complet — lancer une ingestion, redémarrer l'API, constater que la tâche est bien présente et
marquée `FAILED` — demande un démon Docker, absent de l'environnement d'audit.
