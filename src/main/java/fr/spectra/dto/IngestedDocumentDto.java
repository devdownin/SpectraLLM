package fr.spectra.dto;

import java.time.Instant;

public record IngestedDocumentDto(
        String sha256,
        String fileName,
        String format,
        Instant ingestedAt
) {}
