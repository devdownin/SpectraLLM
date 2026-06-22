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
14. [Glossaire et pour aller plus loin](#14)

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
**vos** documents.

### B. Paires de préférence (DPO)
💡 Le **DPO (Direct Preference Optimization)** apprend au modèle ce qui est
**préférable**. Pour chaque exemple on fournit une **bonne** réponse (*chosen*)
et une **moins bonne** (*rejected*, p. ex. une hallucination plausible). Le
modèle apprend à rapprocher ses sorties des *chosen* et à s'éloigner des
*rejected*.

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

### B. La boucle de feedback (👍/👎 → DPO → ré‑entraînement)
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
## 14. Glossaire et pour aller plus loin

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
| **Circuit breaker** | Coupe‑circuit qui isole un service défaillant. |
| **Thread virtuel** | Fil d'exécution ultra‑léger, idéal pour l'attente réseau. |

### Pour aller plus loin (idées sources)
- *Plus proches voisins approchés* — **HNSW** (graphes « petit monde » hiérarchiques).
- *Recherche lexicale* — **BM25** (modèle probabiliste Okapi).
- *Fusion de classements* — **Reciprocal Rank Fusion**.
- *Raisonnement‑action* — **ReAct**.
- *RAG robuste* — **Corrective RAG (CRAG)** et **Self‑RAG**.
- *Préférences* — **Direct Preference Optimization (DPO)**.
- *Affinage efficace* — **LoRA** et **QLoRA**.

---

*Spectra : du document brut à l'expertise métier, en toute confidentialité.*
