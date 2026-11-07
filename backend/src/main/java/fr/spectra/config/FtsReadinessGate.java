package fr.spectra.config;

import fr.spectra.service.FtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Garde la disponibilité ({@code /actuator/health/readiness}) fermée tant que l'index BM25 de la
 * collection par défaut n'est pas chargé ou reconstruit.
 *
 * <p>Sans cela, l'API se déclare prête alors que l'index se recharge encore (journal :
 * {@code chroma=26572, fts=0}). Toute requête reçue dans cet intervalle est « hybride » de nom
 * seulement : la fusion BM25 + vecteurs retombe sur les vecteurs seuls, sans erreur ni signal.
 * Cela a faussé des mesures de rappel et dégraderait en silence les réponses d'un utilisateur
 * qui interroge juste après un redémarrage.
 *
 * <p>Le groupe {@code liveness} n'est pas touché : un rechargement n'est pas une panne et ne doit
 * pas provoquer de redémarrage. Si la reconstruction échoue (ChromaDB indisponible), la garde se
 * lève quand même : le service reste servi en vectoriel seul, et le rattrapage planifié de
 * {@link FtsService} refait l'index ensuite.
 */
@Component
public class FtsReadinessGate {

    private static final Logger log = LoggerFactory.getLogger(FtsReadinessGate.class);

    private final FtsService fts;
    private final ApplicationEventPublisher publisher;
    private final AtomicBoolean applicationReady = new AtomicBoolean();

    public FtsReadinessGate(FtsService fts, ApplicationEventPublisher publisher) {
        this.fts = fts;
        this.publisher = publisher;
    }

    /** Spring Boot vient de déclarer l'application prête : on la referme si l'index n'est pas là. */
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        applicationReady.set(true);
        if (!fts.isInitialLoadSettled()) {
            log.info("Disponibilité retenue : index BM25 en cours de chargement");
            AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC);
        }
    }

    /** L'index est chargé (ou la reconstruction a échoué) : on rouvre. */
    @EventListener
    public void onInitialLoadSettled(FtsService.InitialLoadSettled event) {
        if (applicationReady.get()) {
            log.info("Disponibilité rouverte : index BM25 '{}' prêt ({} passages)", event.collection(), event.chunks());
            AvailabilityChangeEvent.publish(publisher, this, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }
}
