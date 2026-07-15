# Audit ergonomique de l'interface web Spectra

> **Suivi (mise à jour)** — Constats **#1 à #6 traités** :
> #1 confirmations avant suppression (`ConfirmDialog` + Documents/Model Hub) ;
> #2 ablation asynchrone suivie, annulable, persistée (`AblationJob`, `/api/ablation/async`) ;
> #3 annulation des tâches depuis le TaskCenter (des endpoints DELETE existaient déjà pour
> dataset/éval/A-B/fine-tuning/ingestion — l'audit les avait manqués ; ajoutés pour DPO,
> installations et ablation ; il manque encore le benchmark qualité) ;
> #4 migration i18n FR/EN des écrans Ingestion, Documents, Fine-Tuning, Comparison, Model Hub,
> Optimization et de leurs panneaux (676 clés/langue) ;
> #5 toggle « Synthetic Q&A » supprimé ; #6 « Max Chunks » remplacé par des presets explicites.
> Traités au passage : #9 (`alert()` → toast), #12 (dates via `i18n.language`),
> #13 (grilles Comparison/A-B responsives), partie de #18/#19.

> Audit réalisé sur le code du frontend (`frontend/src/`, React 19 + TypeScript + Tailwind v4 +
> React Query + SSE) et les contrôleurs backend concernés. Basé exclusivement sur la lecture du
> code — les points nécessitant une vérification visuelle ou manuelle sont signalés par 👁.
>
> Périmètre : 9 écrans (`Dashboard`, `Model Hub`, `Ingestion`, `Documents`, `Fine-Tuning`,
> `Playground`, `Comparison`, `Optimization`, `Documentation`) + composants transverses
> (`TaskCenter`, `WizardProgress`, `ServiceHealthBanner`, `StartupOverlay`, `Header`, `Sidebar`).

---

## 1. Synthèse générale

L'application est **globalement mature sur le plan UX**, avec une infrastructure de suivi des
traitements longs remarquablement soignée : centre d'activité global temps réel
(`useGlobalTasks` : SSE `/api/sse/tasks` + repli polling adaptatif), barres de progression
honnêtes (déterminées quand le total est connu, balayage sinon), ETA par extrapolation, titre
d'onglet dynamique, notifications navigateur opt-in, toasts de fin hors-page. Le Playground est
exemplaire (streaming token par token, bouton Stop, garde d'inactivité glissante, Retry, édition
de message, persistance de la conversation). Les états vides, skeletons et messages de
remédiation (`ServiceHealthBanner` avec commandes shell) sont présents presque partout.

Les trois frictions majeures sont : **(1) des suppressions destructives sans aucune
confirmation** (Documents, en ligne, en masse et dans la fiche) ; **(2) l'écran Optimization dont
l'ablation est un appel HTTP bloquant de jusqu'à 30 minutes**, sans progression réelle, sans
annulation, dont le résultat est perdu au moindre refresh — à contre-courant du pattern de jobs
asynchrones que le reste de l'application maîtrise ; **(3) une incohérence de langue
généralisée** : l'i18n FR/EN ne couvre que ~103 clés, la majorité des écrans est en anglais codé
en dur, l'écran Optimization est en français codé en dur, et le sélecteur de langue du header n'a
d'effet que sur une fraction de l'interface. S'y ajoute l'absence totale de mécanisme
d'annulation des tâches de fond (aucun endpoint `cancel` dans le backend).

---

## 2. Tableau de constats priorisés

Impact/Effort : 🔴 fort · 🟠 moyen · 🟢 faible. Trié par (impact décroissant, effort croissant).

