package fr.spectra.service;

import fr.spectra.dto.FineTuningRequest;
import fr.spectra.model.DpoPair;
import fr.spectra.persistence.ArticleCommentEntity;
import fr.spectra.persistence.ArticleCommentRepository;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.dataset.DpoGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ré-entraînement automatique déclenché par les commentaires IA approuvés.
 *
 * <p>Le point vérifié ici est le <b>câblage</b> : les paires issues des commentaires doivent
 * atteindre le registre que l'entraînement lit réellement
 * ({@link DpoGenerationService#getAllPairs()}, consommé par
 * {@code FineTuningService.exportDpoDataset}). Le service se contentait d'écrire
 * {@code comments_dpo.jsonl}, un fichier qu'aucun entraînement n'ouvre : le job partait sur des
 * paires sans rapport avec les commentaires déclencheurs — voire échouait faute de paires.</p>
 */
class ArticleCommentAutoRetrainTest {

    @TempDir
    Path tempDatasetDir;

    private ArticleCommentRepository commentRepo;
    private FineTuningService fineTuningService;
    private DpoGenerationService dpoGenerator;
    private ArticleCommentService service;

    @BeforeEach
    void setUp() {
        commentRepo = mock(ArticleCommentRepository.class);
        fineTuningService = mock(FineTuningService.class);
        dpoGenerator = mock(DpoGenerationService.class);
        service = new ArticleCommentService(
                commentRepo, mock(IngestedFileRepository.class), mock(RagService.class),
                mock(LlmChatClient.class), fineTuningService, dpoGenerator,
                tempDatasetDir.toString(), 5);
    }

    private static ArticleCommentEntity comment(String content, String focus) {
        return new ArticleCommentEntity("sha-doc", content, "tester",
                ArticleCommentEntity.CommentType.AI_GENERATED, null, focus);
    }

    /** Un APPROVED et un REJECTED sur le même focus → une paire {prompt, chosen, rejected}. */
    private void givenOneApprovedAndOneRejectedComment() {
        when(commentRepo.findByCommentTypeAndRating(
                ArticleCommentEntity.CommentType.AI_GENERATED,
                ArticleCommentEntity.Rating.APPROVED))
                .thenReturn(List.of(comment("Analyse correcte et ancrée dans le document.", "focus-1")));
        when(commentRepo.findByCommentTypeAndRating(
                ArticleCommentEntity.CommentType.AI_GENERATED,
                ArticleCommentEntity.Rating.REJECTED))
                .thenReturn(List.of(comment("Résumé faux, chiffres inventés, hors sujet.", "focus-1")));
    }

    @Test
    void lesPairesIssuesDesCommentairesSontEnregistreesDansLeRegistreLuParLEntrainement() {
        givenOneApprovedAndOneRejectedComment();
        when(dpoGenerator.addPreferencePair(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DpoPair("p", "c", "r", "article_comment", "src"));
        when(fineTuningService.submit(any())).thenReturn("job-1");

        service.triggerRetraining(5);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> chosen = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rejected = ArgumentCaptor.forClass(String.class);
        verify(dpoGenerator).addPreferencePair(
                prompt.capture(), chosen.capture(), rejected.capture(), anyString());

        assertThat(prompt.getValue()).isEqualTo("focus-1");
        assertThat(chosen.getValue()).isEqualTo("Analyse correcte et ancrée dans le document.");
        assertThat(rejected.getValue()).isEqualTo("Résumé faux, chiffres inventés, hors sujet.");
    }

    @Test
    void leJobSoumisEstAligneDpoEtProduitUnModeleDeployable() {
        givenOneApprovedAndOneRejectedComment();
        when(dpoGenerator.addPreferencePair(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DpoPair("p", "c", "r", "article_comment", "src"));
        when(fineTuningService.submit(any())).thenReturn("job-1");

        service.triggerRetraining(5);

        ArgumentCaptor<FineTuningRequest> request = ArgumentCaptor.forClass(FineTuningRequest.class);
        verify(fineTuningService).submit(request.capture());

        assertThat(request.getValue().dpoEnabled()).isTrue();
        // Sans export GGUF, l'adaptateur produit n'est ni converti ni enregistré : la boucle
        // d'apprentissage continu ne mettrait jamais rien en service.
        assertThat(request.getValue().exportGguf()).isTrue();
    }

    @Test
    void aucunJobSoumisQuandToutesLesPairesSontDejaEnregistrees() {
        givenOneApprovedAndOneRejectedComment();
        // addPreferencePair renvoie null pour un doublon : rien de nouveau à apprendre.
        when(dpoGenerator.addPreferencePair(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        service.triggerRetraining(10);

        verify(fineTuningService, never()).submit(any());
    }

    @Test
    void aucunJobSoumisEtAucuneErreurQuandAucunCommentaireApprouve() {
        when(commentRepo.findByCommentTypeAndRating(
                ArticleCommentEntity.CommentType.AI_GENERATED,
                ArticleCommentEntity.Rating.APPROVED))
                .thenReturn(List.of());

        assertThatCode(() -> service.triggerRetraining(5)).doesNotThrowAnyException();

        verify(dpoGenerator, never()).addPreferencePair(anyString(), anyString(), anyString(), anyString());
        verify(fineTuningService, never()).submit(any());
    }

    /**
     * {@code submit()} renvoie {@code null} quand un entraînement tourne déjà (409). Le service
     * journalisait ce {@code null} comme un succès ; il doit désormais l'absorber sans échouer,
     * les paires restant enregistrées pour le prochain déclenchement.
     */
    @Test
    void unEntrainementDejaEnCoursNeFaitPasEchouerLeDeclenchement() {
        givenOneApprovedAndOneRejectedComment();
        when(dpoGenerator.addPreferencePair(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DpoPair("p", "c", "r", "article_comment", "src"));
        when(fineTuningService.submit(any())).thenReturn(null);

        assertThatCode(() -> service.triggerRetraining(5)).doesNotThrowAnyException();

        verify(dpoGenerator).addPreferencePair(anyString(), anyString(), anyString(), anyString());
    }
}
