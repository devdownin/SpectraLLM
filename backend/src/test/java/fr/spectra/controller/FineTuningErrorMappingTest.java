package fr.spectra.controller;

import fr.spectra.service.BaseModelCatalog;
import fr.spectra.service.FineTuningService;
import fr.spectra.service.LlmChatClient;
import fr.spectra.service.ModelRegistryService;
import fr.spectra.service.training.TrainingUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ce que l'appelant HTTP reçoit réellement quand le fine-tuning est refusé.
 *
 * <p><b>Pourquoi passer par MockMvc plutôt qu'appeler le handler directement.</b>
 * {@code GlobalExceptionHandlerTest} vérifie le <i>corps</i> des méthodes de mapping : appelées,
 * elles produisent le bon {@code ProblemDetail}. Il ne vérifie pas qu'elles sont <b>appelées</b>.
 * Or c'est précisément ce qui casse en silence lors d'un remaniement — une annotation
 * {@code @ExceptionHandler} retirée, un type d'exception plus général qui capture en amont, un
 * ordre de résolution modifié. Le service lèverait bien son exception, les tests unitaires
 * resteraient verts, et l'API répondrait 500.
 *
 * <p><b>Pourquoi {@code standaloneSetup} et non {@code @WebMvcTest}.</b> Les tranches de test de
 * Spring Boot 4 vivent dans des modules séparés, absents du classpath de ce projet ; les ajouter
 * pour un seul test serait disproportionné. {@code standaloneSetup} suffit ici : il construit un
 * vrai {@code ExceptionHandlerExceptionResolver} avec l'advice fourni, donc la résolution
 * d'exception traversée est celle de production. Ce qu'il ne couvre pas — que
 * {@code GlobalExceptionHandler} soit bien découvert comme {@code @ControllerAdvice} — l'est par
 * {@code ApplicationContextSmokeTest}, qui charge le contexte complet.
 */
class FineTuningErrorMappingTest {

    private static final String BODY = """
            {"modelName":"mon-modele","baseModel":"phi3"}""";

    private static final String REASON =
            "script d'entraînement introuvable : /app/scripts/train.sh. "
                    + "L'image spectra-api ne contient ni scripts/ ni Python.";

    private FineTuningService fineTuningService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        fineTuningService = mock(FineTuningService.class);
        FineTuningController controller = new FineTuningController(
                fineTuningService, mock(LlmChatClient.class),
                mock(BaseModelCatalog.class), mock(ModelRegistryService.class),
                mock(fr.spectra.service.JobTelemetryStore.class),
                mock(fr.spectra.service.training.TrainingRunner.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("exécuteur absent → 503, et non 500")
    void unavailableRunnerMapsTo503() throws Exception {
        when(fineTuningService.submit(any())).thenThrow(new TrainingUnavailableException(REASON));

        mockMvc.perform(post("/api/fine-tuning")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("la réponse est un ProblemDetail portant la cause en clair")
    void unavailableRunnerExplainsWhy() throws Exception {
        // Un 503 sans motif obligerait l'exploitant à fouiller les journaux du serveur —
        // exactement ce que le lot 4a cherchait à éviter.
        when(fineTuningService.submit(any())).thenThrow(new TrainingUnavailableException(REASON));

        mockMvc.perform(post("/api/fine-tuning")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Entraînement indisponible"))
                .andExpect(jsonPath("$.detail").value(containsString("train.sh")));
    }

    @Test
    @DisplayName("entraînement déjà en cours → 409, distinct du 503")
    void alreadyRunningMapsTo409() throws Exception {
        // Les deux refus ne doivent pas se confondre : « réessayez plus tard » (409) et
        // « cette installation ne sait pas entraîner » (503) appellent des actions opposées.
        when(fineTuningService.submit(any())).thenReturn(null);

        mockMvc.perform(post("/api/fine-tuning")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("exécuteur disponible → 200 avec le job complet, pas seulement son identifiant")
    void availableRunnerMapsTo200() throws Exception {
        when(fineTuningService.submit(any())).thenReturn("job-42");
        // La réponse porte désormais le job entier : l'UI affiche son suivi sans attendre le
        // premier sondage, au lieu d'un panneau creux pendant quatre secondes.
        when(fineTuningService.getJob("job-42")).thenReturn(
                fr.spectra.dto.FineTuningJob.pending("job-42", new fr.spectra.dto.FineTuningRequest(
                        "mon-modele", "phi3", 64, 128, 3, 2e-4, 0.8,
                        false, false, false, false, 0.1)));

        mockMvc.perform(post("/api/fine-tuning")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-42"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.modelName").value("mon-modele"))
                .andExpect(jsonPath("$.totalEpochs").value(3));
    }
}
