# Audit — Sécurité

> Audit du 2026-07-18. Périmètre : surface d'authentification/autorisation, exposition réseau
> (CORS, actuator), endpoints mutants, SSRF, désérialisation, traversée de chemin, gestion des
> secrets, DoS/amplification de coût. Croisement code (`ApiKeyFilter`, `WebConfig`, contrôleurs,
> `UrlFetcherService`, `FtsService`, `LlmFitService`), configuration (`application.yml`) et
> déploiement (compose, k8s).
>
> Ce rapport remplace `SECURITY_AUDIT.md`, supprimé sans successeur. `SECURITY.md` (à la racine)
> reste la **politique** de signalement ; ce document en est le **constat technique**.
>
> Constat général : la couche transport et les entrées « externes » sont **bien durcies**
> (SSRF, ZIP-bombs, désérialisation, traversée de chemin — voir §4). La faiblesse est le
> **modèle d'authentification/autorisation** : il est binaire (une clé unique partagée, ou rien),
> désactivé par défaut, sans identité par utilisateur — et, activé, il casse les mises à jour
> temps réel. Un déploiement exposé au-delà d'un réseau de confiance n'a donc pas de contrôle
> d'accès exploitable en l'état.

---

## 1. Authentification & autorisation

### S1 — Aucune identité par utilisateur ; l'audit trail est répudiable — **élevé**

`ApiKeyFilter` valide un **secret unique partagé** (`SPECTRA_API_KEY`, header `X-API-Key`) pour
toute l'API. Il n'y a ni comptes, ni rôles, ni principal authentifié. Conséquences :

- **`?actor=` est purement déclaratif.** Les mutations GED et les commentaires
  (`transitionLifecycle`, `addTags`, `deleteDocument`, `linkToModel`, commentaires) prennent
  l'acteur d'un **paramètre de requête fourni par le client** (`@RequestParam(defaultValue =
  "api") String actor`). L'audit trail R6 (`ged_audit_log`) enregistre donc *ce que l'appelant
  déclare*, pas *qui il est* — n'importe qui peut écrire `actor=alice`. La traçabilité, qui est
  une fonctionnalité mise en avant de la GED, n'a **aucune valeur probante**.
