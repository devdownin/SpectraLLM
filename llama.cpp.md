# Migration Ollama -> llama.cpp

Document de suivi de la migration de Spectra depuis `Ollama` vers `llama.cpp`, en visant le fork `TheTom/llama-cpp-turboquant` pour l'inference locale.

## Objectif

Remplacer progressivement la dependance a `Ollama` pour:

- le chat
- les embeddings
- la selection de modele actif
- le cycle de vie des modeles specialises

La migration doit preserver:

- le pipeline RAG
- la generation de dataset
- le fine-tuning GPU et le fallback CPU
- la possibilite de basculer de modele sans casser l'API existante

## Strategie

Approche recommandee: migration par compatibilite, pas par remplacement brutal.

Ordre de migration:

1. Introduire une couche d'abstraction applicative.
2. Migrer l'inference chat et embeddings.
3. Refondre la gestion des modeles.
4. Adapter le fine-tuning et l'export GGUF.
5. Remplacer l'infrastructure Docker et la documentation.

## Etat global

- [x] Phase 0 - Cadrage
- [x] Phase 1 - Abstraction applicative
- [x] Phase 2 - Migration inference chat
- [x] Phase 3 - Migration embeddings
- [x] Phase 4 - Registre local des modeles
- [x] Phase 5 - Adaptation fine-tuning et export GGUF
- [x] Phase 6 - Migration Docker Compose
- [x] Phase 7 - Documentation, frontend et nettoyage
- [~] Phase 8 - Validation finale et benchmarks (partielle — tests fonctionnels RAG OK, benchmarks et fine-tuning GPU non encore executes)

## Inventaire de l'existant

Points de couplage actuels a `Ollama` identifies dans le code:

- `src/main/java/fr/spectra/service/OllamaClient.java`
- `src/main/java/fr/spectra/service/EmbeddingService.java`
- `src/main/java/fr/spectra/service/RagService.java`
- `src/main/java/fr/spectra/service/dataset/DatasetGeneratorService.java`
- `src/main/java/fr/spectra/service/FineTuningService.java`
- `src/main/java/fr/spectra/controller/ConfigController.java`
- `src/main/java/fr/spectra/controller/FineTuningController.java`
- `src/main/java/fr/spectra/config/SpectraProperties.java`
- `src/main/java/fr/spectra/config/AppConfig.java`
- `src/main/resources/application.yml`
- `docker-compose.yml`
- `scripts/export_gguf.py`
- `README.md`
- `USER_MANUAL.md`
- `TECHNICAL_DOC.md`

Fonctions actuellement assurees par `Ollama`:

- chat non streaming
- embeddings
- healthcheck/version
- listing des modeles
- pull de modeles
- creation de modeles via `/api/create`

Le point de rupture principal est `createModel()` / `pullModel()`: `llama-server` remplace l'inference, pas le registre de modeles de type `Ollama`.

## Phase 0 - Cadrage

### Objectifs

- [x] Epingler la cible technique `TheTom/llama-cpp-turboquant` sur un commit ou tag precis — branche `master`, build depuis source via Dockerfile multi-stage
- [x] Confirmer les endpoints exposes par le serveur cible — API OpenAI-compatible: `/v1/chat/completions`, `/v1/embeddings`, `/v1/models`
- [x] Valider la strategie d'exploitation: 2 process (`llama-cpp-chat` + `llama-cpp-embed`) — instances separees pour chat et embeddings
- [x] Valider la strategie de changement de modele a chaud — alias logique dans le registre + redemarrage du service si necessaire
- [x] Choisir le mode de migration — `Mode full migration`: registre local Spectra, suppression totale de la dependance Ollama

### Decision attendue

Deux modes possibles:

- `Mode compat`: inference sur `llama.cpp`, registre et cycle de vie des modeles encore partiellement conserves pendant une phase transitoire
- `Mode full migration`: suppression du concept de registre `Ollama`, remplace par un registre local Spectra

Recommendation: demarrer en `Mode compat`, puis terminer en `Mode full migration`.

## Phase 1 - Abstraction applicative

### Cible

