package fr.spectra.dto;

import java.util.List;

/**
 * Profil des ressources disponibles sur l'hôte (ou le conteneur),
 * accompagné des paramètres llama-server recommandés pour chaque mode.
 */
public record ResourceProfile(
        /** Nombre de cœurs CPU disponibles (tenant compte des quotas cgroups). */
        int cpuCores,
        /** RAM disponible en MB (tenant compte des limites cgroups). */
        long ramMb,
        /** Type de GPU détecté : "nvidia", "amd", "vulkan" ou "none". */
        String gpuType,
        /** VRAM GPU en MB (0 si aucun GPU NVIDIA détecté). */
        long gpuVramMb,
        /** Indique si une limite cgroup mémoire est active. */
        boolean cgroupMemoryLimitActive,
        /** Indique si un quota cgroup CPU est actif. */
        boolean cgroupCpuQuotaActive,
        /** Paramètres recommandés pour le mode chat. */
        LlamaServerParams chatRecommendation,
        /** Paramètres recommandés pour le mode embed. */
        LlamaServerParams embedRecommendation
) {

    /**
     * Paramètres recommandés pour llama-server dans un mode donné.
     */
    public record LlamaServerParams(
            int threads,
            int contextSize,
            int batchSize,
            int nGpuLayers,
            boolean flashAttn,
            String cacheTypeK,
            String cacheTypeV,
            /** Arguments CLI équivalents, prêts à passer à llama-server. */
            List<String> cliArgs
    ) {}
}