- **Aucune séparation des privilèges (RBAC).** Quiconque détient la clé (ou tout le monde si
  elle n'est pas configurée, cf. S2) peut tout faire : ingérer, supprimer des documents, lancer
  un fine-tuning, supprimer des modèles, purger le stockage.

Recommandation : introduire une vraie authentification (Spring Security + OIDC/JWT, ou au minimum
plusieurs clés nommées) et **dériver l'acteur du principal authentifié**, jamais du paramètre de
requête. Tant que ce n'est pas fait, retirer `actor` des paramètres publics et le figer à une
valeur serveur éviterait l'illusion de traçabilité.

### S2 — Non sécurisé par défaut — **élevé**

Si `SPECTRA_API_KEY` est absente ou vide, `ApiKeyFilter` **laisse passer toutes les requêtes**
(mode développement, un warning est loggé). Le défaut est donc *ouvert* : une instance démarrée
sans configuration explicite expose **tous** les endpoints mutants sans authentification.
`SECURITY.md` recommande bien de définir la clé, mais c'est un opt-in : l'oubli est silencieux
côté sécurité (l'application fonctionne parfaitement).

Recommandation : au minimum, logger un avertissement **au niveau WARN à chaque démarrage** de
façon très visible (déjà partiellement le cas) et le documenter en tête de `getting-started`.
Idéalement, refuser de démarrer en profil `prod` sans clé (fail-closed).

### S3 — Activer la clé API casse tout le temps réel (SSE) — **élevé (piège d'exploitation)**

Le frontend consomme les flux temps réel (`/api/sse/system-load`, `/training-logs`, `/tasks`)
via l'API navigateur **`EventSource`**, qui **ne peut pas envoyer d'en-tête personnalisé**. Ces
endpoints ne sont pas exemptés dans `ApiKeyFilter.shouldNotFilter` (seuls `/actuator`,
`/swagger-ui`, `/api-docs` le sont). Donc **dès qu'on active `SPECTRA_API_KEY`, tous les flux SSE
renvoient 401** : le centre de tâches, les logs de fine-tuning et la charge système cessent de se
mettre à jour.

C'est un piège : le seul mécanisme d'authentification fourni, une fois activé, **dégrade
l'application** — ce qui pousse en pratique à le laisser désactivé (revient à S2). Le SSO/OIDC de
S1 réglerait le fond ; à court terme, il faut un schéma d'auth compatible EventSource (jeton en
paramètre de requête à usage court, ou cookie de session `HttpOnly` + exemption CSRF sur les
GET SSE).

> **✅ Correctif backend appliqué.** `ApiKeyFilter` résout désormais la clé depuis trois sources,
> dans l'ordre : header `X-API-Key`, **paramètre de requête `apiKey`**, puis **cookie `X-API-Key`**.
> Un `EventSource` peut donc s'authentifier via le paramètre ou le cookie, faute de pouvoir poser
> un en-tête — les flux SSE ne renvoient plus 401 quand `SPECTRA_API_KEY` est active. ⚠️ Le
> paramètre de requête peut apparaître dans les journaux d'accès / l'en-tête `Referer` (préférez le
> cookie ou le header hors SSE). **Reste à faire côté frontend** : aucun mécanisme de saisie /
> transmission de la clé n'existe encore dans l'UI (voir S2) — le correctif ouvre le canal, mais
> l'UI doit encore fournir la clé aux appels et aux URL `EventSource`.

### S4 — Ordre CORS / filtre : préflight rejeté quand la clé est active — **faible**

Le CORS est géré par `WebMvcConfigurer` (niveau MVC, après les filtres servlet), alors que
`ApiKeyFilter` est un filtre servlet exécuté **avant**. Une préflight `OPTIONS` cross-origin
n'emporte pas d'en-tête custom → rejetée en 401 avant d'atteindre le CORS. N'affecte que la
combinaison « dev cross-origin **+** clé API activée » (en prod, nginx proxyfie et le CORS est
désactivé). À corriger en exemptant `OPTIONS` dans `shouldNotFilter`.

---

## 2. Exposition réseau

### S5 — Actuator non authentifié + détails de santé complets — **moyen**

`ApiKeyFilter.shouldNotFilter` exempte `/actuator/**` (nécessaire pour les probes Docker/k8s),
et `application.yml` expose `health,info,prometheus,metrics` avec **`show-details: always`**. Sur
un port joignable, un anonyme obtient donc :

- `/actuator/health` — l'état **et les détails** de chaque service externe (URLs internes de
  `llm-chat`, `llm-embed`, ChromaDB, reranker, docparser…) : cartographie de la topologie interne ;