| # | Écran / Zone | Problème observé | Impact | Effort | Recommandation |
|---|---|---|---|---|---|
| 1 | **Documents** — `Documents.tsx:519` (ligne), `:882` (barre bulk), `:1225` (fiche) ; **Model Hub** — `ModelStoragePanel.tsx:98` | **Suppression définitive en un clic, sans confirmation** : l'icône poubelle est présente sur chaque ligne, à 40 px du reste de la ligne cliquable ; « Delete » en masse supprime N documents ; « Delete permanently » supprime document + chunks ; le panneau Storage supprime un GGUF de plusieurs Go. Aucun `confirm`, aucun undo. | 🔴 | 🟢 | Dialogue de confirmation (réutiliser le pattern modal + `useFocusTrap` déjà présents) indiquant ce qui sera perdu (« X documents, Y chunks indexés »), ou toast avec bouton *Annuler* et suppression différée. Prioritaire pour le bulk delete. |
| 2 | **Optimization** — `Optimization.tsx:246-253`, `api.ts:253-257`, `RagAblationController.java` | **L'ablation est un POST synchrone bloquant avec timeout de 30 min** : spinner indéterminé sans progression ni ETA (alors que le backend traite bras × runs × questions, donc un ratio existe), aucune annulation, résultat stocké en state local → **navigation ou F5 = résultat perdu alors que le serveur continue de calculer**. Invisible dans le TaskCenter. | 🔴 | 🔴 | Aligner sur le pattern déjà existant de `QualityBenchmarkController` (`POST /compare/async` + job + polling) : job d'ablation asynchrone avec `processed/total`, intégré à `useGlobalTasks`, résultat persisté côté serveur et rechargeable. |
| 3 | **Toute l'app** — backend (aucun endpoint `cancel`, vérifié sur les 19 contrôleurs) | **Aucune tâche longue n'est annulable** : ingestion d'un gros ZIP, génération dataset « ALL chunks », fine-tuning CPU de plusieurs heures, batch d'évaluations, téléchargement de modèle. Seule issue : redémarrer les conteneurs. Le Playground est la seule exception (AbortController). | 🔴 | 🔴 | Endpoints `DELETE /api/<domaine>/<taskId>` (interruption coopérative) + bouton « Annuler » dans le `TaskCenter` et sur les pages. Commencer par les tâches les plus longues (fine-tuning, dataset, install). |
| 4 | **Toute l'app** — i18n (`i18n/*.json` : 103 clés ; namespaces `nav, dashboard, playground, taskCenter, wizard, health, header, docs` uniquement) | **Couverture i18n très partielle et langue incohérente** : Ingestion/Documents/Fine-Tuning/Comparison/Model Hub ~100 % anglais en dur ; Optimization ~100 % **français** en dur ; `StartupOverlay` français en dur ; Model Hub mélange les deux dans le même écran (« Installer », « Réinitialiser », « Executables », « vitesse » au milieu de libellés anglais, `ModelHub.tsx:278,203,396,426`). Le bouton FR/EN du header ne traduit qu'une fraction de l'UI. | 🔴 | 🟠 | Terminer la migration i18n écran par écran (l'infra est en place et documentée comme « migration progressive » dans `i18n/index.ts`). À minima, corriger immédiatement les mélanges intra-écran (Model Hub, Optimization). |
| 5 | **Ingestion** — `Ingestion.tsx:377-385` | **Toggle « Synthetic Q&A » piège** : coché par défaut, le décocher ne change rien au traitement — il fait seulement échouer le bouton « Initialize Pipeline » avec un toast d'erreur. Une option qui n'a qu'un pouvoir de blocage est de la charge cognitive pure (le commentaire du code l'admet : « there is no other mode here »). | 🟠 | 🟢 | Supprimer la case. Si un mode « indexation seule » a du sens, le déplacer comme choix explicite au niveau du bouton de génération. |
| 6 | **Ingestion** — `Ingestion.tsx:778-785` | **Slider « Max Chunks » contre-intuitif** : min = 0 = « ALL » (le traitement le plus lourd est à l'extrémité gauche), défaut 10, graduations affichées « All / 50 / 100 » qui ne correspondent pas aux crans réels (step 5). | 🟠 | 🟢 | Remplacer par des presets explicites : « Essai (10) · 50 · 100 · Tout le corpus », avec estimation de durée (« ~N appels LLM »). |
| 7 | **Documents** — `Documents.tsx:446-455` (lignes = `div onClick`), **Fine-Tuning** — `FineTuning.tsx:674-678` (`<tr onClick>`) | **Éléments interactifs non accessibles au clavier** : les lignes de la liste de documents et de l'historique d'entraînement ne sont ni focusables ni activables au clavier (pas de `role="button"`, `tabIndex`, `onKeyDown`). Contraste avec le soin mis ailleurs (focus trap, `aria-*`). 👁 | 🟠 | 🟢 | `role="button" tabIndex={0}` + Enter/Espace, ou envelopper le contenu principal dans un vrai `<button>`. |
| 8 | **Header** — `Header.tsx:29-31` + `index.css` (tokens absents) | **Pastilles santé Chat/Embed probablement invisibles** : `bg-success` et `bg-warning` n'existent pas dans les tokens CSS (seuls `--color-muted-foreground`, `--color-card`… ont été ajoutés). En Tailwind v4, ces classes ne génèrent rien → la pastille est transparente quand le service est OK ou en chargement. 👁 vérifier visuellement. | 🟠 | 🟢 | Définir `--color-success`/`--color-warning` ou réutiliser `bg-primary`/`bg-secondary` comme partout ailleurs. |
| 9 | **Comparison** — `Comparison.tsx:192` | **`alert()` natif** en cas d'échec de lancement d'évaluation, seul endroit de l'app à ne pas utiliser les toasts sonner. Bloquant, non stylé, incohérent. | 🟠 | 🟢 | `toast.error(...)` avec le détail de l'erreur API comme sur les autres écrans. |
| 10 | **Playground** — `Playground.tsx:283-286` vs `:695-699` | **Messages contradictoires au changement de modèle** : le toast dit « llm-chat reloads it automatically within a few seconds », mais la note sous le sélecteur dit « Effective on the next chat service restart ». L'utilisateur ne sait pas s'il doit redémarrer. | 🟠 | 🟢 | Garder une seule formulation (celle du toast, qui correspond au comportement du superviseur décrit dans Model Hub) et supprimer la note. |
| 11 | **Ingestion** — `Ingestion.tsx:224-230, 310-316` ; **Fine-Tuning** — `FineTuning.tsx:323-327` | **Abandon silencieux du suivi après 5 échecs de polling** : l'entrée reste affichée « PROCESSING » pour toujours, sans indication que le suivi est coupé ni bouton pour le relancer (le `TaskCenter`, lui, affiche son état de connexion — bon pattern non repris ici). | 🟠 | 🟢 | Marquer visuellement « suivi interrompu » + action « reprendre le suivi », ou s'appuyer sur le point 14. |
| 12 | **Dates** — `Documents.tsx:73` et `Comparison.tsx:328,338` (`en-US`), `FineTuning.tsx:695` (`en-US`), `Ingestion.tsx:672` et `Dashboard.tsx:898` (`fr-FR`), `AbComparisonView.tsx:161` (`en-US`) | **Trois conventions de format de date différentes** selon l'écran, indépendantes de la langue choisie. | 🟠 | 🟢 | Helper unique `formatDate()` branché sur `i18n.language` (via `Intl.DateTimeFormat`), utilisé partout. |
| 13 | **Comparison / A-B** — `Comparison.tsx:294` (`grid-cols-[260px_1fr]`), `AbComparisonView.tsx:152` (`grid-cols-[240px_1fr]`) | **Grilles à colonne fixe sans variante responsive** (le squelette de chargement, lui, a bien le préfixe `lg:` — `:281`). Sur écran étroit, la zone de détail est écrasée. 👁 | 🟠 | 🟢 | `grid-cols-1 lg:grid-cols-[260px_1fr]` comme sur le skeleton. |
| 14 | **Ingestion / Fine-Tuning** — pollers locaux `setInterval` (`pollIngest` 3 s, `pollGenTask` 5 s, poll job 4 s) coexistant avec `useGlobalTasks` (SSE) | **Double système de suivi des tâches** : chaque page ré-implémente son polling avec sa propre gestion d'erreurs/cleanup, alors que le flux SSE global transporte déjà les mêmes données normalisées. Coût : code dupliqué (~150 lignes), risques d'états divergents entre la page et le TaskCenter, requêtes redondantes. | 🟠 | 🟠 | Dériver l'affichage par page depuis `useGlobalTasks` (filtré par `kind`), en gardant au plus un `GET` de détail à l'ouverture. Supprime aussi le point 11. |
| 15 | **Documents** — filtres format/qualité/tri client-side sur pagination serveur (`Documents.tsx:136-167, 370-390`) | **Filtres appliqués seulement aux documents déjà chargés** (lots de 200) : « quality ≥ 75% » ou le tri par chunks peuvent donner des résultats différents après chaque « Load more ». Le compteur « N shown · M loaded / T total » atténue mais n'explique pas. | 🟠 | 🟠 | Pousser format/qualité/tri côté serveur (lifecycle et recherche y sont déjà), ou afficher un avertissement quand `hasNextPage` et un filtre client sont actifs. |
| 16 | **Dashboard** — `Dashboard.tsx:119-141` | **Stats de commentaires calculées côté client à coups de 21 requêtes** (`listDocuments size=500` + 20 × `commentApi.list`) toutes les 60 s, et tronquées aux 20 premiers documents → chiffres faux dès 21 documents commentés. | 🟠 | 🟠 | Endpoint agrégé côté serveur (`/api/ged/stats` embarque déjà `commentStats` — utiliser celui-là et supprimer le calcul client, ou le compléter côté backend). |
| 17 | **Fine-Tuning** — `FineTuning.tsx:351-353` | **Progression d'entraînement à granularité époque** : avec 3 époques, la barre saute 0 → 33 → 66 → 100 %, sans ETA, alors que des logs par step arrivent via SSE et que `etaMs()` existe dans `useGlobalTasks`. | 🟠 | 🟠 | Afficher l'ETA (fonction déjà écrite) et, si le backend le permet, une progression par step au sein de l'époque. |
| 18 | **Modales** — trace RAG (`Playground.tsx:1057-1159`), `BatchEvaluateDialog.tsx` | **Comportement modal incohérent** : la fiche Documents a focus trap + Échap + restauration du focus (`useFocusTrap`), mais la modale de trace et le dialogue batch n'ont ni piège de focus ni fermeture Échap. | 🟢 | 🟢 | Réutiliser `useFocusTrap` (déjà écrit et testé) sur les trois modales. |
| 19 | **Fine-Tuning** — `FineTuning.tsx:204, 227, 283` | **Échecs silencieux** (`catch { /* ignore */ }`) sur chargement de recette, export de recette et chargement de l'historique : l'utilisateur clique « Export Recipe » et il ne se passe rien en cas d'erreur. | 🟢 | 🟢 | Toast d'erreur minimal sur les actions déclenchées par un clic (les fetchs de fond peuvent rester silencieux). |
| 20 | **Documents** — `deleteMutation` (`:238-246`) | Suppression sans `onError` : un échec 4xx (ex. document lié à un modèle) est totalement muet — l'intercepteur axios ne couvre que réseau/5xx. | 🟢 | 🟢 | Ajouter `onError` avec le détail du problème. |
| 21 | **Ingestion** — écran-fleuve « Étape 1/2/3 » + bandeau d'état + double indicateur de pipeline (header d'écran + `WizardProgress` global juste au-dessus) | **Redondance des indicateurs de progression** : sur `/ingestion` on voit simultanément le fil global (Ingestion→Dataset→Training→Querying) et le mini-pipeline local (Ingest→Generate→Ready), qui disent presque la même chose avec deux styles différents. | 🟢 | 🟠 | Supprimer le mini-pipeline local ou fusionner les deux (le `WizardProgress`, branché sur l'état réel, est le meilleur des deux). |
| 22 | **Typographie globale** — quasi tous les composants | **Corps de texte de 9 à 11 px, en capitales avec espacement large, généralisés** (`text-[9px]`…`text-[11px]` + `uppercase tracking-widest`). Esthétique « console » assumée, mais en dessous des seuils de lisibilité confortable pour des contenus informatifs (erreurs, aides, stats). 👁 à arbitrer avec un test utilisateur. | 🟢 | 🟠 | Réserver le style micro-caps aux étiquettes ; passer les contenus porteurs d'information (messages d'erreur, descriptions, aides) à ≥ 12-13 px sans capitales forcées. |

