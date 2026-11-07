package fr.spectra.config;

import fr.spectra.service.FtsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Non-régression : après un redémarrage l'API ne doit pas se déclarer prête tant que l'index BM25
 * n'est pas là, sinon une requête « hybride » tourne en vectoriel seul (journal {@code both=0}).
 */
class FtsReadinessGateTest {

    private final FtsService fts = mock(FtsService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final FtsReadinessGate gate = new FtsReadinessGate(fts, publisher);

    private ReadinessState published() {
        ArgumentCaptor<ApplicationEvent> event = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(event.capture());
        return ((AvailabilityChangeEvent<?>) event.getValue()).getState() instanceof ReadinessState s ? s : null;
    }

    @Test
    @DisplayName("l'application prête avec un index pas encore chargé referme la disponibilité")
    void closesReadinessWhileTheIndexIsLoading() {
        when(fts.isInitialLoadSettled()).thenReturn(false);

        gate.onApplicationReady(mock(ApplicationReadyEvent.class));

        assertThat(published()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
    }

    @Test
    @DisplayName("la fin du chargement rouvre la disponibilité")
    void reopensReadinessWhenTheIndexIsReady() {
        when(fts.isInitialLoadSettled()).thenReturn(false);
        gate.onApplicationReady(mock(ApplicationReadyEvent.class));

        gate.onInitialLoadSettled(new FtsService.InitialLoadSettled("spectra_documents", 26_572));

        ArgumentCaptor<ApplicationEvent> events = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
        assertThat(((AvailabilityChangeEvent<?>) events.getAllValues().get(1)).getState())
                .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    @DisplayName("index déjà chargé avant la fin du démarrage : la garde ne touche à rien")
    void doesNothingWhenTheIndexIsAlreadyLoaded() {
        when(fts.isInitialLoadSettled()).thenReturn(true);

        gate.onApplicationReady(mock(ApplicationReadyEvent.class));

        verify(publisher, never()).publishEvent(any(ApplicationEvent.class));
    }

    @Test
    @DisplayName("un chargement terminé avant l'événement « prêt » ne publie rien : Spring ouvrira de lui-même")
    void ignoresASettleEventBeforeTheApplicationIsReady() {
        gate.onInitialLoadSettled(new FtsService.InitialLoadSettled("spectra_documents", 10));

        verify(publisher, never()).publishEvent(any(ApplicationEvent.class));
    }
}