- `/actuator/prometheus` et `/actuator/metrics` — métriques applicatives (volumes, latences,
  compteurs d'ingestion), utiles à un attaquant pour le profilage.

Sur un déploiement correctement isolé (S recommandé par `SECURITY.md` §2), l'impact est contenu ;
mais l'exemption est inconditionnelle et `show-details: always` est le réglage le plus verbeux.
Recommandation : `show-details: when-authorized` (avec une vraie auth), ou n'exposer publiquement
que les groupes de probes `liveness`/`readiness` (sans détails) et réserver `prometheus`/`metrics`
au réseau de scrape (NetworkPolicy).

### S6 — CORS : configurable et sans `allowCredentials` — **info (point positif)**

`WebConfig` restreint le CORS à `/api/**`, aux origines configurables (défaut : localhost),
**sans** `allowCredentials(true)`. L'absence de credentials + l'absence d'auth par cookie
signifient qu'un CORS permissif ne divulguerait pas de réponse authentifiée. Réglage sain — à
préserver (ne pas ajouter `allowCredentials` sans repenser l'auth).

---

## 3. DoS & amplification de coût

### S7 — Aucune limitation de débit sur des endpoints coûteux et ouverts par défaut — **moyen**

Aucun rate limiting (`resilience4j.ratelimiter`, bucket, ou en amont) n'est présent. Or plusieurs
endpoints déclenchent un travail **lourd et non authentifié par défaut** (S2) :

- `POST /api/query` / `/query/stream` — une inférence LLM complète par appel ;
- `POST /api/dataset/generate`, `/dpo/generate` — N appels LLM (un verrou anti-concurrence
  existe, mais un seul job monopolise déjà la ressource) ;
- `POST /api/ged/documents/{sha}/comments` (génération IA) — retrieval + inférence ;
- `POST /api/ingest` — extraction/embedding (borné en mémoire, mais pas en fréquence).

Un client non authentifié peut donc saturer le GPU/CPU et, sur une facturation cloud, amplifier
les coûts. Recommandation : rate limiting par IP/clé sur les endpoints LLM (au minimum derrière le
reverse proxy nginx documenté), et documenter cette dépendance dans `SECURITY.md`.

---

## 4. Points durcis (à préserver)

Ces surfaces ont déjà été traitées et constituent la vraie force sécurité du projet :

- **SSRF** (`UrlFetcherService`) — validation de schéma + résolution DNS contrôlée **au moment de
  la connexion** (`doAfterResolve`, ferme la fenêtre de DNS-rebinding TOCTOU), redirections 30x
  désactivées, plages loopback/privées/link-local/CGNAT/ULA rejetées. Le chemin browserless reste
  advisory (défense = egress réseau du conteneur), c'est documenté.
- **Désérialisation** (`FtsService`) — `ObjectInputFilter` en liste blanche stricte
  (`BM25Index` + types JDK, `!*`) : un `.bin` malveillant ne peut pas instancier une chaîne de
  gadgets.
- **ZIP-bombs** (`IngestionTaskExecutor`, `LimitedInputStream`) — profondeur max, nombre d'entrées
  max, taille décompressée bornée, filtrage de traversée de chemin dans les entrées.
- **Traversée de chemin** (`LlmFitService`) — suppression de GGUF/cache par nom simple, `normalize()`
  + confinement au répertoire cible, refus des noms contenant `/`.
- **Comparaison de clé à temps constant** (`ApiKeyFilter`, `MessageDigest.isEqual`).
- **Upload** — bornes multipart (50 Mo/fichier, 100 Mo/requête) + timeout d'upload Tomcat (120 s,
  anti-slowloris).

---

## 5. Divers / faible

- **Mot de passe H2 vide** (`spring.datasource.password:`) — sans risque en l'état (base fichier,
  console H2 désactivée, pas de serveur TCP), mais à ne pas exposer si un jour la console ou un
  mode serveur est activé.
- **docparser** journalise le nom de fichier fourni par l'utilisateur (log injection mineure) ;
  l'écriture temporaire n'utilise que le *suffixe* du nom (`Path(filename).suffix`), donc pas de
  traversée.

---

## 6. Priorisation

| # | Sujet | Sévérité | Nature du correctif |
|---|-------|----------|---------------------|
| S1 | Identité par utilisateur + acteur dérivé du principal (audit trail probant, RBAC) | Élevé | Architectural (auth) — décision produit |
| S3 | Auth compatible SSE (le SSO réglerait S1 **et** S3) | Élevé | Architectural |
| S2 | Fail-closed en prod sans clé (ou avertissement très visible) | Élevé | Faible |
| S5 | Actuator : `when-authorized` / restreindre `prometheus` au réseau de scrape | Moyen | Faible |
| S7 | Rate limiting sur les endpoints LLM | Moyen | Moyen |
| S4 | Exempter `OPTIONS` du filtre clé API | Faible | Trivial |

**Nature de ce document** : audit (analyse). Aucun correctif de sécurité n'est appliqué ici — le
cœur du sujet (S1/S3) est un **choix d'architecture d'authentification** qui appartient à
l'équipe, et les demi-mesures (S2/S4/S5) n'ont de sens qu'une fois cette direction tranchée.
Deux constats connexes relevés pendant l'audit ont été traités séparément : la contrainte
**mono-instance** (documentée dans [architecture](../architecture.en.md#scaling--operational-constraints))
et la **CI kustomize non épinglée** (corrigée dans `k8s-validate.yml`).
