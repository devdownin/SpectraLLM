# Fiabilisation — Suivi des améliorations

Priorité d'implémentation : **2 → 4 → 1 → 8 → 5/6/7 → 3 → 9 → 10/11**

---

## Critique — risque de perte de données / état bloqué

### [2] `generatedPairs` croît sans borne et se duplique entre runs — ✅ DONE
- **Problème** : `CopyOnWriteArrayList` accumule toutes les paires de tous les `submit()` sans reset ni déduplication. Deux clics "Generate" doublent les paires.
- **Fix** : Vider la liste et le fichier JSONL au début de `generateAsync()`, réécrire le fichier en sortie.

### [4] `AsyncConfig` ignore les virtual threads — ✅ DONE
- **Problème** : `spring.threads.virtual.enabled: true` activé mais `ThreadPoolTaskExecutor` crée des threads platform. Les tâches `@Async` n'en bénéficient pas.
- **Fix** : Remplacer par `SimpleAsyncTaskExecutor` avec `setVirtualThreads(true)`.

### [1] Streaming Playground sans timeout de garde — ✅ DONE
- **Problème** : Si llama-server ne termine pas (`done` jamais émis), le message reste `STREAMING` indéfiniment.
- **Fix** : `AbortController` avec timeout 120s côté frontend + `Flux.timeout(generateTimeout)` côté backend dans `queryStream()`.

### [3] H2 avec `ddl-auto: update` sans migration contrôlée — ⬜ TODO
- **Problème** : Un changement de schéma peut corrompre silencieusement la base sans rollback possible.
- **Fix** : Migrer vers Flyway ou Liquibase pour tracer les évolutions de schéma.

---

## Robustesse UX — états figés ou incohérents

### [8] Génération dataset concurrente non protégée — ✅ DONE
- **Problème** : Deux appels simultanés à `POST /api/dataset/generate` écrivent en parallèle → doublons.
- **Fix** : `AtomicBoolean generationRunning` dans `DatasetGeneratorService`, retour 409 si déjà en cours.

### [5] Polling sans backoff exponentiel — ✅ DONE
- **Problème** : `Comparison.tsx` poll toutes les 3s, `Datasets.tsx` toutes les 10s — sans backoff si l'API est down.
- **Fix** : Après 3 échecs consécutifs, doubler l'intervalle jusqu'à 60s max.

### [6] localStorage chat sans plafond de taille — ✅ DONE
- **Problème** : `Playground.tsx` sérialise tout l'historique. Au-delà de ~100 messages, `QuotaExceededError` non capturé.
- **Fix** : Limiter à 50 messages récents avant persistance.

### [7] Polling de tâche sans cleanup si onglet fermé — ✅ DONE
- **Problème** : `setInterval` non nettoyés si le composant est démonté en cours de tâche.
- **Fix** : Vérifier que `clearInterval` est appelé dans tous les chemins de sortie des `useEffect`.

---

## Performance / Scalabilité

### [9] `getAllDocuments()` charge toute la collection en mémoire — ⬜ TODO
- **Problème** : `listSources()` et `generateAsync()` chargent tous les textes en RAM. Sur 10 000 chunks → plusieurs centaines de MB.
- **Fix** : Remplacer par `getDocumentsPaged()` en boucle (page size = 500).

---

## Observabilité

### [10] `actuator/health` expose les détails à tous — ⬜ TODO
- **Problème** : `show-details: always` retourne l'état interne à n'importe quel client.
- **Fix** : Passer à `show-details: when-authorized` ou filtrer via `ApiKeyFilter`.

### [11] Pas de correlation ID dans les logs — ⬜ TODO
- **Problème** : Si deux ingestions tournent en parallèle, les logs s'entremêlent sans corrélation.
- **Fix** : Ajouter `MDC.put("taskId", taskId)` au début de chaque tâche async, `MDC.clear()` en finally.
