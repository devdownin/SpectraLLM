package fr.spectra.model;

/**
 * Paire DPO (Direct Preference Optimization) au format standard TRL.
 *
 * <ul>
 *   <li>{@code prompt}   — question + contexte système (messages user/system concaténés)</li>
 *   <li>{@code chosen}   — réponse correcte tirée du dataset SFT</li>
 *   <li>{@code rejected} — réponse plausible mais factuellement incorrecte (générée par LLM)</li>
 * </ul>
 */
public record DpoPair(
        String prompt,
        String chosen,
        String rejected,
        String category,
        String source
) {}
