package fr.spectra.service.reranker;

import fr.spectra.config.SpectraProperties;
import fr.spectra.service.CrossEncoderRerankerClient;
import fr.spectra.service.RerankerClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Enveloppe le moteur de reranking actif dans {@link MeteredRerankerClient}.
 *
 * <p><b>Pourquoi les deux moteurs sont nommés explicitement</b> plutôt qu'injectés par leur
 * interface commune. Un {@code @Bean} de type {@code RerankerClient} qui recevrait un
 * {@code RerankerClient} en paramètre dépendrait de l'exclusion des auto-références par Spring —
 * un comportement réel, mais implicite, et que ce dépôt ne sait pas vérifier à moindre coût
 * (aucun test de contexte Spring n'existe ici). Deux {@link ObjectProvider} de types concrets
 * lèvent l'ambiguïté sans rien devoir aux règles de résolution.
 *
 * <p>Les deux implémentations s'excluent mutuellement par {@code @ConditionalOnExpression} sur
 * {@code spectra.reranker.engine} : il y en a au plus une. Une troisième un jour ajouterait une
 * ligne ici — coût assumé, en échange d'un câblage lisible.
 *
 * <p>Sans moteur actif, aucun bean n'est créé : les consommateurs injectent
 * {@code Optional<RerankerClient>} et voient un {@code Optional.empty()}, exactement comme avant.
 */
@Configuration
public class RerankerMetricsConfig {

    @Bean
    @Primary
    public RerankerClient meteredRerankerClient(ObjectProvider<OnnxCrossEncoderReranker> onnx,
                                                ObjectProvider<CrossEncoderRerankerClient> http,
                                                MeterRegistry registry,
                                                SpectraProperties props) {
        RerankerClient delegate = onnx.getIfAvailable();
        if (delegate == null) {
            delegate = http.getIfAvailable();
        }
        if (delegate == null) {
            return null;   // aucun moteur configuré : pas de bean, pas de métriques
        }
        return new MeteredRerankerClient(delegate, registry, engineTag(props));
    }

    /**
     * Étiquette du moteur, lue dans la configuration plutôt que déduite de la classe : c'est la
     * valeur qui a réellement sélectionné l'implémentation, donc celle qu'un tableau de bord doit
     * afficher pour être comparable à ce que l'exploitant a écrit dans son {@code .env}.
     */
    private static String engineTag(SpectraProperties props) {
        return props != null && props.reranker() != null
                ? props.reranker().effectiveEngine()
                : "unknown";
    }
}