---

## 3. Focus « traitements longs »

### Inventaire des traitements identifiés

| Traitement | Déclencheur | Mécanisme | Indicateur | ETA | Annulation | Survit au refresh | Fin signalée |
|---|---|---|---|---|---|---|---|
| Upload + ingestion fichier/URL | Ingestion | POST → task + polling 3 s | ✅ déterminé dès que `chunksExpected` connu, balayage sinon | ❌ (page) / ✅ (TaskCenter) | ❌ | ✅ (restauration au mount + TaskCenter) | ✅ toast + stats |
| Génération dataset Q/A | Ingestion | POST → task + polling 5 s | ✅ `chunksProcessed/totalChunks` | ✅ (`etaMs`) | ❌ | ✅ | ✅ |
| Génération DPO | (API) | task + TaskCenter | ✅ | ✅ | ❌ | ✅ | ✅ (TaskCenter) |
| Fine-tuning | Fine-Tuning | POST → job + polling 4 s + SSE logs | ⚠️ par époque seulement | ❌ (page) | ❌ | ✅ (`jobRestoredRef`) | ✅ toast précis (« export GGUF & register to deploy ») |
| Évaluation / batch / A-B | Comparison | POST → job + polling 4-5 s | ✅ `processed/testSetSize` | ✅ (TaskCenter) | ❌ | ✅ | ✅ |
| Téléchargement modèle | Model Hub | POST → SSE `%` par modèle | ✅ % réel | ✅ (TaskCenter) | ❌ | ✅ (sessionStorage + rejeu SSE serveur) | ✅ toast détaillé |
| Benchmark qualité | Model Hub (CTA) | POST async → job + polling 3 s | ⚠️ `currentStep` texte, pas de ratio | ❌ | ❌ | ⚠️ jobId en state local — CTA perdu au refresh (le job continue et reste visible au TaskCenter) | ✅ tableau de résultats |
| **Ablation A/B** | Optimization | **POST synchrone, timeout 30 min** | ❌ spinner indéterminé | ❌ | ❌ | ❌ **résultat perdu, requête orpheline** | ⚠️ toast, résultat volatile |
| Requête RAG streaming | Playground | fetch SSE POST | ✅ tokens en direct + TTFT | n/a | ✅ **Stop** + garde d'inactivité 120 s | ⚠️ réponse partielle assainie au reload (bon) | ✅ métriques, sources, badges |
| Génération commentaire IA | Documents | mutation simple | ⚠️ libellé bouton seulement | ❌ | ❌ | ❌ | ✅ toast |
| Réindexation embeddings | Dashboard (carte) | job serveur | (non audité en détail) | — | ❌ | ✅ | — |

