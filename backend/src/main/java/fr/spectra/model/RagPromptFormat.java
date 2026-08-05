package fr.spectra.model;

import java.util.List;

/**
 * Forme <b>exacte</b> du prompt servi en RAG : persona, consignes de citation, bloc de contexte
 * numéroté.
 *
 * <p>Source de vérité unique, et c'est tout son intérêt. Cette mise en forme vivait uniquement
 * dans {@code RagService} ; le dataset de fine-tuning, lui, était construit avec la persona seule
 * et une question nue. Le modèle était donc affiné sur des prompts courts sans contexte, puis
 * servi avec des prompts longs saturés de contexte et assortis d'une consigne de citation qu'il
 * n'avait jamais vue à l'entraînement (constat <b>F14</b> de
 * {@code docs/process/audit-finetuning.fr.md}).
 *
 * <p>Le défaut n'est pas seulement un écart de distribution : le SFT enseignait activement à
 * répondre <i>de mémoire, sans citer</i>, alors que le service exige de répondre <i>à partir du
 * contexte, en citant</i>. Le fine-tuning travaillait contre l'objectif de service.
 *
 * <p>Partager la mise en forme entre les deux est la seule garantie qu'elles restent d'accord —
 * et la même leçon que les évènements structurés du trainer : un format implicite dupliqué en
 * deux endroits finit toujours par diverger.
 */
public final class RagPromptFormat {

    /**
     * Consignes RAG et emplacement du bloc de contexte ({@code %s}). Déplacé depuis
     * {@code RagService} sans modification : ce qui est servi ne change pas, c'est
     * l'entraînement qui vient s'y aligner.
     */
    public static final String INSTRUCTIONS_TEMPLATE = """
            Réponds de manière précise et concise en te basant UNIQUEMENT sur le contexte fourni ci-dessous.
            Chaque passage du contexte est numéroté [1], [2], … : cite tes sources en insérant le numéro
            correspondant entre crochets juste après l'information qu'il justifie (ex. « la valeur par défaut
            est 512 [3]. »). N'invente jamais de numéro et ne cite que des passages réellement présents.
            Si le contexte ne contient pas l'information demandée, dis-le clairement.
            Ne fabrique pas d'information.

            === CONTEXTE ===
            %s
            === FIN DU CONTEXTE ===""";

    /** Un passage du contexte, tel qu'il est présenté au modèle. */
    public record Passage(String sourceFile, String text) {}

    private RagPromptFormat() {
    }

    /**
     * Rend un passage numéroté. La numérotation est <b>1-basée</b> et suit l'ordre de la liste
     * des sources renvoyée au client, pour que les citations {@code [n]} de la réponse soient
     * résolubles vers le bon extrait.
     */
    public static String passage(int number, String sourceFile, String text) {
        return "[" + number + "] (Source: " + (sourceFile == null ? "inconnu" : sourceFile) + ")\n"
                + text + "\n\n";
    }

    /**
     * Assemble un bloc de contexte complet à partir de passages ordonnés.
     *
     * @return {@code null} si la liste est vide — c'est ce que le service utilise pour basculer
     *         en mode direct, et le bloc vide ne doit pas être confondu avec un contexte présent
     *         mais sans contenu.
     */
    public static String contextBlock(List<Passage> passages) {
        if (passages == null || passages.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < passages.size(); i++) {
            sb.append(passage(i + 1, passages.get(i).sourceFile(), passages.get(i).text()));
        }
        return sb.toString();
    }

    /**
     * Prompt système complet : persona du modèle, puis consignes et contexte.
     *
     * <p>La persona est un paramètre et non {@link AssistantPersona#SYSTEM_PROMPT} : au service,
     * elle provient du modèle actif, pour qu'un modèle personnalisé soit servi sous SA propre
     * identité. À l'entraînement, c'est bien la persona canonique qui est utilisée — un modèle
     * issu du fine-tuning s'enregistre précisément avec elle.
     */
    public static String systemPrompt(String persona, String contextBlock) {
        return persona + "\n" + String.format(INSTRUCTIONS_TEMPLATE, contextBlock);
    }
}