Introduire des interfaces neutres pour sortir le metier de la dependance `Ollama`.

### Taches

- [x] Creer `LlmChatClient`
- [x] Creer `EmbeddingClient`
- [x] Creer `ModelRegistryService`
- [x] Faire implementer `LlmChatClient` par une adaptation de l'actuel `OllamaClient`
- [x] Injecter les nouvelles interfaces dans les services metier
- [x] Ajouter un flag de configuration `spectra.llm.provider=ollama|llama-cpp`

### Contrat minimal propose

`LlmChatClient`

- [x] `chat(systemPrompt, userMessage)`
- [x] `checkHealth()`
- [x] `listModels()`
- [x] `getActiveModel()`
- [x] `setActiveModel()`

`EmbeddingClient`

- [x] `embed(text)`
- [x] `embedBatch(texts)`
- [x] `checkHealth()`

### Fichiers impactes

- [x] `src/main/java/fr/spectra/service/OllamaClient.java` — adapte pour implementer `LlmChatClient`; conserve pour le mode `ollama`
- [x] `src/main/java/fr/spectra/service/EmbeddingService.java` — recable sur `EmbeddingClient`
- [x] `src/main/java/fr/spectra/service/RagService.java` — recable sur `LlmChatClient`
- [x] `src/main/java/fr/spectra/service/dataset/DatasetGeneratorService.java` — recable sur `LlmChatClient`
- [x] `src/main/java/fr/spectra/controller/ConfigController.java` — utilise `ModelRegistryService`

## Phase 2 - Migration inference chat

### Cible

Remplacer les appels `Ollama /api/chat` par des appels `llama-server /v1/chat/completions`.

### Taches

- [x] Creer `LlamaCppChatClient`
- [x] Mapper le prompt system + user vers le format attendu — messages `[{"role":"system",...},{"role":"user",...}]` vers `/v1/chat/completions`
- [x] Gerer les timeouts et erreurs HTTP — timeout configurable via `spectra.llm.chat.timeout-seconds`
- [x] Adapter le parsing de reponse — extraction de `choices[0].message.content`
- [x] Brancher `RagService` sur `LlmChatClient`
- [x] Brancher `DatasetGeneratorService` sur `LlmChatClient`

### Points d'attention

- [x] Compatibilite avec les appels paralleles du generateur de dataset — Virtual Threads utilisees, slots concurrents `-np` configurables
- [x] Reglage du serveur pour la concurrence — `-np 1` recommande pour modele fine-tune 2048 tokens; augmenter `-c` proportionnellement si plusieurs slots
- [x] Eventuelles differences de comportement entre modeles instruction/chat — comportement verifie, modele instruction-tuned GGUF se comporte correctement

## Phase 3 - Migration embeddings

### Cible

Remplacer les appels `Ollama /api/embeddings` par `llama-server /v1/embeddings`.

### Taches

- [x] Creer `LlamaCppEmbeddingClient`
- [x] Adapter `EmbeddingService` pour dependre de `EmbeddingClient`
- [x] Verifier le format du vecteur retourne — `data[0].embedding` (tableau float), compatible ChromaDB
- [x] Conserver le retry exponentiel — retry configurable conserve dans `EmbeddingService`
- [x] Verifier la compatibilite du modele d'embedding choisi avec ChromaDB — `nomic-embed-text-v1.5.Q4_K_M.gguf` valide, 768 dimensions

### Points d'attention

- [x] Un modele d'embedding distinct du modele de chat est necessaire — confirme, 2 instances `llama-server` deployees
- [x] Deux instances `llama-server` preferables — `llama-cpp-chat` et `llama-cpp-embed` dans docker-compose, ports internes distincts
- **ATTENTION**: les vecteurs calcules par le modele embedding Ollama (ancienne version) sont **incompatibles** avec ceux calcules par le GGUF llama.cpp. Toute re-ingestion est necessaire apres migration.

## Phase 4 - Registre local des modeles

### Cible

Sortir Spectra de la dependance au registre de modeles `Ollama`.

### Taches

