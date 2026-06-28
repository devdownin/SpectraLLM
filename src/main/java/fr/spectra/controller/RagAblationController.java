package fr.spectra.controller;

import fr.spectra.dto.AblationReport;
import fr.spectra.dto.AblationRequest;
import fr.spectra.service.RagAblationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ablation A/B des enrichissements (RAG, fine-tuning) sur le benchmark tenu à l'écart.
 *
 * <p>Mesure, par configuration (bras), la qualité de génération (exactitude, hallucination), la
 * qualité de retrieval (Hit@k / MRR / Recall@k) et la latence — pour lire le gain marginal de
 * chaque enrichissement. Distinct de {@code /api/quality-benchmark} (modèle brut) et de
 * {@code /api/benchmark} (performance pure).</p>
 *
 * <p><b>Avertissement :</b> endpoint bloquant (plusieurs appels LLM par question × nombre de bras),
 * lent sur CPU.</p>
 */
@RestController
@RequestMapping("/api/ablation")
@Tag(name = "Ablation", description = "Gain marginal des enrichissements (RAG, fine-tuning)")
public class RagAblationController {

    private final RagAblationService service;

    public RagAblationController(RagAblationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            summary = "Lancer un passage d'ablation A/B",
            description = "Évalue une matrice de configurations (bras) sur le benchmark tenu à l'écart : "
                    + "qualité de génération (exactitude, hallucination), qualité de retrieval "
                    + "(Hit@k / MRR / Recall@k) et latence. Corps vide = matrice par défaut "
                    + "(LLM seul vs RAG sur le modèle actif). Chaque bras peut fixer un 'model' "
                    + "(comparaison base vs fine-tuné) et 'useRag'. Bloquant, lent sur CPU.")
    public AblationReport run(@RequestBody(required = false) AblationRequest request) {
        return service.run(request);
    }
}
