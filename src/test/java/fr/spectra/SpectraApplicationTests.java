package fr.spectra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spectra.llm.runtime.enabled=false")
class SpectraApplicationTests {

    @Test
    void contextLoads() {
    }
}
