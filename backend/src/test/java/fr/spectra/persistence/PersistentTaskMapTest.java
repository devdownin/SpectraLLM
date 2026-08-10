package fr.spectra.persistence;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ce que ce test protège.
 *
 * <p>L'état des tâches d'ingestion est muté depuis {@code IngestionService} <b>et</b> depuis
 * {@code IngestionTaskExecutor}, à qui la map est passée en paramètre. La persistance ne tient
 * donc pas à un appel à {@code save()} bien placé, mais au fait que <b>toute</b> écriture passe
 * par le décorateur. Si cette propriété se perd, rien ne casse visiblement : les tâches
 * continuent de vivre en mémoire, et c'est seulement après un redémarrage que l'historique
 * s'avère vide. Exactement le défaut silencieux que l'audit a mis au jour.
 */
class PersistentTaskMapTest {

    /** Faux dépôt : enregistre l'ordre des écritures et des suppressions. */
    private static final class Recorder {
        final Map<String, String> saved = new LinkedHashMap<>();
        final List<String> deleted = new ArrayList<>();
        RuntimeException failWith;

        void save(String v) {
            if (failWith != null) throw failWith;
            saved.put(v, v);
        }

        void delete(String k) {
            deleted.add(k);
        }
    }

    private static PersistentTaskMap<String> mapOf(Recorder r) {
        return new PersistentTaskMap<>(r::save, r::delete, v -> v, "test");
    }

    @Test
    void put_ecritEnBase() {
        Recorder r = new Recorder();
        PersistentTaskMap<String> map = mapOf(r);

        map.put("t1", "valeur");

        assertThat(map.get("t1")).isEqualTo("valeur");
        assertThat(r.saved).containsKey("valeur");
    }

    @Test
    void computeIfPresent_ecritLeResultatEnBase() {
        // C'est LA voie utilisée par l'exécuteur d'ingestion : neuf de ses mutations passent
        // par computeIfPresent. Si elle n'était pas répercutée, la persistance ne couvrirait
        // que la création des tâches — et pas une seule transition de statut.
        Recorder r = new Recorder();
        PersistentTaskMap<String> map = mapOf(r);
        map.put("t1", "avant");

        map.computeIfPresent("t1", (k, v) -> "apres");

        assertThat(map.get("t1")).isEqualTo("apres");
        assertThat(r.saved).containsKeys("avant", "apres");
    }

    @Test
    void computeIfPresent_surCleAbsente_nEcritRien() {
        Recorder r = new Recorder();
        PersistentTaskMap<String> map = mapOf(r);

        map.computeIfPresent("inconnu", (k, v) -> "jamais");

        assertThat(r.saved).isEmpty();
    }

    @Test
    void remove_supprimeAussiEnBase() {
        // Sans cela, la purge horaire viderait la map et laisserait les lignes s'accumuler.
        Recorder r = new Recorder();
        PersistentTaskMap<String> map = mapOf(r);
        map.put("t1", "valeur");

        map.remove("t1");

        assertThat(map).doesNotContainKey("t1");
        assertThat(r.deleted).containsExactly("t1");
    }

    @Test
    void remove_dUneCleAbsente_neSupprimeRienEnBase() {
        Recorder r = new Recorder();
        PersistentTaskMap<String> map = mapOf(r);

        map.remove("inconnu");

        assertThat(r.deleted).isEmpty();
    }

    @Test
    void vuesNonModifiables_pourQuUnePurgeNePuissePasContournerLeMiroir() {
        // entrySet().removeIf(...) est l'écriture spontanée qu'on aurait écrite sans réfléchir
        // dans cleanupOldTasks : elle viderait la map SANS toucher la base. Elle doit échouer
        // franchement plutôt que de faire diverger les deux stockages en silence.
        Recorder r = new Recorder();
        PersistentTaskMap<String> map = mapOf(r);
        map.put("t1", "valeur");

        assertThatThrownBy(() -> map.entrySet().removeIf(e -> true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.keySet().remove("t1"))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(map).containsKey("t1");
        assertThat(r.deleted).isEmpty();
    }

    @Test
    void unEchecDeBaseNeFaitPasEchouerLaTache() {
        // Choix assumé : perdre l'historique est regrettable, faire échouer une ingestion de
        // plusieurs milliers de chunks parce que H2 hoquette le serait davantage.
        Recorder r = new Recorder();
        r.failWith = new IllegalStateException("base indisponible");
        PersistentTaskMap<String> map = mapOf(r);

        map.put("t1", "valeur");

        assertThat(map.get("t1")).isEqualTo("valeur");
    }

    @Test
    void rehydrate_neReecritPasEnBase() {
        // Au démarrage, c'est la base qui alimente la map. La réécrire produirait N écritures
        // inutiles à chaque boot, et masquerait l'instant réel de dernière modification.
        Recorder r = new Recorder();
        PersistentTaskMap<String> map = mapOf(r);

        map.rehydrate(Map.of("t1", "restauree"));

        assertThat(map.get("t1")).isEqualTo("restauree");
        assertThat(r.saved).isEmpty();
    }
}
