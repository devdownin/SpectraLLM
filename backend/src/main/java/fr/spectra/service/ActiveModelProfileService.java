package fr.spectra.service;

import fr.spectra.dto.QueryRequest;
import fr.spectra.model.AssistantPersona;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Profil d'inférence du <b>modèle de chat actif</b>, tel qu'il est décrit dans le registre
 * ({@link ModelRegistryService}) : sa persona ({@code systemPrompt}) et ses paramètres de
 * génération ({@code parameters}).
 *
 * <p><b>Pourquoi ce bean.</b> Le registre persiste depuis toujours un {@code systemPrompt} et
 * une carte {@code parameters} par modèle (fine-tuning, llmfit, {@code POST
 * /api/fine-tuning/models/register}), mais ces champs n'étaient que de la traçabilité : le RAG
 * servait une persona figée dans le code et les paramètres du déploiement. Un modèle
 * personnalisé était donc servi sous une identité qui n'était pas la sienne. Ce bean fait le
 * pont entre ce qui est <i>enregistré</i> et ce qui est <i>appliqué</i> à la génération.</p>
 *
 * <p><b>Précédence des paramètres.</b> Une valeur explicitement portée par la requête (curseurs
 * du Playground, harnais d'ablation) prime toujours ; les paramètres du modèle ne sont que ses
 * <i>défauts</i> ; à défaut des deux, les constantes Spectra s'appliquent. Un modèle enregistré
 * ne peut donc jamais confisquer le réglage demandé par l'appelant.</p>
 */
@Service
public class ActiveModelProfileService {

    /** Température par défaut quand ni la requête ni le modèle actif n'en imposent une. */
    public static final float DEFAULT_TEMPERATURE = 0.7f;
    /** Top-P par défaut quand ni la requête ni le modèle actif n'en imposent un. */
    public static final float DEFAULT_TOP_P = 0.9f;

    private final ModelRegistryService modelRegistry;

    public ActiveModelProfileService(ModelRegistryService modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    /**
     * Instantané du profil du modèle de chat actif. À résoudre <b>une fois par requête</b>, en
     * amont de la génération : le modèle actif peut basculer entre deux requêtes, et un même
     * passage RAG doit rester cohérent (persona et paramètres du même modèle).
     */
    public ModelProfile current() {
        String alias = modelRegistry.getActiveChatModel();
        return modelRegistry.getModel(alias, "chat")
                .map(model -> new ModelProfile(
                        alias,
                        blankToNull(model.systemPrompt()),
                        floatParam(model.parameters(), "temperature"),
                        floatParam(model.parameters(), "top_p", "topP")))
                .orElseGet(() -> new ModelProfile(alias, null, null, null));
    }

    /**
     * Profil résolu d'un modèle actif.
     *
     * @param alias       alias du modèle actif dans le registre
     * @param persona     {@code systemPrompt} enregistré, ou {@code null} si le modèle n'en
     *                    déclare pas — la persona canonique Spectra s'applique alors
     * @param temperature température enregistrée, ou {@code null}
     * @param topP        top-P enregistré, ou {@code null}
     */
    public record ModelProfile(String alias, String persona, Float temperature, Float topP) {

        /** Profil neutre : persona canonique et paramètres par défaut (aucun modèle résolu). */
        public static final ModelProfile DEFAULT = new ModelProfile(null, null, null, null);

        /**
         * Persona à servir : celle du modèle enregistré, sinon la persona canonique Spectra
         * ({@link AssistantPersona#SYSTEM_PROMPT}) — cohérence entraînement ↔ service pour les
         * modèles issus du fine-tuning, qui enregistrent précisément cette persona.
         */
        public String personaOrDefault() {
            return persona != null ? persona : AssistantPersona.SYSTEM_PROMPT;
        }

        /** Température effective : requête &gt; modèle actif &gt; défaut Spectra. */
        public float resolveTemperature(Float requested) {
            if (requested != null) {
                return requested;
            }
            return temperature != null ? temperature : DEFAULT_TEMPERATURE;
        }

        /** Top-P effectif : requête &gt; modèle actif &gt; défaut Spectra. */
        public float resolveTopP(Float requested) {
            if (requested != null) {
                return requested;
            }
            return topP != null ? topP : DEFAULT_TOP_P;
        }

        /**
         * Fige les paramètres de génération de la requête sur leurs valeurs effectives.
         *
         * <p>Appliqué à l'entrée du pipeline RAG, il rend le reste de la chaîne (Self-RAG,
         * Agentic, streaming) insensible à la résolution : tout le monde lit une requête dont
         * {@code temperature}/{@code topP} sont déjà arbitrés, sans avoir à connaître le
         * registre ni à retomber sur ses propres défauts.</p>
         */
        public QueryRequest applyTo(QueryRequest request) {
            return request.withGenerationParams(
                    resolveTemperature(request.temperature()),
                    resolveTopP(request.topP()));
        }
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    /**
     * Lit un paramètre numérique du registre sous l'un des alias donnés. Les paramètres sont
     * une carte libre ({@code jobId}, {@code baseModel}… y côtoient les réglages de
     * génération) et peuvent avoir été désérialisés en {@link Number} comme en {@link String}
     * selon l'écrivain : une valeur absente, non numérique ou illisible est simplement ignorée
     * plutôt que de faire échouer la requête.
     */
    private static Float floatParam(Map<String, Object> parameters, String... keys) {
        if (parameters == null || parameters.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = parameters.get(key);
            if (value instanceof Number number) {
                return number.floatValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                try {
                    return Float.parseFloat(text.trim());
                } catch (NumberFormatException ignored) {
                    // Paramètre non numérique : on retombe sur le défaut.
                }
            }
        }
        return null;
    }
}
