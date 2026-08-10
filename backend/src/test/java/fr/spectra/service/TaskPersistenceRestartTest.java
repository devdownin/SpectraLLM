package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.IngestionTask;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.persistence.IngestionTaskEntity;
import fr.spectra.persistence.IngestionTaskRepository;
import fr.spectra.persistence.StreamSourceRepository;
import fr.spectra.service.extraction.DocumentExtractorFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Le cycle « redémarrage » de bout en bout, sans démon Docker.
 *
 * <p>{@code PersistentTaskMapTest} prouve que le décorateur écrit bien en base. Il ne dit rien
 * du <b>retour</b> : qu'au démarrage suivant les tâches sont relues, que celles restées en vol
 * sont soldées, et qu'elles réapparaissent dans l'API. C'était le trou de vérification signalé
 * en livrant le branchement de S1 — la seule façon de le combler semblait être de redémarrer
 * une vraie stack, elle ne l'était pas.
 *
 * <p>Le redémarrage est simulé fidèlement : une base pré-remplie, puis un service <b>neuf</b>
 * dont on déclenche la reprise. C'est exactement ce que fait Spring au boot via
 * {@code @PostConstruct}, sans avoir à démarrer de contexte.
 */
class TaskPersistenceRestartTest {

    // Dépôt bouchonné : suffisant ici, le mapping entité↔table étant déjà validé au boot par
    // « ddl-auto: validate » et le smoke test de contexte.

    private static IngestionTaskRepository repoWith(IngestionTask... seed) {
        java.util.Map<String, IngestionTaskEntity> rows = new java.util.LinkedHashMap<>();
        for (IngestionTask t : seed) rows.put(t.taskId(), IngestionTaskEntity.fromDto(t));

        IngestionTaskRepository repo = mock(IngestionTaskRepository.class);
        when(repo.findAll()).thenAnswer(i -> List.copyOf(rows.values()));
        when(repo.save(org.mockito.ArgumentMatchers.any(IngestionTaskEntity.class)))
                .thenAnswer(i -> {
                    IngestionTaskEntity e = i.getArgument(0);
                    rows.put(e.getTaskId(), e);
                    return e;
                });
        return repo;
    }

    private static IngestionService serviceOn(IngestionTaskRepository repo) {
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.pipeline()).thenReturn(
                new SpectraProperties.PipelineProperties(512, 64, 10, 30, 120, 4));
        ChromaDbClient chromaDb = mock(ChromaDbClient.class);
        when(chromaDb.getOrCreateCollection(anyString())).thenReturn("c1");

        return new IngestionService(
                mock(DocumentExtractorFactory.class), mock(TextCleanerService.class),
                mock(ChunkingService.class), mock(EmbeddingService.class), chromaDb,
                mock(FtsService.class), mock(IngestionTaskExecutor.class),
                mock(IngestedFileRepository.class), mock(GedService.class),
                mock(StreamSourceRepository.class), repo, props, 50, 4, 0);
    }

    @Test
    void uneTacheEnVolEstSoldeeAuRedemarrage() {
        // Une ingestion coupée net par l'arrêt du serveur : PROCESSING en base, plus aucun
        // thread pour la faire avancer. La laisser telle quelle afficherait une barre de
        // progression éternellement figée.
        IngestionTask enCours = IngestionTask.pending("t-vol", List.of("gros.pdf")).processing();
        IngestionTaskRepository repo = repoWith(enCours);

        IngestionService redemarre = serviceOn(repo);
        redemarre.reconcileInterruptedTasks();

        IngestionTask apres = redemarre.getTask("t-vol");
        assertThat(apres).isNotNull();
        assertThat(apres.status()).isEqualTo(IngestionTask.Status.FAILED);
        assertThat(apres.error()).contains("redémarrage");
    }

    @Test
    void uneTacheTerminaleEstRelueIntacte() {
        // C'est tout l'intérêt de la persistance : l'historique survit. Avant le branchement,
        // cette tâche avait purement et simplement disparu de l'API après un redémarrage.
        IngestionTask finie = IngestionTask.pending("t-ok", List.of("note.txt")).completed(42);
        IngestionService redemarre = serviceOn(repoWith(finie));

        redemarre.reconcileInterruptedTasks();

        IngestionTask apres = redemarre.getTask("t-ok");
        assertThat(apres).isNotNull();
        assertThat(apres.status()).isEqualTo(IngestionTask.Status.COMPLETED);
        assertThat(apres.chunksCreated()).isEqualTo(42);
        assertThat(apres.error()).isNull();
    }

    @Test
    void lesTachesRelues_apparaissentDansLApi() {
        // getAllTasks() alimente l'écran d'ingestion. Une reprise qui remplirait la base sans
        // repeupler la map laisserait l'utilisateur devant un historique vide — le symptôme
        // même qu'on corrige, mais déplacé d'un cran.
        IngestionService redemarre = serviceOn(repoWith(
                IngestionTask.pending("t-1", List.of("a.txt")).completed(1),
                IngestionTask.pending("t-2", List.of("b.txt")).processing()));

        redemarre.reconcileInterruptedTasks();

        assertThat(redemarre.getAllTasks())
                .extracting(IngestionTask::taskId)
                .containsExactlyInAnyOrder("t-1", "t-2");
    }

    @Test
    void uneBaseVide_neCassePasLeDemarrage() {
        IngestionService neuf = serviceOn(repoWith());

        neuf.reconcileInterruptedTasks();

        assertThat(neuf.getAllTasks()).isEmpty();
    }

    @Test
    void uneBaseIllisible_neBloquePasLeDemarrage() {
        // Un historique corrompu ne doit pas empêcher l'API de démarrer : on repart d'une map
        // vide plutôt que d'interdire toute nouvelle ingestion.
        IngestionTaskRepository casse = mock(IngestionTaskRepository.class);
        when(casse.findAll()).thenThrow(new IllegalStateException("base illisible"));

        IngestionService neuf = serviceOn(casse);

        neuf.reconcileInterruptedTasks();
        assertThat(neuf.getAllTasks()).isEmpty();
    }
}
