package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalogue des modèles de base disponibles pour le fine-tuning.
 *
 * <p><b>Source de vérité unique.</b> Le catalogue est chargé depuis
 * {@code base_models.json} — le même fichier que lisent les scripts Python
 * ({@code train_host.py}, {@code export_gguf.py}, {@code export_lora_gguf.py}),
 * embarqué au classpath à la construction (cf. pom.xml). Un adaptateur LoRA n'est
 * valide que sur le modèle de base exact qui l'a entraîné : centraliser le mapping
 * alias → repo HuggingFace élimine les divergences entre entraînement et fusion.</p>
 */
@Service
public class BaseModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(BaseModelCatalog.class);
    private static final String MANIFEST_RESOURCE = "/base_models.json";

    /** Description d'un modèle de base entraînable. */
    public record BaseModel(String alias, String hfRepo, Integer contextLength, String description) {}

    private final Map<String, BaseModel> models;

    public BaseModelCatalog() {
        this(loadManifest());
    }

    BaseModelCatalog(Map<String, BaseModel> models) {
        this.models = models;
    }

    private static Map<String, BaseModel> loadManifest() {
        Map<String, BaseModel> loaded = new LinkedHashMap<>();
        try (InputStream in = BaseModelCatalog.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (in == null) {
                log.warn("Manifeste {} introuvable au classpath — catalogue de modèles de base vide "
                        + "(seuls les repos HuggingFace complets seront acceptés).", MANIFEST_RESOURCE);
                return loaded;
            }
            JsonNode root = new ObjectMapper().readTree(in);
            JsonNode modelsNode = root.path("models");
            modelsNode.properties().forEach(property -> {
                String alias = property.getKey();
                JsonNode entry = property.getValue();
                String hfRepo = entry.path("hfRepo").asText(null);
                if (hfRepo == null || hfRepo.isBlank()) {
                    log.warn("Entrée '{}' du manifeste sans hfRepo — ignorée", alias);
                    return;
                }
                Integer contextLength = entry.hasNonNull("contextLength")
                        ? entry.get("contextLength").asInt() : null;
                loaded.put(alias, new BaseModel(alias, hfRepo,
                        contextLength, entry.path("description").asText(null)));
            });
            log.info("Catalogue des modèles de base chargé : {}", loaded.keySet());
        } catch (Exception e) {
            log.warn("Lecture du manifeste {} impossible : {} — catalogue vide", MANIFEST_RESOURCE, e.getMessage());
        }
        return loaded;
    }

    /** Le modèle de base correspondant à un alias du catalogue, s'il existe. */
    public Optional<BaseModel> find(String alias) {
        return alias != null ? Optional.ofNullable(models.get(alias)) : Optional.empty();
    }

    /**
     * Résout un identifiant de modèle de base vers son repo HuggingFace.
     *
     * <ul>
     *   <li>alias du catalogue ({@code phi3}, {@code tinyllama}, …) → repo HF associé ;</li>
     *   <li>repo HF complet ({@code org/nom}) → accepté tel quel ;</li>
     *   <li>tout le reste est rejeté <b>avant</b> de lancer un entraînement voué à échouer
     *       au téléchargement HuggingFace (ex. l'alias d'un modèle GGUF du registre,
     *       qui n'a pas de poids entraînables).</li>
     * </ul>
     *
     * @throws IllegalArgumentException si l'identifiant n'est ni un alias connu ni un repo HF
     */
    public String resolveHfRepo(String aliasOrRepo) {
        if (aliasOrRepo == null || aliasOrRepo.isBlank()) {
            throw new IllegalArgumentException(
                    "Modèle de base manquant. Alias disponibles : " + models.keySet());
        }
        BaseModel known = models.get(aliasOrRepo);
        if (known != null) {
            return known.hfRepo();
        }
        if (aliasOrRepo.contains("/")) {
            // Repo HuggingFace explicite (org/nom) : accepté tel quel.
            return aliasOrRepo;
        }
        throw new IllegalArgumentException(
                "Modèle de base inconnu : '" + aliasOrRepo + "'. Utilisez un alias du catalogue "
                + models.keySet() + " ou un repo HuggingFace complet (« org/nom »). "
                + "NB : un modèle GGUF du registre local n'est pas ré-entraînable.");
    }

    /** Vue API du catalogue (alias, repo HF, contexte, description). */
    public List<Map<String, Object>> list() {
        return models.values().stream()
                .map(m -> {
                    Map<String, Object> api = new LinkedHashMap<>();
                    api.put("alias", m.alias());
                    api.put("hfRepo", m.hfRepo());
                    if (m.contextLength() != null) api.put("contextLength", m.contextLength());
                    if (m.description() != null) api.put("description", m.description());
                    return api;
                })
                .toList();
    }
}
