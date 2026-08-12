# Audit du frontend

**Périmètre.** `frontend/src` — 72 fichiers source (19 495 lignes) plus 27 fichiers de test
(2 640 lignes) : React 19, TypeScript, Vite, React Query, i18next, Tailwind.

**Méthode.** Mesure avant lecture, comme les audits précédents. Couverture par fichier,
dépendances déclarées confrontées aux imports réels, tailles de chunks après build, densité
d'appels `t()` par page, inventaire des chemins d'erreur, marqueurs d'accessibilité. Les
constats sont classés par ce que leur correction rapporte.

---

## Résumé

Le frontend est mieux tenu que son indicateur ne le suggère — mais **son indicateur ment**, et
c'est le constat qui domine tout le reste.

L'architecture est saine : routes en chargement différé, client d'API partagé, hooks communs,
`ErrorBoundary` monté, 203 attributs `aria-*` et pas un seul `<div onClick>` sans rôle. Le
répertoire `lib/` est exemplaire — chaque module y a son fichier de test. Ce n'est pas un
frontend négligé.

Le problème est ailleurs : **plusieurs mécanismes corrects existent et ne sont presque pas
adoptés**. Un extracteur de message d'erreur écrit pour un bug documenté, utilisé à un endroit
sur seize. Une bibliothèque d'icônes entière pour deux icônes. Trois dépendances de formulaire
pour une page. À chaque fois, la chose juste est là, et le code passe à côté.

| # | Constat | Nature | Effort |
|---|---|---|---|
| F1 | La couverture affichée vaut **le double** de la réelle | Mesure faussée | **Corrigé** |
| F2 | `services/api.ts` : 92 fonctions, **0 %** couvert | Risque | **Corrigé** |
| F3 | `apiErrorMessage` adopté sur **1 chemin d'erreur sur 16** | Régression latente | **Corrigé** |
| F4 | La logique vit dans les pages, pas dans les composants | Structure | Élevé |
| F5 | Deux systèmes d'icônes : 198 usages contre 2 | Poids inutile | **Corrigé** |
| F6 | Trois dépendances de formulaire pour une seule page | Poids inutile | **Vérifié — à garder** |
| F7 | i18n inégal : trois pages très en retard | Cohérence | **Corrigé** — sauf `Documentation`, qualifié |
| F8 | 73 des 77 avertissements ESLint sont `no-explicit-any` | Typage | Moyen |

---

## F1 — La couverture affichée vaut le double de la réelle

`vitest.config.ts` déclare la couverture ainsi :

```ts
coverage: {
  reporter: ['text', 'json', 'lcov'],
},
```

Il manque `all: true`. Sans cette option, `@vitest/coverage-v8` n'instrumente que les fichiers
**chargés par un test**. Un fichier qu'aucun test n'importe n'est pas compté à 0 % : il est
absent du calcul, numérateur *et* dénominateur.

**29 des 72 fichiers source sont dans ce cas** — soit 40 % du frontend, dont `App.tsx`,
`Sidebar.tsx`, `ErrorBoundary.tsx`, les cinq composants de graphique, `useStatus.ts`.

L'écart est exactement du simple au double :

| | Affiché | Réel |
|---|---|---|
| Instructions | 61,3 % | **33,1 %** |
| Branches | 54,5 % | **28,3 %** |
| Fonctions | 45,0 % | **23,7 %** |
| Lignes | 62,2 % | **33,7 %** |

**Trois conséquences.**

D'abord, Codecov publie le chiffre gonflé : le badge et les commentaires de PR décrivent une
situation deux fois meilleure que la réalité.

Ensuite, l'indicateur récompense le mauvais geste. Écrire un test qui se contente d'importer
un module le fait entrer dans le calcul et **fait monter le pourcentage sans rien vérifier**.
À l'inverse, un test sérieux sur un fichier déjà couvert le fait à peine bouger.

Enfin — et je dois le corriger ici — **l'audit de simplification citait 62 % de lignes et 45 %
de fonctions** pour justifier de ne pas refondre les pages à état lourd (constat S6). Les
chiffres étaient faux. La conclusion, elle, en sort renforcée : refondre un composant de
1 300 lignes sous 24 % de couverture de fonctions est encore moins raisonnable que sous 45 %.

### Correction — et un chiffrage que j'avais annoncé quatre fois trop grand

Ajouter `all: true` et un `include` prend trois lignes. Le blocage était ailleurs :
`codecov.yml` comparait au commit de base avec `threshold: 1%`, donc la PR qui rendait le
chiffre honnête aurait échoué au statut. C'est très probablement pourquoi le réglage est resté
faux si longtemps.

