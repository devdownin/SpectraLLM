package fr.spectra.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Répond à GET / pour éviter un 404 quand le port 8080 est accédé directement.
 * Redirige vers le frontend nginx (port 80). En production nginx intercepte
 * avant qu'il n'atteigne Spring.
 */
@Hidden
@RestController
public class RootController {

    @GetMapping("/")
    public void root(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendRedirect("http://" + request.getServerName() + "/");
    }
}
