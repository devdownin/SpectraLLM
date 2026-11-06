package fr.spectra.sources;

import org.junit.jupiter.api.Test;
import java.net.UnknownHostException;
import static org.assertj.core.api.Assertions.*;

class SourceHttpClientTest {
    @Test void privateAndMetadataAddressesAreBlockedWithoutAdministratorAllowlist() {
        var client = new SourceHttpClient("");
        for (String address : new String[] {"127.0.0.1", "10.12.0.1", "169.254.169.254", "100.64.0.1"}) {
            assertThatThrownBy(() -> client.validate("http://" + address + "/docs"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("explicitly allowed");
        }
        assertThatThrownBy(() -> client.resolveAllowed("127.0.0.1"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test void explicitHostAllowlistTrimsWhitespaceAndAllowsOnlyNamedPrivateHost() throws Exception {
        var client = new SourceHttpClient(" 127.0.0.1 , 10.0.0.2 ");
        assertThat(client.validate("http://127.0.0.1:9000/").getHost()).isEqualTo("127.0.0.1");
        assertThat(client.resolveAllowed("127.0.0.1")).hasSize(1);
        assertThatThrownBy(() -> client.validate("http://127.0.0.2/")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void embeddedCredentialsFragmentsAndUnsupportedSchemesAreRejectedEvenForAllowedHost() {
        var client = new SourceHttpClient("127.0.0.1");
        for (String url : new String[] {"http://user:secret@127.0.0.1/", "http://127.0.0.1/#fragment", "file:///etc/passwd", "ftp://127.0.0.1/"}) {
            assertThatThrownBy(() -> client.validate(url)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
