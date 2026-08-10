package fr.spectra.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Map de tâches asynchrones dont toute écriture est répercutée en base.
 *
 * <h2>Pourquoi une décoration plutôt qu'un {@code save()} sur chaque site</h2>
 *
 * <p>L'état des tâches est muté depuis de nombreux endroits — pour l'ingestion, depuis
 * {@code IngestionService} <b>et</b> depuis {@code IngestionTaskExecutor}, à qui la map est
 * passée en paramètre et qui la modifie sur neuf sites ; pour la génération de jeux de données,
 * depuis onze sites du même service. Brancher la persistance site par site suppose de tous les
 * trouver, et surtout que le prochain contributeur qui en ajoutera un pense à le faire.
 *
 * <p>C'est exactement le mode de défaillance décrit par l'audit de simplification : un
 * branchement qui cesse silencieusement de fonctionner, sans qu'aucun test ne rougisse. En
 * décorant la map elle-même, la question ne se pose plus — toute mutation, présente ou future,
 * passe par ici, et aucun appelant n'a à savoir que la persistance existe.
 *
 * <h2>La base est un miroir, pas la source de vérité</h2>
 *
 * <p>La map reste l'autorité à l'exécution. C'est délibéré : {@code computeIfPresent} donne
 * l'atomicité sur laquelle repose la correction d'une course annulation/fin documentée dans
 * {@code IngestionService.cancelTask()}. Un aller-retour en base (lire, décider, écrire) la
 * réintroduirait. La base sert à survivre au redémarrage, pas à arbitrer la concurrence.
 *
 * <p>Corollaire assumé : <b>un échec d'écriture en base ne fait pas échouer la tâche.</b> Il est
 * journalisé et l'on continue. Perdre l'historique d'une ingestion est regrettable ; faire
 * échouer l'ingestion elle-même parce que H2 hoquette le serait davantage.
 *
 * <h2>Les vues sont en lecture seule</h2>
 *
 * <p>{@link #entrySet()}, {@link #keySet()} et {@link #values()} renvoient des copies non
 * modifiables. Une purge écrite {@code entrySet().removeIf(...)} contournerait le miroir et
 * ferait diverger les deux stockages en silence ; elle lève désormais
 * {@code UnsupportedOperationException} au lieu de vider la map en laissant les lignes en base.
 * Passez par {@link #remove(Object)}.
 *
 * @param <V> type de la tâche (DTO), tel que manipulé par le service
 */
public class PersistentTaskMap<V> implements Map<String, V> {

    private static final Logger log = LoggerFactory.getLogger(PersistentTaskMap.class);

    private final Map<String, V> live = new ConcurrentHashMap<>();
    private final Consumer<V> save;
    private final Consumer<String> delete;
    private final Function<V, String> idOf;
    private final String label;

    /**
     * @param save   persiste une tâche (conversion DTO → entité incluse)
     * @param delete supprime une tâche par identifiant
     * @param idOf   extrait l'identifiant d'une tâche, pour les journaux d'échec
     * @param label  nom du domaine (« ingestion », « génération »), pour les journaux
     */
    public PersistentTaskMap(Consumer<V> save, Consumer<String> delete,
                             Function<V, String> idOf, String label) {
        this.save = save;
        this.delete = delete;
        this.idOf = idOf;
        this.label = label;
    }

    // ── Miroir ────────────────────────────────────────────────────────────────

    private void mirror(V task) {
        if (task == null) return;
        try {
            save.accept(task);
        } catch (RuntimeException e) {
            log.warn("Tâche {} ({}) non persistée : {} — le traitement continue, l'historique "
                    + "sera incomplet après un redémarrage.", idOf.apply(task), label, e.getMessage());
        }
    }

    // ── Écritures ─────────────────────────────────────────────────────────────

    @Override
    public V put(String key, V value) {
        V previous = live.put(key, value);
        mirror(value);
        return previous;
    }

    @Override
    public V computeIfPresent(String key, BiFunction<? super String, ? super V, ? extends V> fn) {
        // La fonction est appliquée par la ConcurrentHashMap sous son verrou de segment :
        // l'atomicité vue par l'appelant est préservée. Le miroir est écrit APRÈS, hors du
        // verrou — une écriture H2 n'a rien à faire sous un verrou de map.
        V updated = live.computeIfPresent(key, fn);
        mirror(updated);
        return updated;
    }

    @Override
    public V remove(Object key) {
        V removed = live.remove(key);
        if (removed != null) {
            try {
                delete.accept(String.valueOf(key));
            } catch (RuntimeException e) {
                log.warn("Tâche {} ({}) non supprimée en base : {}", key, label, e.getMessage());
            }
        }
        return removed;
    }

    @Override
    public void putAll(Map<? extends String, ? extends V> m) {
        m.forEach(this::put);
    }

    @Override
    public void clear() {
        List<String> keys = List.copyOf(live.keySet());
        live.clear();
        keys.forEach(k -> {
            try {
                delete.accept(k);
            } catch (RuntimeException e) {
                log.warn("Tâche {} ({}) non supprimée en base : {}", k, label, e.getMessage());
            }
        });
    }

    /**
     * Réinjecte les tâches lues en base au démarrage, <b>sans</b> les réécrire — ici c'est la
     * base qui alimente la map, pas l'inverse. Réservé à la reprise après redémarrage.
     */
    public void rehydrate(Map<String, V> restored) {
        live.putAll(restored);
    }

    // ── Lectures : délégation, vues non modifiables ───────────────────────────

    @Override public int size() { return live.size(); }
    @Override public boolean isEmpty() { return live.isEmpty(); }
    @Override public boolean containsKey(Object key) { return live.containsKey(key); }
    @Override public boolean containsValue(Object value) { return live.containsValue(value); }
    @Override public V get(Object key) { return live.get(key); }

    @Override public Set<String> keySet() { return Set.copyOf(live.keySet()); }
    @Override public Collection<V> values() { return List.copyOf(live.values()); }
    @Override public Set<Entry<String, V>> entrySet() { return Set.copyOf(live.entrySet()); }
}
