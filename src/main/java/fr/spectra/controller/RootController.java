package fr.spectra.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Répond à GET / pour éviter un 404 quand le port 8080 est accédé directement.
 * Redirige vers le frontend nginx (port 80). En production nginx intercepte
 * avant qu'il n'atteigne Spring.
 */
@Hidden
@RestController
public class RootController {

    /** Nom d'hôte valide (RFC 1123) — neutralise toute injection CRLF / chemin / schéma. */
    private static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");

    @GetMapping("/")
    public void root(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String host = request.getServerName();
        // Le header Host est contrôlé par le client : valider strictement avant
        // de l'utiliser dans une redirection (sinon open redirect / header injection).
        if (host == null || !SAFE_HOST.matcher(host).matches()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid Host header");
            return;
        }
        response.sendRedirect("http://" + host + "/");
    }
}
