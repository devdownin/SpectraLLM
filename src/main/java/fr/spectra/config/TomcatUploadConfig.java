package fr.spectra.config;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * Caps multipart upload time to 2 minutes.
 *
 * Without this, a 50 MB upload from a slow or malicious client can hold
 * a connection open indefinitely. Tomcat's disableUploadTimeout defaults
 * to true (no cap); flipping it to false activates connectionUploadTimeout.
 */
@Configuration
public class TomcatUploadConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> {
            connector.setProperty("disableUploadTimeout", "false");
            connector.setProperty("connectionUploadTimeout", "120000");
        });
    }
}
