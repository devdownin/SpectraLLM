package fr.spectra.service.training;

/**
 * Levée quand aucun {@link TrainingRunner} n'est en état d'exécuter un entraînement.
 *
 * <p>Mappée en HTTP 503 par {@code GlobalExceptionHandler}, avec la raison en clair.
 *
 * <p><b>Pourquoi 503 et non 500.</b> Rien n'est cassé : l'environnement d'exécution n'est
 * simplement pas là. C'est un état de déploiement, corrigible par l'exploitant, et le message
 * doit lui dire quoi corriger — d'où {@link TrainingRunner#unavailabilityReason()}.
 */
public class TrainingUnavailableException extends RuntimeException {

    public TrainingUnavailableException(String message) {
        super(message);
    }
}
