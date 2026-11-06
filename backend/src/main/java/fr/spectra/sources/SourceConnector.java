package fr.spectra.sources;

import java.util.Map;

/** Connectors collect bounded items; only the shared service writes indexes or sync state. */
public interface SourceConnector {
    SourceModels.Type type();
    void validate(SourceModels.Config config);
    SourceModels.ConnectionTest test(SourceModels.Config config) throws Exception;
    /** Return true only when discovery covered the entire configured scope. */
    boolean scan(SourceModels.Config config, Sink sink) throws Exception;
    record Item(String externalId, String title, String sourceUrl, String filename, String revision,
                byte[] content, Map<String, String> metadata) {}
    interface Sink {
        void accept(Item item) throws Exception;
        default void deleted(String externalId) throws Exception {}
        void checkCancelled() throws InterruptedException;
    }
}
