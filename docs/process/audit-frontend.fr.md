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
| F1 | La couverture affichée vaut **le double** de la réelle | Mesure faussée | Faible, mais coûteux à assumer |
| F2 | `services/api.ts` : 92 fonctions, **0 %** couvert | Risque | Moyen |
| F3 | `apiErrorMessage` adopté sur **1 chemin d'erreur sur 16** | Régression latente | Faible |
| F4 | La logique vit dans les pages, pas dans les composants | Structure | Élevé |
| F5 | Deux systèmes d'icônes : 198 usages contre 2 | Poids inutile | Faible |
| F6 | Trois dépendances de formulaire pour une seule page | Poids inutile | Faible |
| F7 | i18n inégal : trois pages très en retard | Cohérence | Moyen |
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

**Correction, et pourquoi elle n'est pas appliquée ici.** Ajouter `all: true` et un `include`
prend trois lignes. Mais `codecov.yml` compare au commit de base avec `threshold: 1%` : publier
soudain 33 % au lieu de 61 % ferait **échouer le statut Codecov** de la PR qui corrige le
réglage. C'est très probablement la raison pour laquelle personne ne l'a fait.

Deux façons de l'assumer, et c'est une décision de mainteneur :

- **accepter la chute une fois** — corriger le réglage et forcer le statut sur cette PR, en
  expliquant dans le corps que le chiffre ne baisse pas, il devient vrai ;
- **poser un plancher explicite** — remplacer `target: auto` par un objectif chiffré (par
  exemple 33 %) au moment d'activer `all`, puis le remonter progressivement. L'indicateur
  redevient honnête *et* la CI reste verte.

Je recommande la seconde : elle rend le progrès mesurable au lieu de repartir d'un « auto »
qui, lui, se contentera de figer la nouvelle base.

---

## F2 — `services/api.ts` : 92 fonctions, aucune couverte

C'est le point de passage obligé de tout le frontend : 410 lignes, 92 fonctions, importé par
neuf pages sur neuf. Sa couverture de fonctions est de **0 %**, et 18 instructions sur 164
sont exécutées — celles du module lui-même, pas de ses appels.

Ce n'est pas un oubli isolé : c'est le fichier dont la défaillance casse tout, et le seul du
répertoire `services/`. À comparer avec `lib/`, où chaque module a son test — la discipline
existe dans ce dépôt, elle ne s'est simplement pas appliquée ici.

Un test par fonction serait absurde. Ce qui vaut le coup est plus étroit : vérifier que chaque
groupe d'appels vise la bonne route et propage bien le corps et les erreurs. Une vingtaine de
cas sur les familles réellement utilisées suffiraient à couvrir la surface qui casse.

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

Remplacer ces deux icônes et retirer la dépendance est un changement d'une ligne par icône.

---

## F6 — Trois dépendances de formulaire pour une seule page

`react-hook-form`, `zod` et `@hookform/resolvers` ne sont importés que par `pages/FineTuning.tsx`.
Trois bibliothèques, un seul consommateur — et le chunk `FineTuning` pèse 127 kB (36 kB gzip),
le troisième du build.

Ce n'est pas nécessairement à supprimer : si d'autres formulaires validés arrivent, l'outillage
est déjà là et le choix est bon. Mais c'est une décision à prendre consciemment, pas à
subir — et si aucun autre formulaire n'est prévu, une validation manuscrite y coûterait moins
cher que trois dépendances à maintenir.

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

`Playground` est une page de fonctionnalité principale : y laisser huit chaînes non traduites
casse l'expérience dans l'une des deux langues. `Documentation` est un cas à part, traité par
le constat S5 de l'audit de simplification — son contenu devrait sortir du TSX, ce qui réglera
l'i18n en même temps.

---

## F8 — 73 des 77 avertissements ESLint sont `no-explicit-any`

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
