package fr.spectra.service;

import fr.spectra.service.extraction.ExtractionException;

/** Échec explicite avec conservation du nombre de chunks déjà indexés. */
public class PartialIngestionException extends ExtractionException {
    private final int chunks;

    public PartialIngestionException(String message, int chunks) {
        super(message);
        this.chunks = chunks;
    }

    public int chunks() { return chunks; }
}