### Constats transverses

1. **Le pattern « job asynchrone + TaskCenter » est excellent et devrait être la règle unique.**
   Deux traitements y échappent : l'ablation (synchrone, voir constat #2) et la génération de
   commentaire IA (mutation courte mais LLM sur CPU = potentiellement > 30 s, seul feedback :
   libellé du bouton).

2. **Aucune annulation nulle part** (constat #3). Pour des traitements CPU de plusieurs heures
   (fine-tuning) ou coûteux (dataset « ALL »), c'est le manque le plus important. À défaut
   d'annulation backend, au minimum : avertir avant lancement de l'ordre de grandeur
   (l'Optimization le fait bien : « Bloquant et lent sur CPU : N bras × M runs… » —
   généraliser cette annonce au fine-tuning et à la génération dataset).

3. **Honnêteté des indicateurs : bonne** — les barres ne simulent jamais de progression
   (déterminées seulement si dénominateur connu, sinon balayage explicite). L'ETA par
   extrapolation linéaire est signalée comme estimation. À reproduire sur le benchmark qualité
   (exposer `processed/total` plutôt que `currentStep` texte).

4. **Garde-fous pré-lancement inégaux** : la génération dataset vérifie qu'une ingestion n'est
   pas en cours (`window.confirm` — remplacer par le pattern modal maison), mais le fine-tuning
   ne vérifie pas que le dataset existe (`datasetSize=0` échouera ou entraînera sur rien), et
   deux jobs de fine-tuning peuvent être soumis en parallèle sans avertissement.

5. **Erreurs de fin de tâche bien traitées** dans l'ensemble (message d'erreur affiché dans le
   panneau + toast + état FAILED), cas OOM d'ingestion traité spécifiquement avec un message
   actionnable — bon exemple à suivre.