**Mais l'ampleur annoncée — « 33 % au lieu de 61 % » — ne valait que pour le frontend seul.**
Les cinq rapports (Jacoco, Vitest, docparser, reranker, trainer) sont téléversés **sans
flags** : Codecov les fusionne en un seul chiffre global, où le frontend ne pèse que 1 832
lignes sur 12 579. Mesuré :

| | Couvert / total | % |
|---|---|---|
| Backend (Jacoco) | 7 056 / 10 264 | 68,7 % |
| Python (trois services) | 458 / 483 | 94,8 % |
| Frontend, dénominateur tronqué | 1 169 / 1 832 | 63,8 % |
| Frontend, dénominateur complet | 1 125 / 3 254 | **34,6 %** |
| **Global avant** | 8 683 / 12 579 | **69,03 %** |
| **Global après** | 8 639 / 14 001 | **61,70 %** |

L'écart réel est de **−7,33 points**, non de −28. La conclusion tenait — 7,33 dépasse largement
le seuil de 1 % — mais l'ordre de grandeur comptait pour choisir le plancher.

**Ce qui est appliqué :** `target: 60%` sur `project`, `target: 70%` sur `patch`.

Un objectif **chiffré remplace la comparaison au commit de base**. Livrés dans la même PR,
`all: true` et `target: 60%` donnent 61,70 % contre un plancher de 60 % : **vert du premier
coup, sans statut à forcer**. C'est ce qui rend l'option « plancher » strictement meilleure que
l'option « accepter la chute », que je présentais à tort comme équivalente.

- **60 %** et non 62 % : le global atterrit à 61,70 %, un plancher à 62 % échouerait
  immédiatement et 61 % ne laisserait que 0,7 point. 60 % laisse 1,7 point de marge de
  travail — une marge, pas un droit à la dérive.
- **70 % sur le patch** : le code neuf est tenu plus haut que le legacy. C'est le seul levier
  qui fait remonter la couverture sans campagne dédiée, et il laisse de la place aux lignes
  qu'on ne teste pas raisonnablement (journalisation, gardes défensives). Une PR légitimement
  en dessous — un correctif d'une ligne dans un fichier non couvert — se traite en l'expliquant
  en revue, pas en baissant le seuil.

---

## F2 — `services/api.ts` : 92 fonctions, aucune couverte

C'est le point de passage obligé de tout le frontend : 410 lignes, 92 fonctions, importé par
neuf pages sur neuf. Sa couverture de fonctions est de **0 %**, et 18 instructions sur 164
sont exécutées — celles du module lui-même, pas de ses appels.

Ce n'est pas un oubli isolé : c'est le fichier dont la défaillance casse tout, et le seul du
répertoire `services/`. À comparer avec `lib/`, où chaque module a son test — la discipline
existe dans ce dépôt, elle ne s'est simplement pas appliquée ici.

Un test par fonction serait absurde. Ce qui vaut le coup est plus étroit : vérifier que chaque
groupe d'appels vise la bonne route et propage bien le corps et les erreurs.

### Correction

22 cas, ciblés sur les deux seules surfaces qui peuvent casser **en silence** :

- **l'intercepteur de réponse**, qui arbitre ce qui remonte à l'utilisateur. Cinq cas figent
  qu'une panne réseau alerte, qu'une 5xx affiche le message du serveur et non celui d'axios,
  que les **4xx sont laissées aux pages** — les remonter doublerait le message affiché — que
  l'identifiant de toast reste stable pour qu'un sondage en panne n'empile pas les alertes, et
  que l'erreur est toujours **rejetée**, sans quoi le `catch` de la page ne s'exécute jamais et
  l'écran reste en chargement ;
- **les URL assemblées à la main**. Une quinzaine d'appels concatènent `?actor=`,
  `?lifecycle=&actor=`, `?force=`, passent un corps à un `DELETE` ou montent un `FormData`.
  Rien dans le typage ne les protège.

Le cas le plus utile de l'ensemble : `commentApi.addHuman` et `commentApi.generate` visent la
**même route** et ne diffèrent que par un booléen `generate`. Les intervertir appellerait le
LLM à chaque commentaire saisi à la main — sans erreur visible, juste une facture de calcul et
un texte que personne n'a écrit.

