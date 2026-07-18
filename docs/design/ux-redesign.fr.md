# Spectra — Audit UX & refonte du design system

> Refonte visuelle et ergonomique de l'interface React, sans modification de la
> logique métier. Objectif : un rendu de SaaS premium (Linear, Notion, Vercel,
> Stripe), lisible, sobre et cohérent.

---

## 1. Audit global (avant refonte)

### Constat

L'interface fonctionnait bien mais souffrait d'une direction artistique
« terminal cyberpunk » qui dégradait la perception de qualité et la lisibilité :

| Problème | Impact UX |
|---|---|
| ~450 libellés en MAJUSCULES + `letter-spacing` extrême (0,1–0,2 em) | Lecture ralentie de 10–20 %, ton « criard », hiérarchie écrasée |
| Micro-texte 9–11 px omniprésent | Fatigue visuelle, accessibilité limite (WCAG 1.4.4) |
| Palette néon (cyan `#8ff5ff`, magenta `#d674ff`) sur bleu nuit | Halos, vibrations chromatiques, aspect « gamer » plutôt que produit pro |
| Zéro arrondi (`border-radius: 0`) + glows (`text-shadow`, box-shadow néon) | Rendu daté, agressif, éloigné des standards SaaS actuels |
| Deux familles typographiques (Space Grotesk display + Inter) | Compétition visuelle, capitales Space Grotesk peu lisibles en petit corps |
| Navigation plate de 9 entrées sans regroupement | Charge cognitive, parcours utilisateur invisible |
| Couleurs de charts codées en dur, non testées daltonisme | Incohérences entre donut/légende, paires vert/magenta indiscernables en CVD |
| États vides en italique-majuscules (« NO ACTIVE JOB. ») | Ton robotique, aucune invitation à l'action |

### Points forts conservés

- Architecture saine : tokens sémantiques consommés partout (`surface`,
  `on-surface`, `primary`…) → la refonte s'est faite **par les tokens**, sans
  réécrire la logique des pages.
- Lazy loading des routes, React Query avec `Promise.allSettled`, skeletons,
  i18n FR/EN, focus visible, `prefers-reduced-motion` respecté.
- WizardProgress branché sur l'état réel du système (excellent pattern).

### Stratégie de refonte

Toutes les pages consommant les mêmes tokens, la refonte agit sur **quatre
leviers systémiques** plutôt que page par page :

1. **Tokens** (`index.css`) : mêmes noms, nouvelles valeurs → rethème intégral.
2. **Overrides non-layerés** : règles CSS hors `@layer` (prioritaires sur les
   utilitaires Tailwind) qui normalisent d'un coup la casse des titres, le
   letter-spacing, les tailles 9–11 px et les arrondis — sans toucher au JSX.
3. **Shell** réécrit à la main : Sidebar, Header, Layout, Tooltip, loader.
4. **Kit `components/ui/`** : base de convergence pour les évolutions futures.

---

## 2. Charte graphique

### Couleurs (thème sombre unique)

| Rôle | Token | Valeur | Usage |
|---|---|---|---|
| Fond | `--color-surface` | `#0b0d10` | Arrière-plan application |
| Surface basse | `--color-surface-container-low` | `#0e1114` | Sidebar, bandeaux |
| Carte | `--color-surface-container` | `#12151a` | Panneaux de contenu |
| Surface haute | `--color-surface-container-high` | `#191d24` | Hover, contrôles, tooltips |
| Surface maximale | `--color-surface-container-highest` | `#21262f` | Popovers, éléments saillants |
| Texte principal | `--color-on-surface` | `#e8eaf0` | Contraste ≈ 13:1 |
| Texte secondaire | `--color-on-surface-variant` | `#9aa3b2` | Contraste ≈ 6:1 (AA) |
| Texte atténué | `--color-outline` | `#79839a` | Icônes, méta-infos (AA sur cartes) |
| Bordures | `--color-outline-variant` | `#2a303b` | Filets 1 px |
| **Accent (marque)** | `--color-primary` | `#a3adff` | Actions, liens, état actif |
| Texte sur accent | `--color-on-primary` | `#141833` | Boutons primaires |
| Secondaire | `--color-secondary` | `#c9a9f7` | Génération / entraînement |
| Succès | `--color-success` | `#6ee7a0` | Services en ligne, confirmations |
| Avertissement | `--color-warning` | `#f5c264` | Chargements, seuils |
| Erreur | `--color-error` | `#f58c8c` | Pannes, échecs, suppressions |

Principe : **les neutres portent l'interface, l'accent porte l'action.**
L'indigo n'apparaît que sur ce qui est cliquable ou actif ; les états système
(succès/warning/erreur) sont réservés et jamais décoratifs.

