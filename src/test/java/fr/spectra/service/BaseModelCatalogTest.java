package fr.spectra.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Catalogue des modèles de base : chargement du manifeste unique {@code base_models.json}
 * (partagé avec les scripts Python) et résolution alias → repo HuggingFace.
 *
 * <p>Verrou anti-régression : les alias historiques (tinyllama, phi3, mistral, llama3)
 * doivent rester résolus vers les MÊMES repos que ceux sur lesquels les adaptateurs LoRA
 * existants ont été entraînés — changer un repo casserait la fusion de ces adaptateurs.</p>
 */
class BaseModelCatalogTest {

    private final BaseModelCatalog catalog = new BaseModelCatalog();

    @Test
    void manifeste_chargeLesQuatreAliasHistoriques() {
        assertThat(catalog.list()).extracting(m -> m.get("alias"))
                .containsExactly("tinyllama", "phi3", "mistral", "llama3");
    }

    @Test
    void resolveHfRepo_aliasHistoriques_reposInchanges() {
        assertThat(catalog.resolveHfRepo("tinyllama")).isEqualTo("TinyLlama/TinyLlama-1.1B-Chat-v1.0");
        assertThat(catalog.resolveHfRepo("phi3")).isEqualTo("microsoft/Phi-3-mini-4k-instruct");
        assertThat(catalog.resolveHfRepo("mistral")).isEqualTo("mistralai/Mistral-7B-Instruct-v0.3");
        assertThat(catalog.resolveHfRepo("llama3")).isEqualTo("meta-llama/Meta-Llama-3-8B-Instruct");
    }

    @Test
    void resolveHfRepo_repoHuggingFaceComplet_accepteTelQuel() {
        assertThat(catalog.resolveHfRepo("Qwen/Qwen2.5-1.5B-Instruct"))
                .isEqualTo("Qwen/Qwen2.5-1.5B-Instruct");
    }

    @Test
    void resolveHfRepo_aliasInconnu_rejetteAvecLesAliasDisponibles() {
        assertThatThrownBy(() -> catalog.resolveHfRepo("phi-4-mini"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phi-4-mini")
                .hasMessageContaining("phi3");
    }

    @Test
    void resolveHfRepo_valeurVide_rejette() {
        assertThatThrownBy(() -> catalog.resolveHfRepo("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void find_exposeContexteEtDescription() {
        BaseModelCatalog.BaseModel phi3 = catalog.find("phi3").orElseThrow();
        assertThat(phi3.contextLength()).isEqualTo(4096);
        assertThat(phi3.description()).isNotBlank();
    }
}