**Couverture de `api.ts` : 0/92 → 19/92 fonctions**, 18/164 → 46/164 instructions. Le reste,
ce sont les `api.get('/x')` d'une ligne dont un test ne ferait que recopier l'URL — les couvrir
ferait monter le pourcentage sans rien protéger, soit exactement le geste que F1 dénonce.

Deux tests ont d'ailleurs échoué à la première exécution, sur des hypothèses fausses de ma
part : `getTelemetry` passe son plafond par `params` et non dans l'URL, et la méthode s'appelle
`addHuman`, pas `add`. Ils ont été corrigés d'après le code réel.

---

## F3 — `apiErrorMessage` est adopté sur un chemin d'erreur sur seize

`lib/apiError.ts` existe, et son commentaire explique précisément pourquoi :

> Le backend répond majoritairement en `ProblemDetail` (`detail`), mais certaines routes
> renvoient encore une map ad hoc (`error`). Ne lire que `detail` faisait retomber sur
> « Request failed with status code 409 » et perdre le seul message actionnable.

La fonction lit `detail`, puis `error`, puis `message`, puis se rabat sur un texte par défaut.
Elle règle un bug documenté.

**Un seul fichier l'importe** : `pages/FineTuning.tsx`. Les quinze autres appellent
`toast.error()` directement — 50 occurrences — et se répartissent en trois familles, toutes
problématiques :

- **la réimplémentation partielle.** `AbComparisonView.tsx` écrit
  `err?.response?.data?.message ?? err?.message`. Il lit `message` mais **pas `detail`** — or
  c'est `detail` que le backend renvoie majoritairement. Ce chemin retombe donc sur
  `err.message`, c'est-à-dire « Request failed with status code 409 » : le bug que
  `apiErrorMessage` corrige, reproduit à l'identique ;
- **l'abandon pur et simple.** `BatchEvaluateDialog.tsx` attrape `err: any` et affiche une clé
  générique sans rien extraire ;
- **la chaîne en dur.** `EmbeddingConsistencyCard.tsx` affiche `'Failed to start reindexing'`,
  ni traduit ni renseigné.

Les neuf `catch (err: any)` du frontend sont le mécanisme qui rend cela possible : le type
`any` autorise chaque site à inventer sa propre extraction, et personne ne voit qu'elle est
incomplète. `apiErrorMessage` prend un `unknown`, ce qui ferme la porte.

**C'est le meilleur rapport de cet audit** : une quinzaine de remplacements mécaniques, qui
suppriment une régression déjà identifiée une fois et neuf `any` au passage.

### Correction

**Vingt** sites d'appel passent désormais par `apiErrorMessage`, y compris **l'intercepteur
axios global** — qui ne lisait que `message` et affichait donc « Request failed with status
code 500 » sur toute erreur serveur portant un `ProblemDetail`. C'était le site le plus
visible, sur un toast vu partout, et il ne figurait pas dans le décompte initial de seize.

Une mesure a précisé le diagnostic au passage : `GlobalExceptionHandler` convertit les **62**
`ResponseStatusException` du backend en `ProblemDetail` (`detail`), tandis que **16** méthodes
de contrôleur renvoient un `Map.of("error", …)`. Les deux formes sont donc courantes, et les
six formules ad hoc se répartissaient ainsi :

| Formule | Sites | Ce qu'elle ratait |
|---|---|---|
| `detail ?? error ?? message` | 6 | rien — équivalente |
| `error ?? detail ?? message` | 2 | rien, priorité inversée |
| `detail ?? message` | 3 | les 16 réponses `error` |
| `error` seul | 4 | les 62 réponses `detail` |
| `detail ?? indice i18n` | 2 | `error` et `message` |
| `message` seul | 3 | **tout, dans les deux formes** |

Huit des vingt étaient donc déjà correctes. **Le paragraphe ci-dessus laissait entendre que
toutes étaient fautives : c'était trop fort**, et la mesure le corrige. Le gain n'en est pas
moins réel — douze sites rataient effectivement une forme sur deux, et centraliser met les
huit autres à l'abri d'un changement futur du format d'erreur.

`apiErrorStatus()` a été ajouté au même module pour les quatre sites qui lisent le **code**
HTTP (409 « déjà en cours », 429 contre-pression). Sans lui, ces sites conservaient un
`err: any` uniquement pour atteindre `response.status` — ce qui rouvrait la porte aux
extractions improvisées, c'est-à-dire à la cause même du défaut corrigé ici.

Effet mesuré sur F8 : **77 → 55 avertissements ESLint**, l'écart étant intégralement composé
de `no-explicit-any` devenus inutiles.

