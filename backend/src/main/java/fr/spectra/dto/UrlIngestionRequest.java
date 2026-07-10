package fr.spectra.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UrlIngestionRequest(
        @NotEmpty(message = "La liste d'URLs ne peut pas être vide")
        @Size(max = 20, message = "Maximum 20 URLs par requête")
        List<String> urls
) {}