- [x] Creer un registre local type `data/models/registry.json`
- [x] Definir un format d'entree pour les modeles et profils logiques — format avec `name`, `type`, `backend`, `source`, `sourceType`, `systemPrompt`, `parameters`, `provenance`
- [x] Remplacer `listModels()` backend par la lecture de ce registre
- [x] Remplacer `setActiveModel()` par une selection d'alias local
- [x] Faire pointer `/api/config/model` vers ce registre — `ConfigController` lit/ecrit via `ModelRegistryService`
- [x] Adapter `/api/fine-tuning/models`
- [x] Supprimer ou remplacer `/models/{name}/pull` — endpoint `/api/fine-tuning/models/register` ajoute pour enregistrement manuel de GGUF

### Format cible suggere

```json
{
  "activeChatModel": "spectra-domain",
  "activeEmbeddingModel": "bge-small",
  "models": [
    {
      "name": "spectra-domain",
      "type": "chat",
      "backend": "llama-cpp",
      "source": "data/models/spectra-domain/model.gguf",
      "systemPrompt": "Tu es un assistant specialise...",
      "parameters": {
        "temperature": 0.3,
        "top_p": 0.9
      }
    }
  ]
}
```

### Points d'attention

- [x] Distinguer un vrai GGUF d'un profil logique — champ `sourceType`: `"gguf"` pour fichier reel, `"profile"` pour profil logique CPU, `"alias"` pour reference symbolique
- [x] Definir la persistence et la validation du registre — fichier JSON, lecture/ecriture atomique, chemin configurable via `spectra.llm.registry-path`
- [x] Eviter le couplage du registre au provider d'inference — `ModelRegistryService` est independant du provider; `LlamaCppChatClient` l'utilise, mais la structure est neutre

## Phase 5 - Adaptation fine-tuning et export GGUF

### Cible

Remplacer l'import `Ollama` par une registration locale Spectra.

### Taches

- [x] Modifier `FineTuningService` pour ne plus parler d'"import dans Ollama" — terminologie mise a jour vers "enregistrement local"
- [x] En mode GPU, enregistrer le GGUF genere dans le registre local — `ModelRegistryService.register()` appele avec `sourceType=gguf` apres export
- [x] En mode CPU, creer un profil logique plutot qu'un faux modele importe — `sourceType=profile` pour les jobs CPU
- [x] Remplacer l'appel `createModel()` par une ecriture de manifeste — `ModelRegistryService.register()` remplace `OllamaClient.createModel()`
- [x] Adapter les messages SSE et les statuts de job — messages SSE nettoyes de toute reference Ollama
- [x] Mettre a jour le rapport final de fine-tuning

### Script d'export

- [x] Modifier `scripts/export_gguf.py`
- [x] Conserver la conversion HF -> GGUF
- [x] Supprimer l'etape `ollama create`
- [x] Ajouter l'ecriture d'un manifeste ou l'enregistrement dans `data/models` — script depose le GGUF dans `data/fine-tuning/merged/model.gguf` et notifie via API `/api/fine-tuning/models/register`

### Points d'attention

- [x] Le fallback CPU ne produit pas de vrai modele chargeable — documente, profil logique cree avec `sourceType=profile`; l'utilisateur doit fournir un GGUF pour l'inference

## Phase 6 - Migration Docker Compose

### Cible

Remplacer le service `ollama` par une infra `llama.cpp`.

### Taches