---

## F4 — La logique vit dans les pages, pas dans les composants

| | Fichiers | Lignes | Moyenne |
|---|---|---|---|
| `pages/` | 9 | 9 037 | **1 004** |
| `components/` | 44 | 5 474 | 124 |

Une page moyenne pèse huit fois un composant moyen. `Documents.tsx` compte **180 fonctions**
pour 16 % de couverture, `Ingestion.tsx` 66 fonctions pour 45 %.

Ce déséquilibre explique F1 et F2 mieux que n'importe quelle négligence : la logique est là où
elle est la plus coûteuse à tester — dans des composants qui montent tout l'écran, ouvrent des
dialogues et parlent au réseau. Les hooks métier existent pourtant déjà (`useSse`,
`useGlobalTasks`, `useStatus`), le chemin est balisé.

**À ne pas traiter tant que F1 et F2 ne sont pas faits.** Extraire de la logique d'un fichier
couvert à 16 % sans filet, c'est déplacer du risque, pas le réduire.

---

## F5 — Deux systèmes d'icônes, dont un pour deux icônes

| Système | Usages | Où |
|---|---|---|
| `material-symbols` (classe CSS) | **198** | partout |
| `lucide-react` (composants) | **2** | `StartupOverlay.tsx` uniquement |

`lucide-react` est une dépendance complète — poids de bundle, et un flux régulier de PR
Dependabot — pour deux icônes dans un seul écran. La convention du projet est visiblement
`material-symbols`, chargé en CSS depuis `fonts.css`.

### Correction

Une seule icône était concernée — `Loader2`, utilisée une fois dans `StartupOverlay`. Le projet
avait déjà sa convention de spinner (`material-symbols-outlined animate-spin">progress_activity`,
employée à cinq endroits) : la remplacer était un changement d'une ligne, et `lucide-react` est
retirée de `package.json`.

---

## F6 — Trois dépendances de formulaire pour une seule page

`react-hook-form`, `zod` et `@hookform/resolvers` ne sont importés que par `pages/FineTuning.tsx`.
Trois bibliothèques, un seul consommateur — et le chunk `FineTuning` pèse 127 kB (36 kB gzip),
le troisième du build.

### Vérification : à garder

La mesure tranche dans l'autre sens, et il faut le dire clairement — **ce constat ne débouche
sur aucune correction**.

Ces trois dépendances ne décorent pas un formulaire trivial : `zod` porte un schéma de **dix
champs** avec bornes (`min`/`max`), expression régulière et messages d'erreur traduits, et
`useForm` fournit `register`, `handleSubmit`, `watch`, `reset`, `setValue`, `getValues` et
`formState.errors`, tous utilisés.

Les retirer supposerait de réécrire à la main la validation, l'affichage des erreurs et l'état
du formulaire, sur une page de 1 156 lignes. Ce serait une régression déguisée en nettoyage —
exactement le raccourci que ces audits cherchent à éviter ailleurs. **L'outillage gagne sa
place** ; le constat se referme sur une décision consciente, ce qui était son objet.

---

## F7 — L'i18n est inégale : trois pages très en retard

Le dispositif est complet (`i18n/fr.json`, `i18n/en.json`, détection de langue) et bien utilisé
sur la majorité des pages. Trois font exception :

| Page | Appels `t()` | Chaînes en dur |
|---|---|---|
| `Documents.tsx` | 132 | 0 |
| `FineTuning.tsx` | 95 | 0 |
| `Ingestion.tsx` | 71 | 0 |
| **`Dashboard.tsx`** | 25 | **15** |
| **`Playground.tsx`** | 7 | **8** |
| **`Documentation.tsx`** | 4 | **73** |

`Playground` est une page de fonctionnalité principale : y laisser des chaînes non traduites
casse l'expérience dans l'une des deux langues.

### Correction, et un chiffre à rectifier

**Le tableau ci-dessus sous-estimait le problème.** Il comptait le texte entre balises ; en y
ajoutant les `aria-label`, `title`, `placeholder` et les toasts — tous lus par l'utilisateur ou
par un lecteur d'écran — `Playground` totalisait **43** chaînes en dur pour 8 appels à `t()`, et
non 8. La page était essentiellement monolingue.

42 d'entre elles sont désormais traduites (« BM25 » reste tel quel : c'est un nom
d'algorithme), plus les 18 de `Dashboard`. Les deux locales passent de 708 à **842 clés**, à
parité exacte.

