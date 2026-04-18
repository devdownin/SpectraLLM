package fr.spectra.service.extraction;

import fr.spectra.model.ExtractedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Layout-aware PDF extractor backed by the Python docparser microservice.
 *
 * <p>Uses {@link LayoutParserClient} to extract PDF content as structured Markdown,
 * preserving headings, tables, and multi-column layouts. Falls back to plain-text
 * PDFBox extraction ({@link PdfExtractor}) if the service is unavailable.
 *
 * <p>Active only when {@code spectra.layout-parser.enabled=true}.
 * {@link PdfExtractor} is conditionally deactivated in that case to avoid
 * duplicate registrations for {@code application/pdf}.
 */
@Component
@ConditionalOnProperty(prefix = "spectra.layout-parser", name = "enabled", havingValue = "true")
public class LayoutAwarePdfExtractor implements DocumentExtractor {

    private static final Logger log = LoggerFactory.getLogger(LayoutAwarePdfExtractor.class);

    private final LayoutParserClient client;
    /** Direct instantiation (no Spring dependency) — used as fallback when docparser is unavailable. */
    private final PdfExtractor fallback = new PdfExtractor();

    public LayoutAwarePdfExtractor(LayoutParserClient client) {
        this.client = client;
    }

    @Override
    public Set<String> supportedContentTypes() {
        return Set.of("application/pdf");
    }

    @Override
    public ExtractedDocument extract(String fileName, InputStream content) throws ExtractionException {
        byte[] bytes;
        try {
            bytes = content.readAllBytes();
        } catch (IOException e) {
            throw new ExtractionException("Cannot read PDF content: " + fileName, e);
        }

        // Try layout-aware parsing first
        Optional<LayoutParserClient.ParseResult> result = client.parse(fileName, bytes);
        if (result.isPresent()) {
            LayoutParserClient.ParseResult r = result.get();
            Map<String, String> meta = new HashMap<>(r.metadata());
            meta.put("format", "PDF");
            meta.put("parser", r.parser());
            meta.put("layoutAware", "true");
            log.info("Layout-aware extraction succeeded for '{}': {} chars, {} pages, parser={}",
                    fileName, r.text().length(), r.pageCount(), r.parser());
            return new ExtractedDocument(fileName, "application/pdf", r.text(), r.pageCount(), meta);
        }

        // Fallback to plain PDFBox extraction
        log.warn("docparser unavailable for '{}', using PDFBox fallback", fileName);
        return fallback.extract(fileName, new ByteArrayInputStream(bytes));
    }
}
