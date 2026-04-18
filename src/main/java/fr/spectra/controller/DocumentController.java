package fr.spectra.controller;

import fr.spectra.config.SpectraProperties;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.FtsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoints pour lister et supprimer les documents ingérés dans ChromaDB.
 */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "Documents", description = "Gestion des documents ingérés")
public class DocumentController {

    private final ChromaDbClient chromaDbClient;
    private final FtsService ftsService;
    private final String defaultCollection;

    public DocumentController(ChromaDbClient chromaDbClient,
                               FtsService ftsService,
                               SpectraProperties properties) {
        this.chromaDbClient = chromaDbClient;
        this.ftsService = ftsService;
        this.defaultCollection = properties.chromadb().effectiveCollection();
    }

    /**
     * Liste les fichiers sources ingérés avec leur nombre de chunks.
     *
     * @param collection nom de la collection ChromaDB (optionnel, défaut = collection configurée)
     */
    @GetMapping
    @Operation(summary = "Liste les fichiers sources ingérés avec leur nombre de chunks")
    public Map<String, Integer> listDocuments(
            @RequestParam(value = "collection", defaultValue = "") String collection) {
        String coll = collection.isBlank() ? defaultCollection : collection;
        String collectionId = chromaDbClient.getOrCreateCollection(coll);
        return chromaDbClient.listSources(collectionId);
    }

    /**
     * Supprime tous les chunks d'un fichier source.
     *
     * @param sourceFile  nom du fichier tel qu'il apparaît dans les métadonnées (URL-encodé)
     * @param collection  nom de la collection (optionnel)
     */
    @DeleteMapping("/{sourceFile}")
    @Operation(summary = "Supprime tous les chunks d'un fichier source de ChromaDB")
    public ResponseEntity<Map<String, Object>> deleteDocument(
            @PathVariable String sourceFile,
            @RequestParam(value = "collection", defaultValue = "") String collection) {
        String coll = collection.isBlank() ? defaultCollection : collection;
        String collectionId = chromaDbClient.getOrCreateCollection(coll);
        int deleted = chromaDbClient.deleteBySource(collectionId, sourceFile);
        ftsService.removeBySource(sourceFile, coll);
        return ResponseEntity.ok(Map.of(
                "sourceFile", sourceFile,
                "collection", coll,
                "chunksDeleted", deleted
        ));
    }
}
