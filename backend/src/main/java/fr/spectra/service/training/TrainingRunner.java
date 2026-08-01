package fr.spectra.service.training;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Exécute un entraînement, quel que soit l'endroit où il s'exécute.
 *
 * <p><b>Le problème que cette abstraction résout (F1).</b> {@code FineTuningService} lançait
 * {@code ./scripts/train.sh} par {@code ProcessBuilder} et {@code python3} pour l'export GGUF.
 * L'image {@code spectra-api} est un {@code eclipse-temurin:25-jre} : <b>ni Python, ni
 * {@code scripts/}</b>. En Docker, chaque job était donc accepté, générait son jeu de données,
 * créait son répertoire de travail… puis mourait sur une {@code IOException} à mi-course. La
 * fonctionnalité était annoncée disponible et ne l'était pas.
 *
 * <p>Séparer « quoi entraîner » de « où l'exécuter » permet à l'appelant de poser la question
 * <b>avant</b> d'accepter le travail : {@link #isAvailable()} d'abord, un 503 actionnable
 * sinon. Une fonctionnalité explicitement absente vaut mieux qu'une fonctionnalité qui échoue
 * à mi-parcours.
 *
 * <p><b>Ce qui reste hors de cette interface, délibérément.</b> Le cycle de vie du job (statuts,
 * persistance, sémantique d'annulation) appartient à {@code FineTuningService} : le runner ne
 * sait qu'exécuter, diffuser et tuer. D'où {@code cancelled} passé en paramètre plutôt que
 * consulté ici — l'état d'annulation a plusieurs lecteurs, il n'a qu'un propriétaire.
 */
public interface TrainingRunner {

    /**
     * Le runner peut-il exécuter un entraînement <b>maintenant</b> ? Contrôle local et bon
     * marché, appelé avant d'accepter une soumission.
     */
    boolean isAvailable();

    /**
     * Pourquoi le runner est indisponible, formulé pour être <b>lu par un exploitant</b> :
     * ce qui manque, et ce qu'il faut faire. {@code null} quand {@link #isAvailable()} est vrai.
     */
    String unavailabilityReason();

    /**
     * Lance l'entraînement et bloque jusqu'à sa fin.
     *
     * @param onLine    reçoit chaque ligne de sortie (journal, SSE, extraction de progression)
     * @param cancelled consulté pendant l'exécution ; à {@code true}, le runner interrompt
     * @return le code de sortie du processus (0 = succès)
     */
    int train(TrainingSpec spec, Consumer<String> onLine, BooleanSupplier cancelled) throws Exception;

    /**
     * Fusionne l'adaptateur LoRA et le convertit en GGUF. Mêmes conventions que
     * {@link #train}.
     */
    int exportGguf(ExportSpec spec, Consumer<String> onLine, BooleanSupplier cancelled) throws Exception;

    /**
     * Interrompt le travail en cours pour ce job.
     *
     * @return {@code true} si quelque chose a effectivement été interrompu
     */
    boolean cancel(String jobId);
}
