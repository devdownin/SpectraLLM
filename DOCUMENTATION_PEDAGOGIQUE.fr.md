# 📚 Spectra — Le Mini‑Livre des Idées et des Algorithmes

> *Du document brut à l'expertise métier souveraine.*
>
> Ce document est un **mini‑livre pédagogique**. Il explique, pour chaque brique
> de Spectra, **trois choses** : l'**intuition** (le problème résolu),
> l'**algorithme** (comment ça marche, formules et pseudo‑code à l'appui), et
> un **exemple d'usage concret**. Aucune connaissance préalable en IA n'est
> requise pour la première lecture ; les encadrés ⚙️ s'adressent aux lecteurs
> qui veulent le détail algorithmique.

**Conventions de lecture**
- 💡 **Intuition** : l'idée en langage courant.
- ⚙️ **Algorithme** : le fonctionnement précis (formules, pseudo‑code, compromis).
- 🎯 **Exemple d'usage** : un cas réel, avec entrée et résultat attendu.

---

## Sommaire

0. [Préambule : pourquoi Spectra existe](#0)
1. [Représenter le sens : les embeddings](#1)
2. [Stocker et chercher vite : ChromaDB, HNSW, cosinus](#2)
3. [L'ingestion : du fichier brut au savoir indexé](#3)
4. [La recherche hybride : vecteurs + mots‑clés + fusion](#4)
5. [Le re‑ranking : le contrôle qualité final](#5)
6. [Les six visages du RAG](#6)
7. [Optimiser le contexte : compression, dédup, long‑contexte](#7)
8. [Fabriquer un jeu de données d'entraînement](#8)
9. [Apprendre en permanence : QLoRA et la boucle de feedback](#9)
10. [Mesurer la qualité : LLM‑juge, métriques, benchmark](#10)
11. [L'auto‑réglage matériel](#11)
12. [Tenir en production : résilience et concurrence](#12)
13. [Déployer : de Docker à GKE](#13)
14. [Comparer les algorithmes : forces, faiblesses et lecture des résultats](#14)
15. [Glossaire et pour aller plus loin](#15)

---

<a name="0"></a>
## 0. Préambule : pourquoi Spectra existe

Un assistant généraliste (type ChatGPT) connaît « le monde », mais pas **votre**
monde : vos procédures internes, votre jargon, vos références produit. Spectra est
un **constructeur de LLM de domaine** : il transforme votre documentation en un
expert IA qui tourne **100 % en local**, sans qu'aucune donnée ne sorte de votre
infrastructure.

Spectra combine deux mécanismes complémentaires, qui forment une **boucle
vertueuse** :

```
        ┌─────────────────────────── RAG ───────────────────────────┐
        │ L'IA LIT vos documents à la volée pour répondre            │
        ▼                                                            │
   vos documents ──► dataset Q&A/préférences ──► fine‑tuning ──► modèle amélioré
        ▲                                                            │
        └────────────────── Fine‑tuning : l'IA APPREND ─────────────┘
```

- **RAG (Retrieval‑Augmented Generation)** : répondre *maintenant* en allant
  chercher les bons extraits. Aucun ré‑entraînement nécessaire ; la connaissance
  reste externe et modifiable instantanément.
- **Fine‑tuning** : faire *entrer* durablement le savoir et le style dans les
  poids du modèle. Plus lent à produire, mais le modèle devient plus rapide,
  parle votre langue, et peut répondre même sans base documentaire.

> **L'idée maîtresse** : Spectra utilise le RAG pour *générer* un jeu de données
> de haute qualité, puis ce jeu de données pour *affiner* le modèle, puis le
> modèle affiné pour mieux faire du RAG. Le savoir se **compose** dans le temps.

🎯 **Exemple d'usage.** Une PME industrielle dépose 300 fiches techniques PDF.
Dès l'ingestion, ses techniciens posent des questions (« quelle est la pression
de service de la vanne X‑42 ? ») → réponses sourcées immédiatement (RAG). En
parallèle, Spectra fabrique un dataset à partir de ces fiches et affine le
modèle pendant la nuit : le lendemain, les réponses sont plus rapides et le
modèle maîtrise le vocabulaire maison.

---

<a name="1"></a>
## 1. Représenter le sens : les embeddings

💡 **Intuition.** Un ordinateur ne « comprend » pas le texte ; il compare des
nombres. Un **embedding** traduit un morceau de texte en une liste de nombres
(un **vecteur**) telle que *deux textes de sens proche aient des vecteurs
proches*. « Le moteur surchauffe » et « l'unité motrice monte en température »
finissent côte à côte, même sans mot commun.

⚙️ **Algorithme.**
- Un modèle d'embedding (ici de la famille *Nomic Embed*) projette chaque texte
  dans un espace à *d* dimensions (souvent quelques centaines).
- Les vecteurs sont **normalisés** (longueur ramenée à 1). C'est important : sur
  des vecteurs normalisés, comparer par **angle** (cosinus) revient à comparer
  par proximité, et les scores deviennent interprétables dans `[0, 1]`.
- **Similarité cosinus** entre deux vecteurs *a* et *b* :

  ```
  cos(a, b) = (a · b) / (‖a‖ · ‖b‖)
            = somme(aᵢ·bᵢ)            (si a et b sont normalisés)
  ```
  1 = sens identique, 0 = sans rapport, négatif = opposé.

- On distingue **deux usages** d'embedding dans Spectra :
  - *embedding de passage* : pour indexer chaque chunk de document ;
  - *embedding de requête* : pour la question de l'utilisateur.
  Les deux vivent dans le même espace, donc on peut les comparer directement.

🎯 **Exemple d'usage.** Vous cherchez « congés payés ». Un moteur classique
raterait un paragraphe intitulé « absences rémunérées annuelles ». Avec les
embeddings, les deux vecteurs sont quasi colinéaires → le paragraphe remonte,
synonymie comprise.

---

<a name="2"></a>
## 2. Stocker et chercher vite : ChromaDB, HNSW, cosinus

💡 **Intuition.** Si vous avez 1 million de chunks, comparer la question à
*chacun* (recherche exhaustive) est trop lent. Il faut un **index** qui trouve
les « plus proches voisins » presque instantanément, quitte à accepter une
infime imprécision. C'est le rôle de **ChromaDB** (la base vectorielle) et de
son index **HNSW**.

⚙️ **Algorithme — HNSW (Hierarchical Navigable Small World).**
HNSW construit un **graphe en plusieurs étages**. En haut, peu de nœuds reliés
par de longues « autoroutes » ; en bas, tous les nœuds reliés à leurs voisins
proches par des « routes locales ».

```
Recherche du plus proche voisin d'une requête q :
  entrer au sommet, sur un nœud d'entrée
  pour chaque étage, du haut vers le bas :
      avancer de voisin en voisin vers le nœud le plus proche de q
      (descendre d'un étage quand on ne peut plus se rapprocher)
  à l'étage 0, explorer le voisinage avec une file de taille ef_search
  renvoyer les meilleurs candidats trouvés
```

Trois paramètres gouvernent le compromis **vitesse ↔ précision** :
- `M` : nombre de voisins par nœud (densité du graphe).
- `ef_construction` : effort à la **construction** (qualité de l'index).
- `ef_search` : effort à la **requête** (rappel). Plus il est grand, plus on
  trouve les vrais voisins… plus c'est lent.

**Choix de Spectra à la création d'une collection :**
- **espace = cosinus** (et non la distance L2 par défaut) : scores `[0,1]`
  interprétables, cohérents avec les vecteurs normalisés ;
- `ef_search` relevé (≈ 100) pour un **meilleur rappel** — un re‑ranking
  affinera ensuite ;
- `ef_construction` relevé (≈ 200) pour un index plus précis.

> ⚠️ **Point subtil.** L'espace de distance est **figé à la création** de la
> collection. Changer de métrique impose de **ré‑indexer** (ré‑ingérer) les
> documents.

🎯 **Exemple d'usage.** Base de 200 000 chunks. Une recherche exhaustive
prendrait des centaines de millisecondes par requête ; HNSW renvoie le top‑k en
quelques millisecondes, avec un rappel > 95 %. Si vous constatez des oublis sur
un corpus difficile, augmenter `ef_search` récupère les voisins manquants au
prix d'un peu de latence.

---

<a name="3"></a>
## 3. L'ingestion : du fichier brut au savoir indexé

L'ingestion « digère » vos fichiers en cinq temps : extraction → nettoyage →
découpage → vectorisation → indexation.

### A. Extraction « consciente de la mise en page »
💡 Un PDF est un nuage de caractères positionnés ; une extraction naïve mélange
les colonnes et casse les tableaux. Spectra convertit chaque document (PDF, DOCX,
HTML, JSON, XML, Avro, TXT, ZIP…) en **Markdown structuré** (titres, listes,
tableaux). Un LLM lit un tableau Markdown bien mieux qu'une bouillie de mots.

### B. Nettoyage du texte
⚙️ Une chaîne d'étapes idempotentes : normalisation Unicode, suppression des
en‑têtes/pieds de page récurrents, correction des **ligatures OCR** (`ﬀ` → `ff`),
nettoyage des bordures ASCII de tableaux, compression des espaces, etc. Objectif :
réduire le **bruit** qui dégrade et les embeddings et la génération.

### C. Chunking sémantique (découpage)
💡 Trop gros, un chunk dilue le sens ; trop petit, il perd le contexte. Spectra
vise ~**512 tokens** (~2000 caractères) par chunk.

⚙️ **Fenêtre glissante avec chevauchement.**
```
taille_chunk = 512 tokens ; chevauchement = 64 tokens
début = 0
tant que début < longueur(document) :
    fin = début + taille_chunk
    émettre document[début : fin]
    début = fin - chevauchement     # on recule de 64 → continuité
```
Le chevauchement évite qu'une phrase coupée en fin de chunk perde son sens : elle
réapparaît entière au début du suivant.

### D. Embeddings par lots
⚙️ Les chunks sont vectorisés **par lots** en un seul appel au serveur
d'embedding (au lieu de N appels séquentiels), et le serveur traite plusieurs
requêtes **en parallèle** (plusieurs « slots »). Sur un gros document, c'est ce
qui fait passer l'indexation de minutes à secondes.

### E. Indexation
Les couples (texte, vecteur, métadonnées : fichier source, page…) sont stockés
dans ChromaDB. Les métadonnées permettront le **filtrage** (« seulement le
manuel v3 ») et la **suppression ciblée** (« retire ce fichier »).

🎯 **Exemple d'usage.** Vous glissez un ZIP de 80 fichiers mêlant PDF, DOCX et
JSON. Spectra extrait chacun en Markdown, nettoie, découpe en ~3 000 chunks,
vectorise par lots et indexe — avec une **barre de progression incrémentale**
(nombre de chunks au fil de l'eau). Vous pouvez interroger dès les premiers
chunks indexés.

---

<a name="4"></a>
## 4. La recherche hybride : vecteurs + mots‑clés + fusion

💡 **Intuition.** Le vectoriel excelle sur le **sens** mais peut « diluer » un
terme exact (un numéro de série, un acronyme). La recherche par **mots‑clés**
excelle sur l'exact mais ignore les synonymes. Spectra **combine les deux**.

### A. Recherche vectorielle
Top‑k des chunks dont le vecteur est le plus proche (cosinus) de la question.

### B. Recherche lexicale BM25
⚙️ **BM25** classe les documents selon la fréquence des mots de la requête,
pondérée par leur rareté et la longueur du document.
```
score(D, Q) = Σ_terme∈Q  IDF(terme) · ( f · (k₁+1) )
                                       ───────────────────────────────
                                       f + k₁·(1 − b + b·|D|/avgdl)
  f      = fréquence du terme dans D
  |D|    = longueur de D ; avgdl = longueur moyenne des documents
  IDF    = rareté du terme dans le corpus (un mot rare pèse plus)
  k₁, b  = réglages (saturation de fréquence ; influence de la longueur)
```
Intuitivement : un mot **rare** présent **plusieurs fois** dans un document
**court** est un très bon signal. Spectra maintient pour cela un index plein‑texte
(FTS) reconstruit à partir des chunks indexés.

### C. Fusion RRF (Reciprocal Rank Fusion)
💡 Comment réconcilier deux classements (vectoriel et BM25) qui n'ont pas la même
échelle de score ? On ne fusionne pas les *scores*, on fusionne les **rangs**.

⚙️
```
RRF(d) = 1/(k + rang_vectoriel(d)) + 1/(k + rang_bm25(d))      avec k ≈ 60
```
Un document **bien classé dans les deux** listes obtient le meilleur score total.
`k` amortit l'influence des tout premiers rangs (robustesse).

🎯 **Exemple d'usage.** Question : « tolérance du roulement 6204‑ZZ ». Le
vectoriel ramène des passages sur les roulements en général ; BM25 verrouille la
référence exacte « 6204‑ZZ ». RRF place en tête le chunk qui parle **du bon
roulement** *et* **de tolérance** — le meilleur des deux mondes.

---

<a name="5"></a>
## 5. Le re‑ranking : le contrôle qualité final

💡 **Intuition.** Les étapes précédentes sont **rapides mais approximatives** :
elles comparent des vecteurs calculés *séparément* pour la question et pour le
chunk (un « bi‑encodeur »). Pour trancher finement, on relit chaque paire
(question, chunk) **ensemble**.

⚙️ **Cross‑encodeur.** Contrairement au bi‑encodeur, le cross‑encodeur prend en
entrée **la concaténation** (question + chunk) et produit un **score de
pertinence** unique. Bien plus précis, mais bien plus coûteux → on ne l'applique
qu'à une **présélection**.

```
candidats = recherche_hybride(question, n = 20)     # rapide
pour chaque c dans candidats :
    score[c] = cross_encodeur(question, c.texte)     # précis, coûteux
garder les 5 meilleurs par score décroissant
```

Ce schéma **20 → 5** (retrieve‑then‑rerank) est le compromis classique :
le rappel large attrape les bons candidats, le re‑ranking assure la précision.

🎯 **Exemple d'usage.** Parmi 20 candidats, trois mentionnent « garantie » mais
un seul concerne **votre** produit et **votre** pays. Le cross‑encodeur lui donne
le meilleur score ; les 5 retenus alimentent une réponse précise et non diluée.

---

<a name="6"></a>
## 6. Les six visages du RAG

Toutes les questions ne se valent pas. Spectra implémente plusieurs **stratégies
de RAG** ; on peut les voir comme une gamme allant du plus simple/rapide au plus
réfléchi/coûteux.

### 6.1 RAG standard
```
contexte = top‑5(rerank(recherche_hybride(question)))
réponse  = LLM("À partir UNIQUEMENT de : {contexte}, réponds à : {question}")
```
Rapide, suffisant pour une question factuelle bien posée.

### 6.2 Multi‑Query RAG
💡 Une seule formulation peut rater de bons passages. On en génère plusieurs.
```
variantes = LLM("Reformule la question de 3 façons différentes")
résultats = ⋃  recherche_hybride(v)  pour v ∈ {question} ∪ variantes
contexte  = rerank(dédupliquer(résultats))
```
🎯 *Usage* : questions vagues ou polysémiques (« problème de démarrage » →
« ne démarre pas », « refus de démarrage à froid », « erreur au boot »).

### 6.3 Agentic RAG (boucle ReAct)
💡 Pour les questions **multi‑étapes**, l'IA alterne **raisonnement** et
**action** (recherche), jusqu'à avoir de quoi répondre.
```
répéter (jusqu'à N tours) :
    Pensée    : que me manque‑t‑il ?
    Action    : rechercher(requête affinée)
    Observation : lire les résultats
    si assez d'information : sortir
Réponse finale : synthétiser
```
🎯 *Usage* : « Compare la procédure de maintenance de la pompe A et de la pompe B
et dis laquelle exige le plus d'outillage. » → l'agent cherche A, puis B, puis
compare.

### 6.4 Adaptive RAG
💡 Pourquoi payer le coût d'un agent pour « quelle est la capacité du réservoir ? »
L'**Adaptive RAG** classe d'abord la question (simple / complexe) et **route**
vers la stratégie adéquate.
```
type = classifier(question)
selon type :
    simple   → RAG standard
    complexe → Agentic / Multi‑Query
```
🎯 *Usage* : un même point d'entrée sert aussi bien les questions courtes que les
investigations, sans gaspiller de calcul.

### 6.5 Corrective RAG (CRAG)
💡 Et si les documents trouvés sont **mauvais** ? CRAG **évalue** la pertinence
des passages récupérés et **corrige** la trajectoire.
```
docs = recherche(question)
qualité = évaluer_pertinence(question, docs)
selon qualité :
    bonne     → répondre avec docs
    douteuse  → reformuler / élargir la recherche, puis répondre
    mauvaise  → signaler le manque d'information plutôt qu'inventer
```
🎯 *Usage* : réduit les hallucinations quand le corpus ne couvre pas la question
— Spectra préfère dire « non documenté » que broder.

### 6.6 Self‑RAG
💡 Le modèle **s'auto‑critique** : a‑t‑il besoin de chercher ? sa réponse est‑elle
**étayée** par les sources ?
```
si besoin_de_recherche(question) :
    docs = recherche(question)
ébauche = LLM(question, docs)
si non soutenue_par(ébauche, docs) :   # auto‑vérification
    réviser ou re‑chercher
```
🎯 *Usage* : questions sensibles où l'on exige que chaque affirmation soit
**traçable** à une source.

### Récapitulatif
| Stratégie | Coût | Idéale pour |
|-----------|------|-------------|
| Standard | 💲 | factuel simple |
| Multi‑Query | 💲💲 | question vague |
| Agentic/ReAct | 💲💲💲 | multi‑étapes, comparaison |
| Adaptive | 💲→💲💲💲 | trafic mixte (route automatiquement) |
| Corrective | 💲💲 | corpus incomplet, anti‑hallucination |
| Self‑RAG | 💲💲 | exigence de traçabilité |

---

<a name="7"></a>
## 7. Optimiser le contexte : compression, dédup, long‑contexte

La fenêtre de contexte du LLM est limitée et **coûteuse**. Trois techniques la
préservent.

### A. Compression de contexte
⚙️ Avant de répondre, on demande au LLM d'**extraire de chaque chunk uniquement
les phrases utiles** à la question. On garde le signal, on jette le bruit.

### B. Déduplication sémantique (Jaccard)
💡 Documents versionnés = passages quasi identiques répétés. Inutile de les
empiler.
⚙️ **Indice de Jaccard** sur les ensembles de mots :
```
Jaccard(A, B) = |A ∩ B| / |A ∪ B|     ∈ [0, 1]
si Jaccard(chunk_i, chunk_j) > seuil (≈ 0,85) : éliminer le doublon
```

### C. Bypass « long‑contexte »
💡 Si le corpus pertinent **tient entièrement** dans la fenêtre du modèle,
pourquoi découper et chercher ? On peut alors fournir tout le contexte d'un coup.
⚙️ Règle : `si tokens(contexte_pertinent) ≤ fenêtre_modèle − marge → tout passer`.

🎯 **Exemple d'usage.** Un contrat de 6 pages versionné trois fois : la dédup
Jaccard supprime les clauses répétées, la compression ne garde que les articles
liés à la question, et si le tout tient dans la fenêtre, Spectra l'envoie
intégralement pour une réponse exhaustive.

---

<a name="8"></a>
## 8. Fabriquer un jeu de données d'entraînement

Pour affiner le modèle, il faut des exemples. Spectra **s'auto‑génère** ce jeu de
données à partir de vos chunks.

### A. Paires Question/Réponse
⚙️ Pour chaque chunk : `LLM("Rédige des questions dont la réponse est dans ce
texte, puis réponds‑y")`. On obtient des couples (question, réponse) ancrés dans
**vos** documents. Spectra produit aussi, par chunk, une paire **résumé** et une
paire **classification** (catégorie du texte).

⚙️ **Score de confiance ancré (et non une simple longueur).** Chaque paire reçoit
un score qui sert ensuite à filtrer (`minConfidence`). Plutôt que de se fier à la
seule longueur de la réponse, on mesure l'**ancrage** : la part des mots de contenu
de la réponse réellement présents dans le chunk source.
```
ancrage = |mots_contenu(réponse) ∩ mots(chunk)| / |mots_contenu(réponse)|
confiance = 0,6 + 0,4 · ancrage        (réponse bien formée)
```
🧠 **Pourquoi ce choix ?** Une réponse dont tous les mots proviennent de la source
est *peu susceptible d'être inventée*. Une réponse bien formée mais peu ancrée
(potentielle hallucination) plafonne vers 0,6 et passe **sous** les seuils stricts
des recettes (0,85 / 0,9) : on n'entraîne le modèle que sur des exemples fiables.

### B. Apprendre à dire « je ne sais pas » (exemples négatifs)
💡 La cause n°1 d'hallucination dans un assistant RAG : répondre alors que
l'information **n'est pas** dans les documents. On l'attaque à la source en
**entraînant l'abstention**.

⚙️ Périodiquement (1 chunk sur N, réglable via `spectra.dataset.refusal-every-n`),
on demande au LLM une question plausible du domaine **dont la réponse est absente**
du chunk, et on l'associe à une réponse de **refus** variée (« Je ne dispose pas de
cette information dans les documents fournis. »). Catégorie `negative`, type `refusal`.

🧠 **Pourquoi c'est plus efficace que de corriger après coup ?** Le comportement
d'abstention devient une *propriété apprise* du modèle, pas un garde‑fou fragile
ajouté au prompt. C'est complémentaire du refus déjà demandé par le prompt RAG.

⚙️ **Déduplication.** Avant persistance, les paires identiques (même instruction +
réponse) sont supprimées : un contenu répété ne doit pas être sur‑pondéré pendant
l'entraînement.

### C. Paires de préférence (DPO / ORPO)
💡 Le **DPO (Direct Preference Optimization)** apprend au modèle ce qui est
**préférable**. Pour chaque exemple on fournit une **bonne** réponse (*chosen*)
et une **moins bonne** (*rejected*, p. ex. une hallucination plausible). Le
modèle apprend à rapprocher ses sorties des *chosen* et à s'éloigner des
*rejected*. Ces mêmes triplets `{prompt, chosen, rejected}` alimentent aussi
l'**ORPO** (cf. § 9.D).

⚙️ **Garde de qualité Jaccard.** Une paire n'est instructive que si *chosen* et
*rejected* **diffèrent vraiment** :
```
si Jaccard(chosen, rejected) > 0,85 : rejeter la paire (trop similaires)
```
Sinon, le « signal de préférence » est nul et bruite l'entraînement.

🎯 **Exemple d'usage.** À partir d'une fiche sécurité, Spectra génère :
*chosen* = « Porter des gants nitrile et des lunettes » ; *rejected* = « Aucun
EPI requis ». La différence est nette (Jaccard faible) → paire conservée, le
modèle apprend à ne pas minimiser les consignes.

### D. Fine‑tuning ou RAG ? Le bon outil par type de connaissance
💡 Le fine‑tuning **encode mal les faits** et **vieillit** : un événement trafic ou
une nomenclature qui change ne doit pas être « gravé » dans les poids. À l'inverse,
le RAG va chercher le fait à jour à chaque requête.

🧠 **Règle de partage adoptée par Spectra :**
| Type de connaissance | Outil recommandé |
|---|---|
| Faits volatils (événements, nomenclatures qui évoluent) | **RAG** |
| Style, ton, format, procédures stables, comportement (abstention) | **Fine‑tuning (SFT)** |
| Préférences / réduction d'hallucination | **DPO / ORPO** |

⚙️ Levier `spectra.fine-tuning.sft-excluded-categories` : exclut du jeu de
fine‑tuning les catégories/types jugés volatils (ex. `evenements,nomenclatures`),
laissés au RAG. Vide par défaut.

---

<a name="9"></a>
## 9. Apprendre en permanence : QLoRA et la boucle de feedback

### A. QLoRA / LoRA — affiner sans tout réécrire
💡 Ré‑entraîner les milliards de poids d'un LLM est lent et gourmand en VRAM.
**LoRA** ajoute de **petites matrices** entraînables à côté des poids gelés ;
**QLoRA** y ajoute la **quantization** (poids de base en 4 bits) pour tenir sur
un GPU modeste.

⚙️ **Idée de LoRA.** Au lieu de mettre à jour une grande matrice de poids `W`,
on apprend une correction de **faible rang** `B·A` (avec `A`, `B` petites) :
```
W_effectif = W_gelé + (α/r) · B·A
  r = rang (petit, ex. 8–64) → très peu de paramètres à entraîner
  α = facteur d'échelle
```
On n'entraîne que `A` et `B` : **2× plus rapide**, **bien moins de VRAM**, et on
peut **empiler/retirer** ces modules comme des « cartouches » de savoir.

⚙️ **Quels modules cibler ? Auto‑détection.** Les `target_modules` étaient codés en
dur en style Llama (`q_proj/k_proj/v_proj/o_proj`). Problème : Phi‑3 fusionne
l'attention en `qkv_proj` → LoRA aurait échoué. Spectra **inspecte l'architecture
réelle** et cible les projections présentes ; `--lora-target all` ajoute les
projections MLP (`gate/up/down_proj`) pour plus de capacité sur les gros modèles.
🧠 **Pourquoi auto‑détecter ?** Un seul code marche pour TinyLlama, Mistral, Llama‑3
et Phi‑3, sans liste à maintenir par modèle.

### A bis. SFT : n'apprendre que la réponse (masquage du prompt)
💡 En supervision (SFT), on ne veut pas que le modèle apprenne à **régénérer la
question** ou le prompt système — seulement à **produire la bonne réponse**.

⚙️ **Algorithme de masquage.** On construit les `labels` token par token : tout ce
qui précède la réponse de l'assistant est masqué à `-100` (ignoré par la perte) ;
seuls les tokens de la réponse (EOS compris) sont supervisés.
```
[système][question]  → labels = -100  (non appris)
[réponse assistant + EOS] → labels = tokens  (appris)
```
🧠 **Pièges évités.** (1) Sans masquage, la perte couvre le prompt → le modèle se
disperse. (2) Comme `pad_token == eos_token`, un collator naïf masquait **tous les
EOS** → le modèle n'apprenait jamais à **s'arrêter**. On supervise donc explicitement
l'EOS de la réponse.

### A ter. Débit : packing et padding dynamique
⚙️ **Padding dynamique.** On ne rembourre plus chaque exemple à 512 tokens : le
collator rembourre au plus long **du batch**. À `batch_size=1`, il n'y a donc
**aucun** padding → calcul économisé, précieux sur CPU.
⚙️ **Multipacking (option).** On concatène plusieurs exemples courts en une séquence
de longueur `max` séparés par EOS, pour éliminer le padding résiduel (20‑40 % d'étapes
en moins sur des Q/R courtes).

### A quater. Réglages à faible coût
- **NEFTune** (`--neftune-alpha`, ex. 5) : un léger **bruit sur les embeddings** en
  SFT améliore souvent la robustesse… gratuitement.
- **Warmup en ratio** (`--warmup-ratio 0.03`) plutôt qu'un nombre d'étapes fixe :
  plus sain quand le dataset est petit.
- **Gradient checkpointing** activé **uniquement sur GPU** (échange calcul↔mémoire) :
  inutile et ralentissant sur CPU.
- **Split validation** (`--val-split`) : tient un échantillon à l'écart pour suivre
  l'`eval_loss` par époque et **détecter le sur‑apprentissage**.

### A quinquies. SFT, DPO ou ORPO ? (et pourquoi ORPO)
| Méthode | Ce qu'elle fait | Coût | Quand |
|---|---|---|---|
| **SFT** | imite les bonnes réponses | faible | format, ton, procédures, abstention |
| **DPO** | éloigne des *rejected*, après un SFT | moyen (modèle de référence) | corriger des préférences fines |
| **ORPO** | SFT **+** préférence en **une passe**, **sans** modèle de référence | faible | alternative simple/efficace au couple SFT→DPO |

🧠 **Pourquoi proposer ORPO ?** DPO nécessite un **modèle de référence** figé (mémoire
doublée) et suppose un bon SFT préalable. **ORPO** combine l'imitation et la pénalité
de préférence (odds‑ratio) en **une seule passe sans référence** : plus léger, souvent
meilleur sur petits modèles. Même dataset `{prompt, chosen, rejected}` que DPO.

### B. La boucle de feedback (👍/👎 → DPO/ORPO → ré‑entraînement)
```
utilisateur note une réponse (👍 / 👎)
   → 👍 devient un "chosen", 👎 un "rejected"
   → accumulation de paires DPO
quand le nombre de validations dépasse un seuil :
   → déclenchement AUTOMATIQUE d'un ré‑entraînement
   → nouveau modèle promu
```
Le modèle s'améliore **chaque jour** grâce à l'expertise de vos utilisateurs.

🎯 **Exemple d'usage.** Vos rédacteurs valident (👍) 50 commentaires d'articles
générés et en corrigent (👎) 10. Au seuil atteint, Spectra relance un fine‑tuning
QLoRA pendant la nuit ; le lendemain, le style des commentaires colle davantage à
votre charte éditoriale.

### C. Servir le modèle : fusion vs adaptateur à chaud
Deux façons de mettre l'adaptateur en production :
- **Fusion** (`export_gguf.py`) : on fond l'adaptateur dans le modèle de base puis on
  quantifie le tout → un GGUF autonome. Simple, mais **un fichier par modèle**.
- **Adaptateur à chaud** (`export_lora_gguf.py`) : on convertit *seulement* l'adaptateur
  (quelques Mo) en GGUF, chargé par `llama-server --lora-scaled adapter.gguf 1.0`
  **par‑dessus** le modèle de base. 🧠 **Avantage :** pas de duplication du modèle de
  base, et on **permute les adaptateurs à chaud** (endpoint `/lora-adapters`, scale 0→1)
  sans redémarrage ni re‑quantization.

⚠️ **Cohérence entraînement ↔ service.** Le modèle a appris à répondre sous une
**persona précise**. Le servir sous un autre system prompt **dégrade** le gain du
fine‑tuning. Spectra centralise donc la persona dans une source unique
(`AssistantPersona.SYSTEM_PROMPT`), réutilisée à l'entraînement, au service (RAG) et à
l'enregistrement du modèle.

---

<a name="10"></a>
## 10. Mesurer la qualité : LLM‑juge, métriques, benchmark

On n'améliore que ce qu'on mesure.

### A. LLM‑as‑a‑Judge
💡 Faire évaluer une réponse… par un LLM jouant l'**examinateur**.
⚙️
```
note, justification = LLM_juge(
    "Évalue de 1 à 10 la réponse suivante au regard de la question
     et des sources ; explique ta note.", question, réponse, sources)
```
On suit l'évolution de la **note moyenne** entre deux versions du modèle.

### A bis. Benchmark **tenu à l'écart** + taux d'hallucination
⚠️ **Piège évité.** Évaluer en échantillonnant le **dataset d'entraînement** est
trompeur : un modèle fine‑tuné a *déjà vu* ces exemples (fuite de données) → notes
artificiellement hautes. Spectra ajoute donc un **jeu de référence versionné, jamais
entraîné** (`benchmarks/highway_benchmark.jsonl`).

⚙️ **Deux mesures complémentaires** (`/api/quality-benchmark`) :
```
questions answerable   → note d'exactitude 1‑10 (vs réponse de référence)
questions non‑answerable (réponse absente du corpus)
        → le modèle DOIT s'abstenir
        → taux d'hallucination = part de réponses inventées au lieu d'un refus
```
🧠 **Pourquoi mesurer l'hallucination séparément ?** C'est l'indicateur direct de
fiabilité d'un assistant RAG ; il valide l'effet des exemples de refus (§ 8.B) et de
l'alignement DPO/ORPO.

⚙️ **Comparaison base vs fine‑tuné** (`/api/quality-benchmark/compare`) : on rejoue le
benchmark sur les deux modèles pour **chiffrer** l'apport du fine‑tuning (exactitude **et**
hallucination, avant/après).

### B. Métriques de personnalisation
Tableau de bord : volume de documents ingérés, taille du dataset, paires DPO
accumulées, taux 👍/👎, nombre de ré‑entraînements… → de quoi piloter la
spécialisation dans le temps.

### C. Benchmark
Mesures de **latence** (temps de première réponse, débit de tokens) et de
**qualité** sur un jeu de questions de référence, pour comparer des
configurations (taille de contexte, parallélisme, GPU…).

🎯 **Exemple d'usage.** Avant/après un fine‑tuning : la note LLM‑juge passe de
6,8 à 8,1 sur 50 questions métier, et la latence reste stable. Décision : promouvoir
le nouveau modèle.

---

<a name="11"></a>
## 11. L'auto‑réglage matériel

💡 **Intuition.** Le même conteneur d'inférence doit tourner sur un portable sans
GPU comme sur un serveur multi‑GPU. Plutôt que d'imposer des réglages, Spectra
**détecte** les ressources au démarrage et **calcule** des paramètres optimaux.

⚙️ **Algorithme (au lancement du serveur d'inférence).**
```
détecter CPU (nb cœurs), RAM disponible (en tenant compte des limites cgroups),
         GPU (NVIDIA via nvidia-smi / AMD ROCm / Vulkan) et sa VRAM

threads        = f(nb_cœurs, mode)        # on laisse de la marge à l'OS
n_gpu_layers   = toutes les couches si GPU détecté, sinon 0 (CPU)
contexte       = g(VRAM ou RAM)           # plus de mémoire → fenêtre plus grande
batch          = h(mémoire)               # gros batch si mémoire abondante
flash_attn, KV‑cache quantisé, parallélisme = réglages par défaut sûrs

lancer le serveur avec ces paramètres
```
Chaque réglage auto‑calculé reste **surchargeable** par variable d'environnement.

🎯 **Exemple d'usage.** Sur un poste CPU 8 cœurs/16 Go : contexte modéré, 0 couche
GPU, batch moyen. Sur un serveur avec un GPU 24 Go : toutes les couches sur GPU,
grande fenêtre de contexte, gros batch — **sans changer un seul fichier de
configuration**.

---

<a name="12"></a>
## 12. Tenir en production : résilience et concurrence

Un assistant qui s'effondre au premier hoquet réseau n'a aucune valeur. Spectra
applique des **patterns de fiabilité** éprouvés.

- **Disjoncteur (circuit breaker).** Si un service dépendant (base vectorielle,
  serveur LLM) tombe, on **ouvre le circuit** : les appels échouent vite et
  proprement (réponse de repli) au lieu de s'empiler et de tout figer. Le circuit
  se referme quand le service récupère.
- **Réessais avec recul (retry/backoff).** Pour les erreurs **transitoires**, on
  réessaie quelques fois en espaçant : `2 s, 4 s, 8 s…`.
- **Threads virtuels.** Le service passe son temps à **attendre** des appels
  réseau (LLM, base). Les **threads virtuels** permettent des dizaines de
  milliers d'attentes concurrentes pour un coût minime, **avec une limite de
  concurrence** pour éviter de saturer la mémoire et les dépendances.
- **Sondes de santé (probes).** Démarrage / vivacité / disponibilité distinctes :
  on laisse le temps au modèle de charger (démarrage long) sans tuer le service,
  et on ne route le trafic que lorsqu'il est *prêt*.
- **Sauvegardes & réconciliation.** Sauvegardes périodiques de la base et
  vérification de **cohérence** (le registre des modèles correspond‑il bien au
  modèle réellement chargé ? la base FTS reflète‑t‑elle l'index vectoriel ?).

🎯 **Exemple d'usage.** Le serveur d'embedding redémarre en pleine ingestion. Le
disjoncteur s'ouvre, les lots en cours échouent proprement, les réessais
reprennent une fois le service revenu — sans perdre les chunks déjà indexés, et
sans bloquer les utilisateurs qui posent des questions sur le reste du corpus.

---

<a name="13"></a>
## 13. Déployer : de Docker à GKE

💡 **Intuition.** Spectra est un **ensemble de services** (API, interface, base
vectorielle, serveurs d'inférence chat et embedding, navigateur sans tête…). On
veut les lancer ensemble, de façon reproductible, du poste de dev au cloud.

- **Docker Compose** : pour un poste unique. Une commande lève toute la pile ;
  une variante active le **GPU**.
- **Kubernetes** : pour un cluster. Chaque service devient un *Deployment* ; seuls
  l'interface (et l'API derrière) sont exposés, le reste reste interne. Des
  *volumes persistants* conservent données et modèles.
- **GKE en continu** : un workflow d'intégration construit les images, les pousse
  dans un registre, puis applique les manifestes au cluster — **à chaque
  livraison**. L'authentification se fait **sans clé** (fédération d'identité
  OIDC). Une variante **GPU** (image CUDA + supplément de manifeste) active
  l'accélération pour l'inférence de chat.

```
git push (main) ──► CI : build images ──► registre ──► déploiement cluster
                         (auth OIDC, sans clé JSON stockée)
```

🎯 **Exemple d'usage.** Vous fusionnez une amélioration du prompt RAG. La CI
reconstruit l'image de l'API, la pousse, met à jour le déploiement et attend le
*rollout* : la nouvelle version est en ligne sans intervention manuelle. Si vous
avez un nœud GPU, l'overlay dédié bascule l'inférence chat sur GPU.

---

<a name="14"></a>
## 14. Comparer les algorithmes : forces, faiblesses et lecture des résultats

Les chapitres précédents ont présenté chaque brique **isolément**. Mais dans la
vraie vie, on ne choisit pas « le » bon algorithme dans l'absolu : on arbitre,
pour **un besoin donné**, entre des familles concurrentes. Ce chapitre fait la
**synthèse comparative** que les autres laissaient implicite. Il répond à trois
questions pratiques :

1. **Quand** privilégier tel algorithme plutôt qu'un autre ? (forces / faiblesses)
2. **Que coûte** ce choix ? (latence, mémoire, complexité)
3. **Comment lire** les chiffres pour décider, sans se faire piéger ?

> 🧭 **Fil rouge.** Presque tous les arbitrages de Spectra sont une variante du
> même compromis : **rappel ↔ précision ↔ coût**. Élargir la recherche augmente
> le rappel mais fait entrer du bruit ; affiner augmente la précision mais coûte
> du temps ou de la mémoire. Garder ce triangle en tête suffit à comprendre 90 %
> des décisions ci‑dessous.

---

### 14.1 Comparer les moteurs de recherche (retrieval)

Quatre approches se superposent dans Spectra. Elles ne sont **pas
interchangeables** : chacune rattrape une faiblesse de la précédente.

| Approche | Force principale | Faiblesse principale | Coût | Échelle de score |
|---|---|---|---|---|
| **Vectoriel (cosinus)** | comprend le **sens**, gère synonymes et paraphrases | « dilue » les termes exacts (réf., acronymes, chiffres) ; dépend de la qualité de l'embedding | moyen (index HNSW) | `[0,1]` continu |
| **Lexical (BM25)** | verrouille le **mot exact**, rare et discriminant ; explicable | aveugle aux synonymes ; sensible à l'orthographe | faible | non borné, dépend du corpus |
| **Hybride + RRF** | cumule sens **et** exactitude ; robuste | ne crée pas d'information : si les deux ratent, RRF rate aussi | faible (fusion de rangs) | rang, pas score |
| **+ Cross‑encodeur (rerank)** | **précision** maximale sur le top‑k | coûteux ; ne s'applique qu'à une présélection (ne « rattrape » pas un bon doc absent du top‑n) | élevé | score de paire |

💡 **Comment les lire ensemble.** Le pipeline n'est pas « choisir l'un », mais
**enchaîner** : vectoriel + BM25 ratissent large (haut **rappel**), RRF
réconcilie, le cross‑encodeur trie fin (haute **précision**). Chaque étage
corrige le défaut du précédent.

⚙️ **Le piège à comprendre : on ne peut pas re‑ranker ce qu'on n'a pas
récupéré.** Le cross‑encodeur ne voit que les `n` candidats (ex. 20) que la
recherche hybride lui passe. Si le bon chunk est au rang 35, **aucun** re‑ranking
ne le fera remonter. Conséquence pratique :

```
si une réponse correcte existe mais n'apparaît jamais → problème de RAPPEL
    → augmenter n (présélection), ef_search, ou activer BM25
si le bon chunk est présent mais mal classé → problème de PRÉCISION
    → renforcer le re‑ranking (cross‑encodeur), réduire le top‑k final
```

🎯 **Diagnostic type.** Une question sur « roulement 6204‑ZZ » échoue. Vous
inspectez les candidats : la référence exacte n'est dans **aucun** des 20 → c'est
le **rappel** qui pèche (le vectoriel a dilué « 6204‑ZZ »). Solution : s'assurer
que BM25 est bien dans la fusion, pas pousser le re‑ranking.

---

### 14.2 Comparer les six stratégies RAG

Le chapitre 6 les listait ; voici **pourquoi** et **quand** chacune gagne — et ce
qu'elle peut **casser**.

| Stratégie | Gagne quand… | Faiblesse / risque | Coût (appels LLM) | Latence |
|---|---|---|---|---|
| **Standard** | la question est factuelle et bien posée | rate les questions vagues ou multi‑étapes | 1 | ⚡ faible |
| **Multi‑Query** | la question est vague/polysémique | génère du bruit si les reformulations dérivent | 1 + N (reformulations) | moyenne |
| **Agentic / ReAct** | question multi‑étapes, comparaison | peut **boucler** ou sur‑raisonner ; non déterministe | plusieurs tours | 🐢 élevée |
| **Adaptive** | trafic mixte (court + complexe) | dépend de la qualité du **classifieur** de routage | 1 (routage) + coût de la branche | variable |
| **Corrective (CRAG)** | corpus incomplet, anti‑hallucination | l'évaluation de pertinence ajoute un appel ; peut sur‑filtrer | 2+ | moyenne |
| **Self‑RAG** | exigence de **traçabilité** | l'auto‑critique peut être complaisante (le modèle se juge lui‑même) | 2+ | moyenne |

🧠 **La vraie question n'est pas « laquelle est la meilleure »** mais « quel est le
**profil de mes questions** ? ». Sur un trafic homogène et factuel, **Standard**
bat tout le monde au rapport qualité/coût ; **Adaptive** n'a d'intérêt que si le
trafic est **hétérogène** (sinon on paie un classifieur pour rien). Agentic ne se
justifie que si une part réelle des questions est **composée** — autrement, on
dépense 3× le calcul pour le même résultat.

⚙️ **Comment décider par la mesure.** Faites tourner le **même** jeu de questions
sur deux stratégies et comparez trois colonnes :

```
              note LLM‑juge   taux d'hallucination   latence p50
Standard          7,9               6 %                 0,8 s
Agentic           8,1              4 %                 3,4 s
```

Ici Agentic gagne +0,2 point pour **4×** la latence : sur un trafic factuel, ce
n'est **pas** rentable. La décision se lit dans l'écart de note **rapporté** au
surcoût, jamais sur la note seule.

---

### 14.3 Comparer les méthodes d'apprentissage

Deux décisions distinctes, souvent confondues : **(A)** faut‑il fine‑tuner ou
faire du RAG ? **(B)** si fine‑tuning, quelle méthode ?

#### A. RAG vs Fine‑tuning — ce sont des outils différents, pas des rivaux

| Critère | **RAG** | **Fine‑tuning** |
|---|---|---|
| Type de savoir | **faits** volatils, qui changent | **comportement** : style, ton, format, abstention |
| Mise à jour | instantanée (ré‑indexer) | lente (ré‑entraîner) |
| Fraîcheur | toujours à jour | « gravé », vieillit |
| Coût marginal | par requête (recherche) | en amont (entraînement), puis quasi nul |
| Traçabilité | excellente (sources citées) | faible (savoir dilué dans les poids) |
| Risque | docs hors‑sujet → mauvaise réponse | sur‑apprentissage, oubli catastrophique |

🧠 **Règle de Spectra.** Un fait qui peut changer demain (nomenclature, événement)
**ne doit jamais** être fine‑tuné — il serait figé et faux. Il reste au RAG. À
l'inverse, « répondre dans le style maison » ou « savoir s'abstenir » sont des
**comportements** : le RAG ne les apprend pas, le fine‑tuning oui. Le levier
`spectra.fine-tuning.sft-excluded-categories` matérialise exactement cette
frontière.

#### B. SFT vs DPO vs ORPO

| Méthode | Apprend | Besoin d'un modèle de référence | Données | Coût | Choisir quand |
|---|---|---|---|---|---|
| **SFT** | à **imiter** les bonnes réponses | non | `{prompt, réponse}` | faible | poser le format, le ton, l'abstention |
| **DPO** | à **préférer** *chosen* à *rejected* | **oui** (mémoire doublée) | `{prompt, chosen, rejected}` | moyen | affiner des préférences **après** un bon SFT |
| **ORPO** | imitation **+** préférence en **une passe** | **non** | `{prompt, chosen, rejected}` | faible | alternative légère au couple SFT→DPO |

💡 **L'arbitrage clé.** DPO suppose un SFT préalable réussi **et** charge un
second modèle figé en mémoire. ORPO fait les deux en un, **sans** référence :
plus léger, souvent meilleur sur petits modèles. D'où le choix de Spectra de
**proposer ORPO** comme voie par défaut quand la VRAM est comptée.

⚠️ **Faiblesse commune à DPO/ORPO.** Le signal de préférence n'existe que si
*chosen* et *rejected* **diffèrent vraiment** (garde Jaccard > 0,85 → paire
rejetée). Deux réponses quasi identiques n'apprennent **rien** et bruitent
l'entraînement.

---

### 14.4 Comment interpréter les résultats (sans se faire piéger)

Comparer des algorithmes ne vaut que si l'on **lit les bons chiffres correctement**.
Voici les métriques de Spectra, ce qu'elles disent — et leurs pièges.

#### A. Métriques de recherche

| Métrique | Question à laquelle elle répond | Se dégrade quand… |
|---|---|---|
| **Recall@k** | « le bon doc est‑il dans les `k` premiers ? » | présélection trop étroite, `ef_search` trop bas |
| **Precision@k** | « parmi les `k`, combien sont pertinents ? » | re‑ranking absent ou faible |
| **MRR** | « à quel **rang** arrive le premier bon doc ? » | bon doc présent mais mal classé |
| **nDCG** | « les meilleurs sont‑ils **en haut** ? » (rang pondéré) | ordre imparfait du top‑k |

🧠 **Le couple qui ment si on le lit seul.** Le **rappel** seul se maximise en
renvoyant *tout* (rappel = 100 %, précision ridicule). La **précision** seule se
maximise en ne renvoyant qu'un doc ultra‑sûr (précision = 100 %, rappel
catastrophique). **Toujours les lire ensemble** : on cherche un rappel élevé en
présélection **puis** une précision élevée après re‑ranking.

#### B. Métriques de génération / modèle

```
note LLM‑juge (1‑10)   → qualité perçue d'une réponse vs question + sources
taux d'hallucination   → % de réponses inventées là où le modèle DEVAIT s'abstenir
eval_loss (par époque) → suit le sur‑apprentissage pendant le fine‑tuning
latence p50 / p95      → temps de réponse typique / pire cas
```

⚠️ **Piège n°1 — la fuite de données (le plus grave).** Évaluer un modèle
fine‑tuné sur des exemples **issus de son dataset d'entraînement** donne des notes
artificiellement hautes : il les a *déjà vus*. C'est pourquoi Spectra mesure sur
un **benchmark tenu à l'écart, jamais entraîné** (`benchmarks/highway_benchmark.jsonl`).
**Toute comparaison de modèles doit utiliser ce jeu, pas un échantillon du
dataset.**

⚠️ **Piège n°2 — la note moyenne qui cache l'hallucination.** Un modèle peut
gagner +1 point de note moyenne **tout en hallucinant davantage** (il répond avec
assurance là où il devrait se taire). C'est pourquoi note **et** taux
d'hallucination se lisent **ensemble** : une hausse de note accompagnée d'une
hausse d'hallucination est un **mauvais** échange pour un assistant RAG.

⚠️ **Piège n°3 — le juge est un LLM.** Le LLM‑juge a des biais (préférence pour
les réponses longues, complaisance). Il est fiable pour **comparer deux versions
sur le même jeu** (les biais s'annulent), beaucoup moins pour donner une note
« absolue ». Lisez‑le toujours en **différentiel** (avant/après), pas en valeur
nue.

⚠️ **Piège n°4 — `eval_loss` qui remonte.** Si la perte de validation **remonte**
pendant que la perte d'entraînement baisse, c'est le signe classique du
**sur‑apprentissage** : le modèle mémorise au lieu de généraliser. Arrêtez plus
tôt (early stopping) ou réduisez le nombre d'époques. C'est précisément ce que le
`--val-split` permet de surveiller (§ 9).

#### C. Lire une comparaison « base vs fine‑tuné »

L'endpoint `/api/quality-benchmark/compare` rejoue le **même** benchmark sur les
deux modèles. La bonne lecture tient en une grille de décision :

```
exactitude ↑  ET  hallucination ↓   → PROMOUVOIR (gain net, sans contrepartie)
exactitude ↑  ET  hallucination ↑   → PRUDENCE : il répond mieux mais ment plus
exactitude ≈  ET  hallucination ↓   → PROMOUVOIR si la fiabilité prime
exactitude ↓                        → NE PAS PROMOUVOIR (régression)
latence ↑↑ sans gain de qualité     → NE PAS PROMOUVOIR (coût pur)
```

🎯 **Exemple d'usage complet.** Vous comparez le modèle de base et un fine‑tuné
sur les 50 questions du benchmark tenu à l'écart :

| Mesure | Base | Fine‑tuné | Lecture |
|---|---|---|---|
| Note LLM‑juge (différentiel) | 6,8 | 8,1 | **+1,3** : nette amélioration |
| Taux d'hallucination | 18 % | 7 % | **−11 pts** : les exemples de refus paient |
| Latence p50 | 0,9 s | 0,9 s | stable |

→ Les deux indicateurs de qualité s'améliorent **sans** coût de latence :
décision claire, on **promeut** le modèle fine‑tuné. Si l'hallucination était au
contraire **montée** à 25 %, on aurait gardé la base malgré la meilleure note —
parce que pour un assistant RAG, **mentir avec assurance est pire que répondre
modestement**.

---

### 14.5 Mémo de décision (à garder sous la main)

| Vous voulez… | Levier le plus efficace |
|---|---|
| Retrouver un terme exact (réf., code) | activer/renforcer **BM25** dans la fusion |
| Capter les synonymes, le sens | **vectoriel** + bon embedding |
| Moins de bruit dans le top‑k final | **cross‑encodeur** (rerank), réduire le top‑k |
| Récupérer des bons docs « oubliés » | ↑ `n` présélection, ↑ `ef_search` |
| Répondre vite à du factuel | RAG **Standard** |
| Traiter des questions composées | **Agentic/ReAct** (si le trafic le justifie) |
| Réduire les hallucinations | exemples de **refus** (§8.B) + **CRAG** + alignement **ORPO** |
| Injecter un savoir **volatil** | **RAG** (jamais le fine‑tuning) |
| Imposer un **style/comportement** | **SFT**, puis **ORPO** si VRAM comptée |
| Décider de promouvoir un modèle | benchmark **tenu à l'écart** : note **et** hallucination |

> 🧭 **À retenir.** Il n'y a pas d'algorithme « meilleur » dans l'absolu — il y a
> un compromis **rappel ↔ précision ↔ coût** adapté à *votre* trafic et *vos*
> données. Et un chiffre ne se lit jamais seul : rappel **avec** précision, note
> **avec** hallucination, gain **rapporté** au surcoût.

---

<a name="15"></a>
## 15. Glossaire et pour aller plus loin

| Terme | En une phrase |
|-------|---------------|
| **Embedding** | Texte traduit en vecteur de nombres représentant son sens. |
| **Cosinus** | Mesure d'angle entre deux vecteurs ; 1 = même sens. |
| **HNSW** | Index en graphe multi‑étages pour trouver les voisins très vite. |
| **`ef_search`** | Effort de recherche HNSW : plus haut = meilleur rappel, plus lent. |
| **BM25** | Score de pertinence par mots‑clés (fréquence × rareté). |
| **RRF** | Fusion de deux classements par l'inverse des rangs. |
| **Bi‑encodeur** | Vecteurs question/document calculés séparément (rapide). |
| **Cross‑encodeur** | Paire (question, document) évaluée ensemble (précis). |
| **ReAct** | Boucle alternant raisonnement et action (recherche). |
| **CRAG** | RAG qui évalue puis corrige les passages récupérés. |
| **Self‑RAG** | RAG qui s'auto‑critique et vérifie l'étayage. |
| **Jaccard** | Similarité d'ensembles : intersection / union. |
| **DPO** | Apprentissage par préférences (bonne vs mauvaise réponse). |
| **LoRA / QLoRA** | Affinage léger via petites matrices (+ quantization 4 bits). |
| **LLM‑as‑a‑Judge** | Un LLM note la qualité d'une réponse. |
| **ORPO** | Imitation + préférence en une passe, sans modèle de référence. |
| **Recall@k** | Le bon document est‑il dans les `k` premiers résultats ? |
| **Precision@k** | Parmi les `k` résultats, quelle part est pertinente ? |
| **MRR** | Rang moyen (inverse) du premier bon résultat. |
| **nDCG** | Qualité du **classement** : les meilleurs sont‑ils en haut ? |
| **Sur‑apprentissage** | Le modèle mémorise au lieu de généraliser (`eval_loss` remonte). |
| **Fuite de données** | Évaluer sur des exemples déjà vus à l'entraînement → notes faussées. |
| **Circuit breaker** | Coupe‑circuit qui isole un service défaillant. |
| **Thread virtuel** | Fil d'exécution ultra‑léger, idéal pour l'attente réseau. |

### Pour aller plus loin (idées sources)
- *Plus proches voisins approchés* — **HNSW** (graphes « petit monde » hiérarchiques).
- *Recherche lexicale* — **BM25** (modèle probabiliste Okapi).
- *Fusion de classements* — **Reciprocal Rank Fusion**.
- *Raisonnement‑action* — **ReAct**.
- *RAG robuste* — **Corrective RAG (CRAG)** et **Self‑RAG**.
- *Préférences* — **Direct Preference Optimization (DPO)** et **ORPO** (odds‑ratio).
- *Affinage efficace* — **LoRA** et **QLoRA**.
- *Évaluation du retrieval* — **Recall@k**, **Precision@k**, **MRR**, **nDCG**.

---

*Spectra : du document brut à l'expertise métier, en toute confidentialité.*
