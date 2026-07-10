package fr.spectra.model;

/**
 * Source de vérité unique du <b>system prompt de persona</b> de l'assistant.
 *
 * <p>Cohérence entraînement ↔ service : le modèle fine-tuné apprend à répondre sous cette
 * persona précise (cf. {@link TrainingPair#of}). Servir le modèle avec un system prompt
 * différent dégrade le bénéfice du fine-tuning. Cette constante est donc partagée par la
 * génération du dataset, le service RAG (mode direct et préfixe du prompt contextuel),
 * l'enregistrement du modèle et l'évaluation.</p>
 */
public final class AssistantPersona {

    /** Persona canonique. Doit rester identique à celle servie en production. */
    public static final String SYSTEM_PROMPT =
            "Tu es un assistant spécialisé dans l'exploitation autoroutière.";

    private AssistantPersona() {
    }
}
