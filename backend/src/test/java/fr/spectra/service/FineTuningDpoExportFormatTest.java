package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.model.AssistantPersona;
import fr.spectra.model.DpoPair;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Format d'export des paires de préférence consommé par TRL (DPO/ORPO).
 *
 * <p>Seul le format <b>conversationnel</b> — {@code prompt}/{@code chosen}/{@code rejected} sous
 * forme de listes de messages — fait appliquer par TRL le gabarit du modèle de base, donc la
 * même mise en forme que le SFT et que le service. L'export sérialisait auparavant le
 * {@code DpoPair} tel quel : un prompt en texte brut, sans marqueur de rôle, auquel TRL
 * n'applique aucun gabarit — une troisième convention, ni apprise en SFT ni servie.</p>
 */
class FineTuningDpoExportFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode exported(DpoPair pair) throws Exception {
        Map<String, Object> row = FineTuningService.toConversationalPair(pair);
        return MAPPER.readTree(MAPPER.writeValueAsString(row));
    }

    private static DpoPair pair(String prompt) {
        return new DpoPair(prompt, "Bonne réponse.", "Mauvaise réponse.", "qa", "doc.pdf");
    }

    @Test
    void lePromptEstUneConversationSystemePuisUtilisateur() throws Exception {
        JsonNode row = exported(pair("Quelle est la procédure ?"));

        assertThat(row.get("prompt").isArray()).isTrue();
        assertThat(row.get("prompt")).hasSize(2);
        assertThat(row.get("prompt").get(0).get("role").asText()).isEqualTo("system");
        assertThat(row.get("prompt").get(0).get("content").asText())
                .isEqualTo(AssistantPersona.SYSTEM_PROMPT);
        assertThat(row.get("prompt").get(1).get("role").asText()).isEqualTo("user");
        assertThat(row.get("prompt").get(1).get("content").asText())
                .isEqualTo("Quelle est la procédure ?");
    }

    @Test
    void chosenEtRejectedSontDesToursAssistant() throws Exception {
        JsonNode row = exported(pair("Question ?"));

        assertThat(row.get("chosen")).hasSize(1);
        assertThat(row.get("chosen").get(0).get("role").asText()).isEqualTo("assistant");
        assertThat(row.get("chosen").get(0).get("content").asText()).isEqualTo("Bonne réponse.");
        assertThat(row.get("rejected").get(0).get("role").asText()).isEqualTo("assistant");
        assertThat(row.get("rejected").get(0).get("content").asText()).isEqualTo("Mauvaise réponse.");
    }

    /**
     * Les paires produites avant ce changement portent la persona concaténée en tête du prompt.
     * Sans normalisation, elle apparaîtrait deux fois : en message système ET dans la question.
     */
    @Test
    void laPersonaConcateneeDUneAnciennePaireNEstPasDupliquee() throws Exception {
        JsonNode row = exported(pair(AssistantPersona.SYSTEM_PROMPT + "\n\nQuelle procédure ?"));

        assertThat(row.get("prompt").get(1).get("content").asText()).isEqualTo("Quelle procédure ?");
        assertThat(row.get("prompt").get(0).get("content").asText())
                .isEqualTo(AssistantPersona.SYSTEM_PROMPT);
    }

    @Test
    void seulesLesTroisClesAttenduesParTrlSontEmises() throws Exception {
        JsonNode row = exported(pair("Question ?"));

        assertThat(row.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("prompt", "chosen", "rejected");
    }

    @Test
    void unPromptNulNeFaitPasEchouerLExport() throws Exception {
        JsonNode row = exported(new DpoPair(null, "ok", "ko", "qa", "doc.pdf"));

        assertThat(row.get("prompt").get(1).get("content").asText()).isEmpty();
    }
}