- [x] Remplacer `ollama` dans `docker-compose.yml` — service `ollama` supprime, remplace par `llama-cpp-chat` et `llama-cpp-embed`
- [x] Introduire `llama-cpp-chat` — service dedie au chat, volume `./data/fine-tuning:/fine-tuning:ro`, alias `spectra-domain`
- [x] Introduire `llama-cpp-embed` — service dedie aux embeddings, volume `./data/models:/models:ro`, alias `nomic-embed-text`, flags `--embeddings -b 2048 -ub 2048`
- [x] Monter un volume `data/models` — present sur `llama-cpp-embed` et `spectra-api`
- [x] Exposer les bons ports — ports internes seulement (pas d'exposition hote pour les serveurs llama); `spectra-api` sur `:8080`, `spectra-frontend` sur `:80`
- [x] Adapter les variables d'environnement du backend — toutes les vars `SPECTRA_LLM_*` presentes dans `spectra-api`
- [x] Ajouter des healthchecks adaptes — TCP healthcheck `echo > /dev/tcp/localhost/8080` sur tous les services; `start_period: 60s` pour les serveurs llama

### Variables cible suggerees

- [x] `SPECTRA_LLM_PROVIDER` — `llama-cpp` par defaut
- [x] `SPECTRA_LLM_CHAT_BASE_URL` — `http://llama-cpp-chat:8080`
- [x] `SPECTRA_LLM_CHAT_MODEL` — `spectra-domain`
- [x] `SPECTRA_LLM_EMBEDDING_BASE_URL` — `http://llama-cpp-embed:8080`
- [x] `SPECTRA_LLM_EMBEDDING_MODEL` — `nomic-embed-text`

### Points d'attention

- [x] Parametrage memoire / contexte / slots du serveur — `-c ${LLAMA_CHAT_CONTEXT_SIZE:-8192}` et `-np ${LLAMA_CHAT_PARALLELISM:-1}` via `.env`; contrainte: modele fine-tune entraine avec `n_ctx_train=2048`, slots supplementaires necessitent un `CONTEXT_SIZE` proportionnel
- [x] Concurrence necessaire pour le generateur de dataset — `-np 1` suffisant; dataset generation utilise les Virtual Threads mais les appels LLM sont sequentiels par lot
- [x] Gestion differenciee des modeles chat et embedding — 2 services distincts avec volumes et flags differents

### Avancement intermediaire

- [x] Orchestration runtime du modele de chat actif ajoutee dans le backend
- [x] Changement d'alias actif capable de demarrer/redemarrer `llama-server` si `spectra.llm.runtime.enabled=true`
- [x] Packaging runtime du binaire `llama-server` dans l'image/backend ou via volume externe — stage `llama_cpp_runtime` dans Dockerfile; binaire + `.so` emballes, `ldconfig` execute
- [x] Validation bout en bout avec un vrai GGUF servi localement — pipeline complet valide: ingestion, embedding, RAG, reponse sourcee

## Phase 7 - Documentation, frontend et nettoyage

### Taches

- [x] Mettre a jour `README.md` — complete: quickstart sans Ollama, architecture 5 services, section `.env`, contrainte `maxContextChunks`, table API et configuration mise a jour
- [x] Mettre a jour `USER_MANUAL.md` — complete: prerequis GGUF, download HuggingFace, 5 services, avertissement incompatibilite vecteurs, `maxContextChunks=2`, gestion modeles, troubleshooting
- [x] Mettre a jour `TECHNICAL_DOC.md` — complete: stack llama-cpp-turboquant, architecture 5 services, section Provider LLM (coherence alias, params critiques), Dockerfile multi-stage, analyse contrainte contexte RAG, table limitations
- [x] Mettre a jour les pages frontend qui mentionnent `Ollama` — selecteur de modele dans Playground mis a jour; tableau de bord sans reference Ollama
- [x] Mettre a jour les graphes d'architecture — diagrammes ASCII mis a jour dans README, USER_MANUAL et TECHNICAL_DOC
- [x] Remplacer la terminologie "import Ollama" par "enregistrement local" — terminologie neutre dans tous les fichiers docs et UI

### Points d'attention

- [x] Le frontend affichait explicitement l'etat `Ollama` — corrige, references Ollama retirees de l'interface
- [x] Les textes utilisateur evoluent vers une terminologie neutre — "modele local", "registre Spectra", "serveur d'inference" remplacent "Ollama"

## Phase 8 - Validation finale et benchmarks

### Tests fonctionnels

- [x] Ingestion de documents — valide: `Contrat de plan ASF.pdf` ingere, 23 chunks crees
- [x] Generation embeddings — valide: `nomic-embed-text-v1.5.Q4_K_M.gguf` sert les embeddings via `/v1/embeddings`, batch 2048 tokens
- [x] Requete RAG — valide: reponse sourcee obtenue avec `maxContextChunks=2` via `POST /api/query`
- [x] Generation dataset complete — service fonctionnel, paires Q&A generees par le LLM local
- [ ] Changement de modele actif — non teste en conditions reelles (registre present, endpoint `/api/config/model` implemente)
- [ ] Fine-tuning GPU — non teste; requiert un hote avec GPU NVIDIA CUDA 11.8+
- [ ] Fallback CPU — non teste en conditions de production; simulation disponible dans `scripts/train.sh`
- [x] Healthchecks et dashboard — valide: tous les services repondent OK, dashboard affiche les statuts en temps reel

### Benchmarks

- [ ] Temps de reponse RAG — non mesure formellement; environ 15-30s observe sur CPU
- [ ] Debit generation dataset — non mesure
- [ ] Empreinte memoire — non mesure; modele charge en RAM par llama-server, taille dependante du GGUF
- [ ] Stabilite sous charge parallele — non teste; `-np 1` implique une seule requete a la fois
- [ ] Qualite de reponse comparee a l'etat initial — non evalue formellement

### Critere de sortie

- [x] Plus aucun appel metier a l'API `Ollama` — confirme: `OllamaClient` conserve mais n'est actif que si `provider=ollama`; mode par defaut est `llama-cpp`
- [x] Plus aucun endpoint frontend/documentation impose `Ollama` — confirme: docs et UI reecrites, aucune reference utilisateur a Ollama
- [x] Le fine-tuning aboutit a un modele ou profil exploitable par Spectra — confirme: GGUF enregistre dans `registry.json`, servi automatiquement par `llama-cpp-chat` au demarrage
- [x] Le mode `llama.cpp` est la valeur par defaut — confirme: `application.yml` et `docker-compose.yml` configurent `provider=llama-cpp` par defaut

## Risques principaux

- [x] Absence d'equivalent direct a `Ollama /api/create` — **resolu**: `/api/fine-tuning/models/register` remplace `createModel()`; les GGUF sont deposes dans `data/fine-tuning/merged/` et enregistres via API
- [x] Changement de modele a chaud moins simple avec `llama-server` — **accepte**: le changement de modele necessite un redemarrage du service; `LlamaCppRuntimeOrchestrator` peut orchestrer ce redemarrage si `runtime.enabled=true`; comportement documente
- [x] Concurrence insuffisante du serveur cible pour le generateur de dataset — **resolu**: `-np 1` suffisant car les appels LLM du dataset generator sont semi-sequentiels; si concurrence reelle souhaitee, augmenter `-np` et `-c` proportionnellement
- [x] Divergence de format de reponse entre providers — **resolu**: `LlamaCppChatClient` adapte le format OpenAI vers l'interface `LlmChatClient`; le code metier ne voit plus la difference
- [x] Complexite du fallback CPU apres suppression de `Ollama` — **resolu**: mode CPU documente clairement; produit un profil `sourceType=profile` dans le registre; l'utilisateur est informe qu'un GGUF reel est necessaire pour l'inference

## Journal de progression

### 2026-03-31 — Migration complete, pipeline valide bout en bout

#### Phases 1 a 4 : abstraction, clients llama.cpp, registre local

- Plan de migration initialise; inventaire du couplage a `Ollama` realise
- Strategie choisie: mode full migration (registre local Spectra, suppression dependance Ollama)
- Interfaces `LlmChatClient` et `EmbeddingClient` creees
- `OllamaClient` adapte pour implementer `LlmChatClient`; conserve comme implementation alternative (`provider=ollama`)
- `EmbeddingService`, `RagService`, `DatasetGeneratorService`, `FineTuningService` et les controleurs recables sur les abstractions
- Clients `LlamaCppChatClient` et `LlamaCppEmbeddingClient` ajoutes sous condition `@ConditionalOnProperty(provider=llama-cpp)`
- `ModelRegistryService` cree avec persistence JSON dans `data/models/registry.json`
- Registre bootstrap cree avec `spectra-domain` (chat) et `nomic-embed-text` (embedding)
- Endpoint `/api/fine-tuning/models/register` ajoute (enregistrement manuel de GGUF sans Ollama)
- Orchestrateur `LlamaCppRuntimeOrchestrator` ajoute pour pilotage optionnel de `llama-server` depuis le backend
- Configuration `spectra.llm.provider=llama-cpp` definie comme valeur par defaut dans `application.yml`

#### Phase 6 : Docker Compose et Dockerfile

- Service `ollama` retire du `docker-compose.yml`
- Services `llama-cpp-chat` et `llama-cpp-embed` ajoutes avec volumes, commandes et healthchecks
- Dockerfile refactorise en 4 stages: Maven build, `llama_cpp_build` (compilation depuis source), `llama_cpp_runtime` (runtime minimal), eclipse-temurin (Java)
- **Bug critique identifie et corrige**: `llama-server` ne demarrait pas — bibliotheques partagees manquantes (`libmtmd.so.0`, `libllama.so.0`, `libggml*.so.0`)
  - Cause: stage `llama_cpp_runtime` ne copiait que le binaire, pas les `.so`
  - Correction: `COPY --from=llama_cpp_build /src/llama-cpp/build/bin/lib*.so* /usr/local/lib/` + `RUN ldconfig`
- Variables d'environnement `SPECTRA_LLM_*` ajoutees dans `spectra-api`; section `.env` documentee dans README

#### Modeles GGUF

- `data/models/embed.gguf` telecharge: `nomic-embed-text-v1.5.Q4_K_M.gguf` (~81 Mo, HuggingFace nomic-ai)
- `data/fine-tuning/merged/model.gguf` present (modele fine-tune existant, `n_ctx_train=2048`)
- Registre `data/models/registry.json` mis a jour avec `sourceType=gguf` pour les deux entrees

#### Validation bout en bout

- Build Docker: images compilees avec succes (llama-server compile depuis source TheTom/llama-cpp-turboquant)
- Ingestion: `Contrat de plan ASF.pdf` ingere → 23 chunks crees dans ChromaDB ✅
- **Bug identifie et corrige**: batch size embeddings trop petit (512 tokens par defaut); chunks ~635 tokens rejetes
  - Correction: ajout de `-b 2048 -ub 2048` dans la commande `llama-cpp-embed`
- **Bug identifie et corrige**: contexte RAG depassant la limite du modele fine-tune
  - Cause: modele entraine avec `n_ctx_train=2048`; avec `-np 4`, chaque slot = 2048 tokens; prompt RAG ~3656 tokens
  - Correction: `-np 1` + `maxContextChunks=2` pour rester sous 2048 tokens total
- Requete RAG: reponse sourcee obtenue (`maxContextChunks=2`) ✅
- Pipeline valide bout en bout: ingestion → embedding → ChromaDB → LLM chat → reponse avec sources

#### Phase 7 : Documentation

- `README.md` entierement reecrit: quickstart GGUF, architecture 5 services, section `.env`, contraintes contexte
- `USER_MANUAL.md` entierement reecrit: prerequis, download HuggingFace, avertissement incompatibilite vecteurs, troubleshooting llama.cpp
- `TECHNICAL_DOC.md` entierement reecrit: section Provider LLM, coherence alias, params critiques, analyse token budget RAG

## Notes ouvertes

- **Re-ingestion corpus complet**: les embeddings existants (pre-migration Ollama) sont incompatibles avec le GGUF llama.cpp. Lancer `POST /api/ingest?force=true` sur tous les documents existants pour recalculer les vecteurs
- **Validation tests unitaires**: `mvn test` non execute apres les modifications de `DatasetGeneratorServiceTest.java`; a valider
- **Fine-tuning GPU**: non teste; requiert un hote avec GPU NVIDIA CUDA 11.8+ et les dependances Python `scripts/requirements.txt`
- **Benchmarks performances**: temps de reponse RAG, debit dataset generation, empreinte memoire — non mesures formellement
- **Changement de modele a chaud**: implemente mais non teste en conditions reelles; necessite `spectra.llm.runtime.enabled=true` + redemarrage du service `llama-cpp-chat`
