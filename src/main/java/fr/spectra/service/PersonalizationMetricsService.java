package fr.spectra.service;

import fr.spectra.dto.EvaluationReport;
import fr.spectra.dto.FineTuningJob;
import fr.spectra.persistence.ArticleCommentEntity;
import fr.spectra.persistence.ArticleCommentRepository;
import fr.spectra.service.dataset.DpoGenerationService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agrège les métriques du cycle de personnalisation continue :
 * commentaires approuvés → paires DPO → fine-tuning → évaluation.
 */
@Service
public class PersonalizationMetricsService {

    private final ArticleCommentRepository commentRepo;
    private final DpoGenerationService dpoService;
    private final FineTuningService fineTuningService;
    private final EvaluationService evaluationService;
    private final ArticleCommentService commentService;

    public PersonalizationMetricsService(ArticleCommentRepository commentRepo,
                                          DpoGenerationService dpoService,
                                          FineTuningService fineTuningService,
                                          EvaluationService evaluationService,
                                          ArticleCommentService commentService) {
        this.commentRepo = commentRepo;
        this.dpoService = dpoService;
        this.fineTuningService = fineTuningService;
        this.evaluationService = evaluationService;
        this.commentService = commentService;
    }

    public PersonalizationMetrics getMetrics() {
        long approved = commentRepo.countByCommentTypeAndRating(
                ArticleCommentEntity.CommentType.AI_GENERATED,
                ArticleCommentEntity.Rating.APPROVED);
        long rejected = commentRepo.countByCommentTypeAndRating(
                ArticleCommentEntity.CommentType.AI_GENERATED,
                ArticleCommentEntity.Rating.REJECTED);
        long total = commentRepo.countByCommentTypeAndRating(
                ArticleCommentEntity.CommentType.AI_GENERATED,
                ArticleCommentEntity.Rating.NONE)
                + approved + rejected;

        int dpoPairs = dpoService.getAllPairs().size();
        List<FineTuningJob> jobs = fineTuningService.getAllJobs();
        List<EvaluationReport> evals = evaluationService.getAllReports();

        int threshold = commentService.getAutoRetrainThreshold();
        long completedCycles = threshold > 0 ? approved / threshold : 0;
        long nextTriggerIn = threshold > 0 ? threshold - (approved % threshold) : -1;

        long completedJobs = jobs.stream()
                .filter(j -> j.status() == FineTuningJob.Status.COMPLETED).count();
        double latestEvalScore = evals.stream()
                .filter(r -> "COMPLETED".equals(r.status()))
                .mapToDouble(EvaluationReport::averageScore)
                .max().orElse(-1.0);

        return new PersonalizationMetrics(
                approved, rejected, total, dpoPairs,
                jobs, evals,
                completedCycles, nextTriggerIn, threshold,
                completedJobs, latestEvalScore
        );
    }

    public record PersonalizationMetrics(
            long approvedComments,
            long rejectedComments,
            long totalAiComments,
            int dpoPairs,
            List<FineTuningJob> fineTuningJobs,
            List<EvaluationReport> evaluations,
            long completedCycles,
            long nextTriggerIn,
            int autoRetrainThreshold,
            long completedFineTuningJobs,
            double latestEvalScore
    ) {}
}