---

## 4. Quick wins (fort impact / faible effort)

1. **Confirmation avant toute suppression** (Documents ligne/bulk/fiche, Model Hub GGUF) —
   constat #1. Le plus gros risque utilisateur de l'app, corrigeable en réutilisant
   `useFocusTrap` + le style modal existant.
2. **Supprimer le toggle « Synthetic Q&A » et refondre le slider « Max Chunks » en presets**
   (constats #5, #6) — élimine les deux pièges du parcours nominal d'onboarding.
3. **Remplacer `alert()` par un toast** (Comparison, constat #9) et **unifier le message
   d'activation de modèle** (Playground, constat #10) — deux corrections de quelques lignes.
4. **Définir les tokens `success`/`warning` manquants** (constat #8) — les pastilles santé du
   header sont probablement invisibles à l'état « OK ».
5. **Helper de date unique branché sur la langue** (constat #12) + **Échap/focus-trap sur les
   modales restantes** (constat #18).

## 5. Recommandations structurelles

1. **Asynchroniser l'ablation** sur le modèle de `QualityBenchmarkController.compareAsync`
   (job + progression + persistance du rapport), l'intégrer à `useGlobalTasks`, et prévoir
   l'annulation. C'est l'écart le plus flagrant avec le reste de l'architecture.
2. **API d'annulation des tâches** (`DELETE /api/…/{taskId}`) + bouton dans le TaskCenter —
   par ordre de durée décroissante : fine-tuning, dataset, install, évaluations.
3. **Terminer la migration i18n** (l'infra et le mécanisme de fallback existent déjà) — l'état
   actuel, tri-lingue selon l'écran, nuit à la crédibilité de l'ensemble.
4. **Unifier le suivi des tâches sur `useGlobalTasks`** et supprimer les pollers `setInterval`
   locaux d'Ingestion et Fine-Tuning (moins de code, plus de cohérence, corrige l'abandon
   silencieux du polling).
5. **Déplacer côté serveur** les filtres riches de Documents (format/qualité/tri) et
   l'agrégation des stats de commentaires du Dashboard.
6. **Consolider les représentations du parcours** : `WizardProgress` (état réel) comme unique
   fil conducteur ; supprimer le mini-pipeline local d'Ingestion et rapprocher les cartes
   « Pipeline » du Dashboard de ses libellés.

---

## 6. Points forts à préserver

- `useGlobalTasks` + `TaskCenter` : SSE avec repli polling adaptatif, normalisation
  multi-sources, ETA, notifications, badge de titre d'onglet — une référence.
- Playground : streaming, Stop, garde d'inactivité *glissante* (ne tue pas une génération lente
  mais saine), Retry sur erreur, édition/régénération avec variantes de température,
  assainissement des états transitoires au reload.
- `ServiceHealthBanner` : diagnostic en langage clair + commande de remédiation, signature de
  dismiss qui réapparaît si le problème change.
- Mutations optimistes avec snapshot/rollback/reconciliation (Documents).
- Reprise des téléchargements de modèles après navigation (sessionStorage + rejeu SSE serveur).
- Messages de fin honnêtes (« Adapter trained — export to GGUF & register to deploy » plutôt
  qu'un faux « déployé »).
