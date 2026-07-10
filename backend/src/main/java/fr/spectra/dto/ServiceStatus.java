package fr.spectra.dto;

import java.util.Map;

public record ServiceStatus(String name, String url, boolean available, String version,
                             long elapsedMs, Map<String, Object> details) {

    public static ServiceStatus unavailable(String name, String url, long elapsedMs) {
        return new ServiceStatus(name, url, false, "unavailable", elapsedMs, Map.of());
    }
}
