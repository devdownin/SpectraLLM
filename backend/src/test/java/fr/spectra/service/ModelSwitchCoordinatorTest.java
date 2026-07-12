package fr.spectra.service;

import fr.spectra.dto.ServiceStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Attente de convergence après bascule du modèle actif : {@code activate} ne rend la main
 * que lorsque le serveur sert réellement le modèle demandé (contrat {@code available} de
 * {@code checkHealth}), et {@code restore} reste best-effort (jamais d'exception propagée).
 */
class ModelSwitchCoordinatorTest {

    private static ServiceStatus served() {
        return new ServiceStatus("llama-cpp", "http://test", true, "ok", 1, Map.of());
    }

    private static ServiceStatus notServed() {
        return new ServiceStatus("llama-cpp", "http://test", false, "model-not-loaded", 1, Map.of());
    }

    @Test
    void activate_attendQueLeServeurServeLeModele() {
        LlmChatClient chat = mock(LlmChatClient.class);
        // Rechargement en cours : deux sondes négatives avant la convergence.
        when(chat.checkHealth()).thenReturn(notServed(), notServed(), served());
        ModelSwitchCoordinator coordinator = new ModelSwitchCoordinator(chat, 5, 1);

        coordinator.activate("nouveau-modele");

        verify(chat).setActiveModel("nouveau-modele");
        verify(chat, org.mockito.Mockito.times(3)).checkHealth();
    }

    @Test
    void activate_timeout_echoueExplicitement() {
        LlmChatClient chat = mock(LlmChatClient.class);
        when(chat.checkHealth()).thenReturn(notServed());
        // Timeout 0 s : la première sonde négative suffit à dépasser l'échéance.
        ModelSwitchCoordinator coordinator = new ModelSwitchCoordinator(chat, 0, 1);

        assertThatThrownBy(() -> coordinator.activate("jamais-servi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jamais-servi");
    }

    @Test
    void awaitServed_toleseLesErreursDeSondePendantLeRechargement() {
        LlmChatClient chat = mock(LlmChatClient.class);
        // Pendant le redémarrage de llama-server, checkHealth peut jeter : on continue de sonder.
        when(chat.checkHealth())
                .thenThrow(new RuntimeException("connexion refusée"))
                .thenReturn(served());
        ModelSwitchCoordinator coordinator = new ModelSwitchCoordinator(chat, 5, 1);

        assertThatCode(() -> coordinator.awaitServed("modele")).doesNotThrowAnyException();
    }

    @Test
    void restore_bestEffort_navalePasLesExceptions() {
        LlmChatClient chat = mock(LlmChatClient.class);
        when(chat.getActiveModel()).thenReturn("autre");
        doThrow(new IllegalStateException("fichier disparu")).when(chat).setActiveModel(anyString());
        ModelSwitchCoordinator coordinator = new ModelSwitchCoordinator(chat, 1, 1);

        // Un échec de restauration ne doit jamais détruire le rapport calculé par l'appelant.
        assertThatCode(() -> coordinator.restore("original")).doesNotThrowAnyException();
    }

    @Test
    void restore_neRebasculePasSiDejaActif() {
        LlmChatClient chat = mock(LlmChatClient.class);
        when(chat.getActiveModel()).thenReturn("original");
        ModelSwitchCoordinator coordinator = new ModelSwitchCoordinator(chat, 1, 1);

        coordinator.restore("original");
        coordinator.restore(null);
        coordinator.restore("  ");

        verify(chat, never()).setActiveModel(anyString());
    }

    @Test
    void lock_estReentrant() {
        LlmChatClient chat = mock(LlmChatClient.class);
        AtomicReference<String> active = new AtomicReference<>("m");
        when(chat.getActiveModel()).thenAnswer(i -> active.get());
        doAnswer(i -> { active.set(i.getArgument(0)); return null; })
                .when(chat).setActiveModel(anyString());
        when(chat.checkHealth()).thenReturn(served());
        ModelSwitchCoordinator coordinator = new ModelSwitchCoordinator(chat, 1, 1);

        // Une comparaison (verrou tenu) enchaîne des passages qui reprennent le verrou.
        coordinator.lock();
        try {
            coordinator.lock();
            try {
                coordinator.activate("m2");
                assertThat(active.get()).isEqualTo("m2");
            } finally {
                coordinator.unlock();
            }
        } finally {
            coordinator.unlock();
        }
    }
}
