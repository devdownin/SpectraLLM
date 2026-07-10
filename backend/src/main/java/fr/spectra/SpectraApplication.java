package fr.spectra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SpectraApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpectraApplication.class, args);
    }
}
