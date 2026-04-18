# Point d'étape — 2026-04-02

## Contexte
Session interrompue sur un blocage Docker Desktop : extraction de `browserless/chrome:latest`
gelée (WSL2 + grande image ~2 GB décompressée). **Redémarrer Docker Desktop avant de reprendre.**

---

## État des services au moment de l'arrêt

| Service | État |
|---|---|
| `spectra-api` (local jar) | Était UP, tué proprement |
| `llama-server` local | Était UP et fonctionnel (testé) |
| `docker compose up -d` | **Bloqué** sur extraction `browserless/chrome` |
| Autres conteneurs Docker | Exited (255) — pas encore redémarrés |

---

## Première action au redémarrage

```bash
# Démarrer sans browserless (URL ingestion non critique pour le RAG)
docker compose up -d chromadb llama-cpp-embed llama-cpp-chat spectra-api spectra-frontend
```

Attendre que `spectra-llama-chat` soit **healthy** (~90s, charge 2.3 GB GGUF).

### Tests à relancer après démarrage

```bash
# 1. Status global
curl -s http://localhost:8080/api/status | python3 -c "
import sys,json; d=json.load(sys.stdin)
for s in d['services']: print('[OK]' if s['available'] else '[KO]', s['name'])
"

# 2. Query RAG (si des docs sont ingérés)
curl -s -X POST http://localhost:8080/api/query \
  -H "Content-Type: application/json" \
  -d '{"question":"Test de fonctionnement RAG","maxContextChunks":3}'
```

---

## Travail effectué dans cette session

### Modèle Phi-3.5-mini installé
- **Fichier :** `data/fine-tuning/merged/phi-3.5-mini-Q4_K_M.gguf` (2.3 GB, GGUF valide)
- **Source :** `bartowski/Phi-3.5-mini-instruct-GGUF` (Q4_K_M — meilleur ratio CPU)
- **Performances mesurées :** ~6 tokens/s sur 16 cœurs CPU, contexte 4096

### Changements non commités (20 fichiers)

#### Java — nouvelles fonctionnalités
| Fichier | Contenu |
|---|---|
| `LlmUnavailableException.java` (**nouveau**) | Exception → HTTP 503 via circuit breaker |
| `LlmChatClient.java` | Interface : ajout `chatStream()` (default = Flux.just) |
| `LlamaCppChatClient.java` | `@CircuitBreaker` sur `chat()`, implémentation `chatStream()` SSE |
| `RagService.java` | `queryStream()` → SSE avec events sources/token/done/error |
| `QueryController.java` | Endpoint `POST /api/query/stream` (SSE streaming) |
| `StatusController.java` | Health checks en parallèle (CompletableFuture) |
| `GlobalExceptionHandler.java` | Handlers pour `LlmUnavailableException` (503) et `UnsupportedOperationException` (400) |
| `FineTuningService.java` | Report : "Ollama" → "llama-server" |
| `application.yml` | Runtime llama-server local, Phi-3.5-mini, KV cache q8_0 |
| `pom.xml` | Ajout resilience4j + spring-boot-starter-aop |

#### Frontend
| Fichier | Contenu |
|---|---|
| `api.ts` | `queryApi.queryStream()` — async generator fetch+ReadableStream |
| `Playground.tsx` | Streaming token-par-token, curseur animé, sources affichées |
| `Documentation.tsx` | Toutes références Ollama remplacées (prérequis, commandes, descriptions) |
| `FineTuning.tsx` | Toast completion : "dans Ollama" → "dans llama-server" |

#### Scripts & Docker
| Fichier | Contenu |
|---|---|
| `export_gguf.py` | Step 3 : `ollama create` → `curl POST /api/fine-tuning/models/register` |
| `llama-autostart.sh` | Guard existence fichier GGUF avant exec |
| `Dockerfile` | `COPY lib*.so*` → `RUN --mount cp || true` (build statique) |
| `docker-compose.yml` | `MODEL_PATH` et `MODEL_ALIAS` → `phi-3.5-mini-Q4_K_M.gguf` / `phi-3.5-mini` |

#### Config runtime
| Fichier | Contenu |
|---|---|
| `application.yml` (racine) | Override external Spring Boot — **temporaire, ne pas committer** |
| `data/models/registry.json` | `activeChatModel: phi-3.5-mini`, nouvelle entrée GGUF |

---

## Fix important à noter : `--flash-attn on`

Le `LlamaCppRuntimeOrchestrator` (dans le jar compilé) ajoute `--flash-attn` comme flag nu,
mais cette version de `llama-server.exe` attend `--flash-attn on|off|auto`.

**Workaround actif :** `extra-args: ["on"]` dans `application.yml` — le `on` est collé
automatiquement après `--flash-attn` par l'orchestrateur.

**Fix permanent :** modifier `LlamaCppRuntimeOrchestrator.java:223` :
```java
// Avant
if (recommended.flashAttn()) command.add("--flash-attn");
// Après
if (recommended.flashAttn()) { command.add("--flash-attn"); command.add("on"); }
```
→ Nécessite recompilation du jar.

---

## Prochaines étapes (backlog)

1. **Immédiat** : Valider `docker compose up` (sans browserless) + test RAG end-to-end
2. **Compiler le jar** avec les nouvelles fonctionnalités Java (Maven cassé dans le terminal actuel — utiliser l'IDE)
3. **Committer** tous les changements de cette session
4. **Audit restant** :
   - Fix `.doc` support (`DocxExtractor.java`) — 30 min
   - H2 persistence pour jobs/tasks en mémoire — effort plus important
5. **Phase 8** : Benchmarks end-to-end avec Phi-3.5-mini

---

## Fichiers de données créés (non versionnés)

```
data/fine-tuning/merged/phi-3.5-mini-Q4_K_M.gguf   2.3 GB  ← modèle actif
data/models/registry.json                                    ← mis à jour
application.yml                                             ← override local (ne pas committer)
```
