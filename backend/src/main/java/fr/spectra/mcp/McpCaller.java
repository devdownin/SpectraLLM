package fr.spectra.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import java.util.Set;

record McpCaller(String id, Set<String> collections) {
    static final String ATTRIBUTE = McpCaller.class.getName();
    static final McpCaller LEGACY = new McpCaller("instance", null);

    static McpCaller from(McpSyncServerExchange exchange) {
        if (exchange != null && exchange.transportContext().get(ATTRIBUTE) instanceof McpCaller caller) {
            return caller;
        }
        throw new IllegalArgumentException("Missing authenticated MCP caller");
    }

    McpTransportContext context() {
        return McpTransportContext.create(java.util.Map.of(ATTRIBUTE, this));
    }

    boolean scoped() {
        return collections != null;
    }

    void requireCollection(String collection) {
        if (scoped() && (collection == null || !collections.contains(collection))) {
            throw new IllegalArgumentException("Collection is not accessible to this MCP caller");
        }
    }
}
