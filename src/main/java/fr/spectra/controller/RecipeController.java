package fr.spectra.controller;

import fr.spectra.dto.FineTuningRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Gestion des recettes d'entraînement YAML (H4).
 *
 * <pre>
 * GET  /api/fine-tuning/recipes            — liste les recettes disponibles
 * GET  /api/fine-tuning/recipes/{name}     — retourne une recette comme JSON (pour pré-remplir le formulaire)
 * POST /api/fine-tuning/recipe/export      — exporte un FineTuningRequest en fichier YAML téléchargeable
 * </pre>
 */
@RestController
@RequestMapping("/api/fine-tuning")
@Tag(name = "Recipes", description = "Recettes d'entraînement YAML réutilisables")
public class RecipeController {

    private static final Logger log = LoggerFactory.getLogger(RecipeController.class);
    private static final String RECIPES_CLASSPATH = "classpath:recipes/*.yml";

    @GetMapping("/recipes")
    @Operation(summary = "Lister les recettes d'entraînement disponibles")
    public List<Map<String, Object>> listRecipes() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(RECIPES_CLASSPATH);
            for (Resource res : resources) {
                try (InputStream is = res.getInputStream()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> recipe = new Yaml().load(is);
                    if (recipe != null) {
                        // Return only metadata fields for the list
                        Map<String, Object> meta = new LinkedHashMap<>();
                        meta.put("name", recipe.get("name"));
                        meta.put("displayName", recipe.get("displayName"));
                        meta.put("description", recipe.get("description"));
                        result.add(meta);
                    }
                } catch (Exception e) {
                    log.warn("Impossible de lire la recette {}: {}", res.getFilename(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Erreur lors du chargement des recettes: {}", e.getMessage());
        }
        return result;
    }

    @GetMapping("/recipes/{name}")
    @Operation(summary = "Charger une recette comme objet FineTuningRequest (pour pré-remplir le formulaire)")
    public ResponseEntity<Map<String, Object>> getRecipe(@PathVariable String name) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource res = resolver.getResource("classpath:recipes/" + name + ".yml");
            if (!res.exists()) {
                return ResponseEntity.notFound().build();
            }
            try (InputStream is = res.getInputStream()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> recipe = new Yaml().load(is);
                return ResponseEntity.ok(recipe);
            }
        } catch (Exception e) {
            log.warn("Erreur lecture recette '{}': {}", name, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/recipe/export")
    @Operation(summary = "Exporter la configuration d'un job comme fichier YAML téléchargeable")
    public ResponseEntity<byte[]> exportRecipe(@RequestBody FineTuningRequest request) {
        try {
            // Build an ordered map of the request fields for clean YAML output
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("name", "custom-recipe");
            doc.put("displayName", request.modelName());
            doc.put("description", "Recette exportée depuis Spectra");
            doc.put("baseModel", request.baseModel());
            doc.put("loraRank", request.loraRank());
            doc.put("loraAlpha", request.loraAlpha());
            doc.put("epochs", request.epochs());
            doc.put("learningRate", request.learningRate());
            doc.put("minConfidence", request.minConfidence());
            doc.put("packingEnabled", request.packingEnabled());
            doc.put("dpoEnabled", request.dpoEnabled());

            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            opts.setPrettyFlow(true);
            String yaml = new Yaml(opts).dump(doc);
            byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);

            String filename = (request.modelName() != null ? request.modelName() : "recipe") + ".yml";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                    .contentType(MediaType.parseMediaType("application/yaml"))
                    .body(bytes);
        } catch (Exception e) {
            log.error("Erreur export recette: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
