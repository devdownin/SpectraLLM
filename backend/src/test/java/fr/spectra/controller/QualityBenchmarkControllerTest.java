package fr.spectra.controller;

import fr.spectra.dto.QualityCompareJob;
import fr.spectra.service.QualityBenchmarkService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vérifie les trois issues du DELETE /api/quality-benchmark/compare/{jobId} : 404 (job inconnu),
 * 409 (déjà terminé) et 200 (annulation coopérative acceptée). Test unitaire pur : le service est
 * mocké, on appelle la méthode du contrôleur directement (pas de contexte Spring).
 */
class QualityBenchmarkControllerTest {

    @Test
    void cancelCompareJob_jobInconnu_renvoie404() {
        QualityBenchmarkService service = mock(QualityBenchmarkService.class);
        when(service.getCompareJob("nope")).thenReturn(null);
        QualityBenchmarkController controller = new QualityBenchmarkController(service);

        assertThatThrownBy(() -> controller.cancelCompareJob("nope"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("inconnue");
    }

    @Test
    void cancelCompareJob_jobTermine_renvoie409() {
        QualityBenchmarkService service = mock(QualityBenchmarkService.class);
        QualityCompareJob done = QualityCompareJob.pending("j1", "a", "b").completed(null);
        when(service.getCompareJob("j1")).thenReturn(done);
        when(service.requestCancelCompare("j1")).thenReturn(false);
        QualityBenchmarkController controller = new QualityBenchmarkController(service);

        ResponseEntity<Map<String, String>> res = controller.cancelCompareJob("j1");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).containsKey("error");
    }

    @Test
    void cancelCompareJob_jobEnCours_renvoie200Cancelling() {
        QualityBenchmarkService service = mock(QualityBenchmarkService.class);
        QualityCompareJob running = QualityCompareJob.pending("j2", "a", "b").running("en cours");
        when(service.getCompareJob("j2")).thenReturn(running);
        when(service.requestCancelCompare("j2")).thenReturn(true);
        QualityBenchmarkController controller = new QualityBenchmarkController(service);

        ResponseEntity<Map<String, String>> res = controller.cancelCompareJob("j2");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("status", "CANCELLING");
        assertThat(res.getBody()).containsEntry("jobId", "j2");
    }
}