**`Documentation` (64 chaînes) est laissée de côté, délibérément** — et le motif a changé
après vérification.

L'idée était que son contenu allait sortir du TSX (constat S5 de l'audit de simplification), et
que traduire un fichier voué à être restructuré serait du travail à jeter. **S5 s'est refermé
sans correctif** : la page est un écran conçu — 598 `className`, 146 grilles ou cartes — et non
un document, donc rien ne sera restructuré.

Le reliquat de F7 reste néanmoins hors périmètre, pour une autre raison. Sur ces ~70 chaînes,
**dix-sept sont des paragraphes multi-lignes portant du balisage en ligne** :

```tsx
Spectra lets you build your own artificial intelligence assistant specialized in{' '}
<strong>your business domain</strong>, from your own documents.
```

Les traduire correctement suppose de les restructurer en composants `<Trans>` d'i18next, puis
d'écrire en français une documentation technique destinée aux utilisateurs. Ce n'est pas une
passe mécanique comme sur `Playground` et `Dashboard`, où les chaînes étaient des libellés :
**c'est un travail de rédaction**, sur une page à 0 / 47 fonctions couvertes.

Une traduction mécanique produirait une documentation en mauvais français — pire, pour un
lecteur, qu'une documentation en anglais assumée. Le constat est donc **ouvert et qualifié** :
il attend quelqu'un qui écrive la version française, pas un refactoring.

### Le verrou

`i18n/locales.test.ts` empêche la dérive de recommencer. Une clé ajoutée d'un seul côté ne
casse rien de visible — i18next se rabat sur l'autre langue, et l'écran affiche simplement le
mauvais idiome. Personne ne le voit en revue. Trois cas ferment la porte : parité exacte des
clés, aucune traduction vide (pire qu'une clé absente, puisque le repli ne se déclenche même
pas), et **mêmes variables d'interpolation** de part et d'autre — un `{{name}}` oublié laisse
un trou dans la phrase sans qu'i18next ne signale quoi que ce soit.

---

## F8 — 73 des 77 avertissements ESLint sont `no-explicit-any` (55 après F3)

Le reste tient en quatre : un `react-refresh/only-export-components`, un
`react-hooks/exhaustive-deps`, deux sans règle identifiée.

58 annotations `: any` et 8 `as any`, aucun `@ts-ignore`. Neuf des `any` sont sur des erreurs
capturées, et F3 en supprime la raison d'être. Les autres méritent d'être traités au fil de
l'eau plutôt qu'en une passe : un diff de 73 corrections de typage est illisible en revue, et
c'est exactement le genre de PR où une vraie régression passe inaperçue.

---

## Ce qui va bien, et qu'il ne faut pas « améliorer »

- **L'accessibilité.** 203 attributs `aria-*`, 28 `role`, aucun `<div onClick>` sans rôle, un
  `useFocusTrap` dédié et testé. C'est nettement au-dessus de la moyenne.
- **Le découpage du bundle.** Les routes sont en chargement différé : le point d'entrée fait
  301 kB (96 kB gzip), et Recharts (302 kB) comme la page de documentation (268 kB) ne sont
  téléchargés que si l'on s'y rend. Rien à corriger.
- **`lib/`.** Huit modules, huit fichiers de test. C'est la partie la mieux tenue du dépôt, et
  le modèle à suivre pour F2.
- **React Query est réellement utilisé** (20 fichiers) — ce n'est pas une dépendance fantôme.
- **Aucun `catch {}` silencieux.** Les erreurs sont toujours remontées, même quand le message
  se perd (F3).
- **`ErrorBoundary` est monté** dans `App.tsx`, autour du routeur.

---

## Vérification

```
vitest run --coverage                       27 fichiers de test   OK
vitest run --coverage --coverage.all        chiffres réels mesurés
tsc --noEmit                                aucune erreur
eslint src                                  0 erreur, 77 avertissements
npm run build                               OK, chunks relevés
```

Les chiffres de F1 viennent d'une exécution réelle avec `all: true` et un `include` explicite,
puis `vitest.config.ts` a été restauré à l'identique — cet audit ne modifie aucun fichier.

**Ce qui n'a pas été vérifié :** le comportement en navigateur. Tout ici vient de l'analyse
statique, du build et de la suite de tests. Les constats d'accessibilité reposent sur la
présence de marqueurs (`aria-*`, `role`), pas sur un parcours au lecteur d'écran — un axe qui
mériterait son propre passage, avec Playwright et `axe-core`.
