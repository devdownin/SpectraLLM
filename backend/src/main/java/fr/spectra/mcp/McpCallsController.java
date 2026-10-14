package fr.spectra.mcp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lecture du journal des appels MCP. Sous {@code /api/**}, donc derrière la même clé API que
 * {@code /mcp} — MCP exige cette clé, si bien que ce journal n'est jamais lisible sans elle.
 */
@RestController
@RequestMapping("/api/mcp")
@Tag(name = "MCP", description = "Journal des appels d'outils MCP")
@ConditionalOnProperty(name = "spectra.mcp.enabled", havingValue = "true")
public class McpCallsController {

    private final McpCallJournal journal;

    public McpCallsController(McpCallJournal journal) {
        this.journal = journal;
    }

    public record McpCalls(int capacity, List<McpCallJournal.McpCall> calls) {}

    @GetMapping("/calls")
    @Operation(summary = "Derniers appels d'outils MCP, le plus récent d'abord (en mémoire, perdus au redémarrage)")
    public McpCalls calls(@RequestParam(defaultValue = "50") int limit) {
        return new McpCalls(McpCallJournal.CAPACITY, journal.recent(limit));
    }
}