### Palette de visualisation de données

Palette catégorielle **validée** (bande de luminance sombre OKLCH 0,48–0,67,
contraste ≥ 3:1 sur `#12151a`, séparation daltonisme ΔE ≥ 8, plancher
vision normale ΔE ≥ 15 — via le validateur du design system) :

```
1. #6673f0 (indigo — marque)   4. #c98500 (ambre)
2. #008300 (vert)              5. #199e70 (vert d'eau)
3. #d55181 (magenta)           6. #9a6ee0 (violet)
```

L'ordre des slots est le mécanisme de sécurité CVD : il est **fixe, jamais
recyclé**. Cycle de vie documentaire : ingested = indigo, qualified = violet,
trained = vert d'eau, archived = gris neutre `#5c6675`, erreur = `#e66767`.

### Typographie

| Usage | Style |
|---|---|
| Famille unique | **Inter** (features `cv05`, `cv11`), antialiasing activé |
| Titre de page (h2) | 24 px / semibold / tracking −0,01 em / sentence case |
| Titre de section (h3) | 14 px / semibold |
| Corps | 13 px / regular / `on-surface` |
| Secondaire | 12 px / `on-surface-variant` |
| Overline / étiquette | 11 px / medium / MAJUSCULES / tracking 0,05 em — **réservé aux micro-étiquettes**, jamais aux phrases |
| Chiffres clés | 28 px / semibold / `tabular-nums` (stabilité au refresh) |

Space Grotesk est retiré de la hiérarchie (le token `--font-headline` pointe
sur Inter). Règle éditoriale : **plus aucune phrase en capitales** — les
overrides CSS neutralisent `uppercase` sur `font-headline` et `font-label`.

### Espacements & formes

- Grille de **8 px** (padding cartes : 16/20/24 px ; gaps : 8/16/24 px).
- Rayons : 6 px (chips, inputs discrets) · 8 px (boutons, contrôles) ·
  12 px (cartes, panneaux) · pilule (barres de progression, pastilles).
- Élévation : liseré `inset 0 0 0 1px rgb(255 255 255 / 0.045)` sur les
  cartes ; ombre portée douce uniquement au hover (`card-hover`) et sur les
  surfaces flottantes (toasts, tooltips). **Aucun glow.**
- Animations : 150–300 ms, `ease`, uniquement sur opacité/transform/couleur ;
  neutralisées par `prefers-reduced-motion`.

---

## 3. Analyse par page

Le rethème par tokens s'applique à toutes les pages ; ci-dessous les constats
spécifiques et l'état après refonte.

### Dashboard (`/`)
- **Faiblesses** : 6 sections empilées de même poids visuel, KPI en néon-glow,
  légende du donut codée en dur, bannières-phrases en capitales.
- **Refonte** : titre sentence case, tuiles KPI à liseré d'accent supérieur,
  chiffres tabulaires sans glow, légende alignée sur la palette validée.
- **Conservé** : « Getting started » conditionnel (excellent onboarding),
  polling 30 s, tolérance de panne par source.

### Model Hub (`/model-hub`)
- **Faiblesses** : contrôles (`SHOW`, `FILTER`) en capitales, panneau
  d'erreur pointillé agressif.
- **Refonte** : cartes accordéon Storage/Historique arrondies, erreur avec
  action « Retry » en bouton primaire (déjà présent — pattern à généraliser).

### Ingestion (`/ingestion`)
- **Faiblesses** : états vides italique-majuscules (4 occurrences), pipeline
  animé néon.
- **Refonte** : états vides en 12 px sentence case ; connecteurs et beams
  recolorés en accent doux ; les animations de flux restent (bonne
  affordance de progression) mais adoucies.

### Documents (`/documents`)
- **Faiblesses** : la plus dense (1 300 lignes), 69 usages de capitales,
  tableaux serrés en 10 px.
- **Refonte** : tailles remontées à 11–12 px, chips arrondies, rangées
  `cv-auto` conservées (perf). Candidate prioritaire à l'adoption du kit
  `ui/` (Badge, EmptyState, tableau standard).

### Fine-Tuning (`/fine-tuning`)
- **Refonte** : états vides réécrits (« No active job » actionnable),
  historique de jobs lisible, StepBar recolorée via tokens.
- **Conservé** : console de logs, auto-scroll, pipeline temps réel.

### Playground (`/playground`)
- **Faiblesses** : labels de paramètres en capitales, panneau latéral chargé.
- **Refonte** : labels sentence case via overrides, boutons RAG Advisor /
  Export / Clear déjà hiérarchisés (ghost/danger) — rendu premium immédiat.

### Comparison & Optimization
- **Refonte** : radars et charts d'ablation sur la palette validée
  (positif `#199e70` / négatif `#e66767`), chips d'état arrondies.

