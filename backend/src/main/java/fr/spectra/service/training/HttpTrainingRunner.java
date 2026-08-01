package fr.spectra.service.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Exécute l'entraînement dans le service {@code spectra-trainer}, par HTTP.
 *
 * <p><b>Ce que ce runner apporte.</b> Le mode hôte ({@link ProcessTrainingRunner}) exige que
 * {@code scripts/} et Python soient présents <i>à côté de la JVM</i> — ce qui n'est jamais le cas
 * en conteneur, l'image {@code spectra-api} étant une JRE nue. Déporter l'exécution dans une
 * image dédiée rend le fine-tuning disponible sous {@code docker compose}, sans réintroduire
 * Python dans l'application.
 *
 * <p><b>Pourquoi {@link HttpClient} du JDK et non {@code WebClient}</b>, à rebours du reste du
 * dépôt. Deux exigences que ce cas pose et qu'aucun autre client n'a :
 * <ul>
 *   <li><b>découpage en lignes fidèle</b> — {@code BodyHandlers.ofLines()} rend exactement les
 *       lignes du script, là où un flux de {@code DataBuffer} exigerait de recomposer les
 *       frontières à la main. La progression et la diffusion SSE en dépendent ;</li>
 *   <li><b>aucun délai de lecture</b> — un entraînement dure des heures. Un
 *       {@code responseTimeout} hérité couperait le flux au milieu, et le job serait déclaré
 *       échoué alors qu'il tourne encore.</li>
 * </ul>
 *
 * <p><b>Le code de sortie voyage dans le corps</b>, en dernière ligne
 * ({@value #EXIT_PREFIX}{@code <code>}), parce qu'un statut HTTP est émis avant que le script
 * n'ait commencé. <b>Une absence de sentinelle vaut échec</b> : elle signale une interruption
 * (conteneur tué, connexion rompue), et la traiter comme un succès enregistrerait un modèle
 * jamais entraîné.
 *
 * <p>Actif lorsque {@code spectra.fine-tuning.runner=http}.
 */
@Component
@ConditionalOnProperty(prefix = "spectra.fine-tuning", name = "runner", havingValue = "http")
public class HttpTrainingRunner implements TrainingRunner {

    private static final Logger log = LoggerFactory.getLogger(HttpTrainingRunner.class);

    /** Doit rester identique à {@code EXIT_PREFIX} de {@code services/trainer/app.py}. */
    static final String EXIT_PREFIX = "__EXIT__:";

    /**
     * Version du contrat de transport attendue du service — forme des requêtes, nom des champs,
     * et convention de la sentinelle de sortie.
     *
     * <p><b>Pourquoi une vérification stricte.</b> Les deux moitiés du contrat vivent dans deux
     * langages et deux images, versionnées séparément. Rien n'empêche une image de trainer
     * ancienne d'être servie à une API récente : la requête partirait, l'entraînement
     * démarrerait, et l'incompatibilité se manifesterait au premier champ mal interprété —
     * après avoir consommé des heures de calcul. Refuser au moment de la soumission déplace
     * l'échec là où il coûte le moins.
     *
     * <p>Une réponse sans {@code protocolVersion} est traitée comme incompatible : c'est la
     * signature d'un service antérieur au versionnement, dont on ne peut rien affirmer.
     *
     * <p>À incrémenter <b>de part et d'autre</b>, et uniquement sur un changement incompatible.
     */
    static final int PROTOCOL_VERSION = 1;

    /** Le contrôle de santé, lui, doit être bref : il est sur le chemin d'une soumission. */
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public HttpTrainingRunner(
            @Value("${spectra.fine-tuning.trainer-url:http://trainer:8000}") String baseUrl,
            ObjectMapper mapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
        // HTTP/1.1 explicite : uvicorn (h11) ne sert que de l'HTTP/1.1. Laisser le défaut
        // HTTP_2 ferait joindre à chaque requête une tentative d'upgrade qui n'aboutira jamais —
        // sans casse, mais sans utilité non plus. Autant l'annoncer.
        //
        // connectTimeout seulement : jamais de délai de LECTURE, cf. javadoc de la classe.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(HEALTH_TIMEOUT)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return unavailabilityReason() == null;
    }

    @Override
    public String unavailabilityReason() {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                            .timeout(HEALTH_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                return "service d'entraînement joignable mais en erreur (HTTP "
                        + response.statusCode() + ") sur " + baseUrl;
            }
            Map<?, ?> body = mapper.readValue(response.body(), Map.class);

            Object reported = body.get("protocolVersion");
            int version = reported instanceof Number n ? n.intValue() : -1;
            if (version != PROTOCOL_VERSION) {
                return "service d'entraînement incompatible sur " + baseUrl + " : il parle la "
                        + "version " + (version < 0 ? "(non déclarée)" : String.valueOf(version))
                        + " du protocole, cette API attend la version " + PROTOCOL_VERSION
                        + ". Reconstruisez l'image du trainer depuis la même version du dépôt "
                        + "(docker compose --profile trainer build trainer).";
            }

            // Un trainer joignable mais sans ses scripts est indisponible. Le dire ici évite
            // d'accepter un job qui échouerait dès la première commande.
            if (!Boolean.TRUE.equals(body.get("trainScript"))) {
                return "service d'entraînement joignable sur " + baseUrl + ", mais son script "
                        + "d'entraînement est absent — l'image du trainer est incomplète.";
            }
            return null;
        } catch (Exception e) {
            return "service d'entraînement injoignable sur " + baseUrl + " (" + e.getMessage()
                    + "). Démarrez-le avec le profil compose « trainer », ou repassez en mode "
                    + "hôte avec spectra.fine-tuning.runner=process.";
        }
    }

    @Override
    public int train(TrainingSpec spec, Consumer<String> onLine, BooleanSupplier cancelled)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", spec.jobId());
        body.put("datasetFile", spec.datasetFile().toAbsolutePath().toString());
        body.put("adapterPath", spec.adapterPath().toAbsolutePath().toString());
        body.put("baseHfRepo", spec.baseHfRepo());
        body.put("loraRank", spec.loraRank());
        body.put("loraAlpha", spec.loraAlpha());
        body.put("epochs", spec.epochs());
        body.put("learningRate", spec.learningRate());
        body.put("packing", spec.packing());
        body.put("dpo", spec.dpo());
        body.put("orpo", spec.orpo());
        body.put("valSplit", spec.valSplit());
        return stream("/train", spec.jobId(), body, onLine, cancelled);
    }

    @Override
    public int exportGguf(ExportSpec spec, Consumer<String> onLine, BooleanSupplier cancelled)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", spec.jobId());
        body.put("adapterPath", spec.adapterPath().toAbsolutePath().toString());
        body.put("outputDir", spec.outputDir().toAbsolutePath().toString());
        body.put("modelName", spec.modelName());
        body.put("baseHfRepo", spec.baseHfRepo());
        return stream("/export", spec.jobId(), body, onLine, cancelled);
    }

    @Override
    public boolean cancel(String jobId) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/cancel/" + jobId))
                            .timeout(HEALTH_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return false;
            }
            return Boolean.TRUE.equals(mapper.readValue(response.body(), Map.class).get("cancelled"));
        } catch (Exception e) {
            // L'annulation est au mieux consultative : le job local passe FAILED de toute façon.
            log.warn("Job {} : annulation non transmise au trainer — {}", jobId, e.getMessage());
            return false;
        }
    }

    /**
     * Poste la requête et consomme la réponse ligne à ligne jusqu'à la sentinelle de sortie.
     *
     * @return le code de sortie du script distant
     * @throws IllegalStateException si le flux s'achève sans sentinelle — interruption, jamais
     *                               un succès
     */
    private int stream(String path, String jobId, Map<String, Object> body,
                       Consumer<String> onLine, BooleanSupplier cancelled) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();   // pas de .timeout() : un entraînement dure des heures

        HttpResponse<java.io.InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Le service d'entraînement a refusé la requête (HTTP "
                    + response.statusCode() + ") sur " + baseUrl + path);
        }

        Integer exitCode = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(EXIT_PREFIX)) {
                    exitCode = Integer.parseInt(line.substring(EXIT_PREFIX.length()).trim());
                    break;
                }
                if (cancelled.getAsBoolean()) {
                    // Fermer le flux suffirait à faire tuer le processus côté trainer (son bloc
                    // finally s'exécute à la déconnexion), mais l'annulation explicite ne
                    // dépend alors pas du moment où la prochaine ligne arrive.
                    cancel(jobId);
                    break;
                }
                log.info("Job {} [trainer]: {}", jobId, line);
                if (onLine != null) onLine.accept(line);
            }
        }

        if (exitCode == null) {
            if (cancelled.getAsBoolean()) {
                return 130;   // convention shell : interrompu
            }
            throw new IllegalStateException("Flux d'entraînement interrompu sans code de sortie "
                    + "(job " + jobId + "). Le service trainer s'est arrêté ou la connexion a été "
                    + "rompue ; le résultat est indéterminé et n'est donc PAS considéré comme un "
                    + "succès.");
        }
        return exitCode;
    }
}
