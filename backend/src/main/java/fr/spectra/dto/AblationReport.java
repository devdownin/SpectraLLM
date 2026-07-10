package fr.spectra.dto;

import java.time.Instant;
import java.util.List;

/**
 * Rapport global d'un passage d'ablation A/B : un {@link AblationArmReport} par configuration,
 * exécuté sur le <b>même</b> benchmark tenu à l'écart.
 *
 * <p>Lecture type — chaque bras n'active qu'un enrichissement de plus que le précédent, de sorte
 * que le delta des champs entre deux bras donne le gain marginal de cet enrichissement (et son
 * coût en latence). Permet de mesurer les deux axes demandés : gain du RAG (bras {@code useRag}
 * false vs true) et gain du fine-tuning (bras modèle de base vs fine-tuné).</p>
 */
public record AblationReport(
        List<AblationArmReport> arms,
        int benchmarkSize,
        Instant startedAt,
        Instant completedAt
) {}