### Documentation (`/documentation`)
- **Refonte** : hérite du rethème (typo, cartes, arrondis). Reco future :
  sommaire sticky + ancre active.

---

## 4. Composants du design system (`frontend/src/components/ui/`)

| Composant | Rôle |
|---|---|
| `Button` | 5 variantes (primary, secondary, outline, ghost, danger), 3 tailles, état loading, icône Material Symbols |
| `Card` / `CardHeader` | Panneau 12 px + liseré, variante interactive (hover) |
| `Badge` | 6 tonalités sémantiques, pastille d'état optionnelle |
| `Stat` | Tuile KPI : overline + valeur tabulaire + tendance |
| `EmptyState` | Icône + titre + description + action — remplace les textes italiques |
| `PageHeader` | Kicker + titre + description + actions : hiérarchie unique |
| `cn` | Utilitaire de composition de classes |

Composants transverses déjà en place et restylés : Sidebar (groupée),
Header (breadcrumb + état services), Tooltip, Skeleton (shimmer neutre),
Toaster (sonner), ConfirmDialog, TaskCenter.

---

## 5. Navigation repensée

Avant : 9 entrées plates. Après : regroupement par **étape du parcours**
(défini dans `navigation.ts`, groupes traduits FR/EN) :

```
Dashboard
Données          Ingestion · Documents
Modèles          Model Hub · Fine-Tuning
Test & évaluation  Playground · Comparison · Optimization
─────────────────
+ Nouveau modèle (CTA principal)
Docs
```

Justification : le produit raconte « documents → modèle → preuve du gain » ;
la sidebar raconte désormais la même histoire. Le CTA « Nouveau modèle »
reste accessible en permanence (action principale à 1 clic), le breadcrumb
du header situe l'utilisateur, et le « ? » contextuel ouvre la bonne section
de documentation.

---

## 6. Roadmap priorisée

### ✅ Fait dans cette itération (fondations)
- Rethème intégral par tokens + overrides (toutes pages).
- Shell réécrit (sidebar groupée, header, tooltips, toasts, loader).
- Kit `ui/` livré ; palette dataviz validée daltonisme ; i18n nettoyée
  (titres en capitales) ; pires états vides corrigés.

### Quick wins (½ à 1 jour chacun)
1. Adopter `EmptyState` + `Badge` dans Documents, Comparison, Model Hub.
2. Remplacer les en-têtes de page ad hoc par `PageHeader` (8 pages).
3. Uniformiser tous les boutons des pages sur `Button` (l'audit des
   variantes est déjà fait : primary/ghost/danger).
4. Toasts de confirmation après actions destructives (suppression corpus,
   clear chat) avec bouton « Annuler » (undo 5 s) quand c'est possible.

### Améliorations moyennes (1–3 jours)
1. **Documents** : table standard du design system (densité, tri visible,
   sélection multiple, pagination unifiée).
2. **Formulaires** : composants `Field`/`Input`/`Select` avec label,
   description et erreur inline (react-hook-form + zod déjà présents).
3. **Recherche globale** (`⌘K`) : palette de commandes naviguant vers pages,
   documents et modèles — le pattern Linear/Raycast par excellence.
4. Raccourcis clavier documentés (envoyer : `Enter`, nouveau job : `n`…).

### Refonte majeure (1–2 semaines)
1. **Mode clair** : les tokens le permettent désormais (dupliquer le bloc
   `@theme` sous `[data-theme="light"]`, re-valider la palette dataviz en
   `--mode light`).
2. **Onboarding progressif** : fusion de WizardProgress et « Getting
   started » en un vrai parcours guidé désactivable.
3. **Densité adaptative** : préférence confort/compact persistée.
4. Migration TypeScript stricte des pages (suppression des 71 `any`).

---

## 7. Recommandations « perception de qualité »

1. **La retenue est le luxe** : un seul accent par écran ; si tout est
   coloré, rien n'est important.
2. **Microcopie** : phrases courtes, sentence case, voix active, toujours
   orientées action (« Ingest documents to get started », pas « EMPTY »).
3. **Stabilité visuelle** : chiffres tabulaires, skeletons aux dimensions
   exactes du contenu final, pas de layout shift au polling.
4. **États d'abord** : chaque vue se conçoit dans ses 4 états (loading /
   vide / erreur / succès) — le kit fournit les 4.
5. **Cohérence des arrondis** : 6/8/12 px selon la taille de l'objet, jamais
   d'exception.
6. **Accessibilité comme signal de gamme** : contrastes AA mesurés, focus
   visible, cibles ≥ 32 px, `aria-label` sur les boutons-icônes — déjà en
   place, à maintenir en revue de PR.
