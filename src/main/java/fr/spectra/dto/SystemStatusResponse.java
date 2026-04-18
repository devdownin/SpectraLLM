package fr.spectra.dto;

import java.time.Instant;
import java.util.List;

public record SystemStatusResponse(
        String application,
        String version,
        Instant timestamp,
        List<ServiceStatus> services
) {
}
