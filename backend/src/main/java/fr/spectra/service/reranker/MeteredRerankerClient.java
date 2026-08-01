package fr.spectra.service.reranker;

import fr.spectra.dto.ServiceStatus;
import fr.spectra.service.RerankerClient;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Décorateur d'instrumentation autour du moteur de reranking actif.
 *
 * <p><b>Pourquoi un décorateur plutôt qu'instrumenter les deux implémentations.</b> C'est ce qui
 * garantit des métriques <i>identiques</i> sur le moteur HTTP et sur le moteur ONNX — condition
 * pour que la comparaison avant/après la bascule ait un sens. Instrumenter chacune séparément,
 * c'est risquer deux définitions de « latence » (avec ou sans sérialisation, avec ou sans
 * tokenisation) qu'on comparerait ensuite sans savoir qu'elles diffèrent.
 *
 * <p><b>Pourquoi maintenant.</b> Le service Python exposait ses propres métriques via
 * {@code prometheus-fastapi-instrumentator} ; le moteur ONNX est arrivé sans aucune. Basculer en
 * l'état échangeait un composant observé contre un composant muet (constat P15 de
 * {@code docs/process/audit-python-java.fr.md}). La mesure de parité d'ordre ayant par ailleurs
 * été écartée, ces compteurs sont désormais le <b>seul</b> signal indiquant que le reranking
 * s'exécute et n'échoue pas en silence.
 *
 * <p><b>Les exceptions traversent.</b> Une métrique qui avalerait l'échec transformerait le repli
 * de {@code RagService} — ordre vectoriel, {@code rerankApplied=false} — en résultat silencieux
 * qui fausserait benchmarks et ablations. Le compteur d'échec est incrémenté <i>et</i>
 * l'exception relancée.
 */
public class MeteredRerankerClient implements RerankerClient {

    /** Nombre d'appels, ventilé par moteur et par issue. */
    static final String REQUESTS = "spectra.reranker.requests.total";
    /** Latence d'un appel complet, quelle que soit son issue. */
    static final String DURATION = "spectra.reranker.duration";
    /** Taille des lots réellement soumis — c'est elle qui conditionne la latence. */
    static final String DOCUMENTS = "spectra.reranker.documents";

    private final RerankerClient delegate;
    private final MeterRegistry registry;
    private final String engine;

    public MeteredRerankerClient(RerankerClient delegate, MeterRegistry registry, String engine) {
        this.delegate = delegate;
        this.registry = registry;
        this.engine = engine;
    }

    @Override
    public List<RankedResult> rerank(String query, List<String> documents, int topN) {
        DistributionSummary.builder(DOCUMENTS).tag("engine", engine).register(registry)
                .record(documents == null ? 0 : documents.size());

        long startedAt = System.nanoTime();
        try {
            List<RankedResult> ranked = delegate.rerank(query, documents, topN);
            record(startedAt, "success");
            return ranked;
        } catch (RuntimeException e) {
            record(startedAt, "failure");
            throw e;   // cf. javadoc : le repli de RagService dépend de cette propagation
        }
    }

    private void record(long startedAt, String outcome) {
        // La latence porte le tag d'issue elle aussi : un échec rapide et un succès lent ne
        // doivent pas se moyenner en une valeur qui ne décrit ni l'un ni l'autre.
        Timer.builder(DURATION).tag("engine", engine).tag("outcome", outcome).register(registry)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        registry.counter(REQUESTS, "engine", engine, "outcome", outcome).increment();
    }

    /**
     * Délégué <b>sans instrumentation</b> : appelé sur le chemin de requête pour décider s'il faut
     * sur-extraire des candidats, il doit rester aussi bon marché que l'implémentation sous-jacente.
     * Le manquer romprait le garde-fou qui rend l'activation par défaut du reranking sûre.
     */
    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    /** Délégué tel quel : {@code /api/status} doit voir le moteur réel, pas le décorateur. */
    @Override
    public ServiceStatus checkHealth() {
        return delegate.checkHealth();
    }
}
