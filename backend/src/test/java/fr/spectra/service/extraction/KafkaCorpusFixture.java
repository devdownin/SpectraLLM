package fr.spectra.service.extraction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Corpus d'essai pour les extracteurs XML et JSON : le vrai s'il est présent, un
 * substitut synthétique sinon.
 *
 * <p><b>Pourquoi.</b> {@code XmlExtractorTest} et {@code JsonExtractorTest} étaient
 * conditionnés à la présence de {@code data/documents/*.zip} par un {@code assumeTrue}
 * placé dans un {@code @BeforeAll}. L'hypothèse échouant avant tout test, JUnit
 * abandonnait le conteneur entier : le rapport n'affichait ni échec, <b>ni même
 * « Skipped »</b> — seulement {@code Tests run: 0}. Trente-deux tests, dont un contrôle
 * de sécurité XXE, ne vérifiaient donc rien, et rien ne le disait.</p>
 *
 * <p>Ces archives sont des documents réels, jamais versionnés (l'historique du dépôt est
 * vide sur ce chemin). Les verser au dépôt n'est pas envisageable ; en revanche, ce que
 * les tests vérifient — la mise à plat des chemins, les métadonnées, la robustesse — ne
 * dépend pas de leur contenu exact, seulement de leur <b>forme</b>. Ce substitut la
 * reproduit : une liste de messages Kafka avec topic, partition, offset, clé et charge
 * utile.</p>
 *
 * <p>Les rares assertions qui portent réellement sur le corpus de production — son nombre
 * d'entrées — restent conditionnées, mais par un {@code assumeTrue} <b>dans le corps du
 * test</b> : le rapport affiche alors un saut visible plutôt qu'un silence.</p>
 */
final class KafkaCorpusFixture {

    /** Topic présent dans le corpus de production, repris tel quel pour les assertions. */
    static final String TOPIC = "asf.peage.backoffice.conformite.sortie.conforme";

    /** Nombre d'entrées du corpus de production, au moment où ces tests ont été écrits. */
    static final int PRODUCTION_ENTRY_COUNT = 36;

    private KafkaCorpusFixture() {}

    /** Entrées chargées, et si elles proviennent du corpus de production ou du substitut. */
    record Corpus(Map<String, byte[]> entries, boolean isProduction) {}

    static Corpus loadXml() throws IOException {
        return load(Path.of("data/documents/xmls.zip"), KafkaCorpusFixture::syntheticXml);
    }

    static Corpus loadJson() throws IOException {
        return load(Path.of("data/documents/jsons.zip"), KafkaCorpusFixture::syntheticJson);
    }

    private static Corpus load(Path archive, Synthesizer fallback) throws IOException {
        if (Files.exists(archive)) {
            Map<String, byte[]> entries = readZip(archive);
            if (!entries.isEmpty()) return new Corpus(entries, true);
        }
        return new Corpus(fallback.build(), false);
    }

    @FunctionalInterface
    private interface Synthesizer {
        Map<String, byte[]> build();
    }

    private static Map<String, byte[]> readZip(Path archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    /** Trois messages de forme identique au corpus réel — de quoi exercer la mise à plat. */
    private static Map<String, byte[]> syntheticXml() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <KafkaMessageList>
                      <KafkaMessage>
                        <topic>%s</topic>
                        <partition>0</partition>
                        <offset>%d</offset>
                        <key>p0_55925%d</key>
                        <payload>identifiant 55925%d, sortie conforme</payload>
                      </KafkaMessage>
                    </KafkaMessageList>
                    """.formatted(TOPIC, 100 + i, i, i);
            entries.put("synthetique_p0_55925" + i + ".xml", xml.getBytes(StandardCharsets.UTF_8));
        }
        return entries;
    }

    private static Map<String, byte[]> syntheticJson() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String json = """
                    [
                      {
                        "topic": "%s",
                        "partition": 0,
                        "offset": %d,
                        "key": "p0_55925%d",
                        "payload": "identifiant 55925%d, sortie conforme"
                      }
                    ]
                    """.formatted(TOPIC, 100 + i, i, i);
            entries.put("synthetique_p0_55925" + i + ".json", json.getBytes(StandardCharsets.UTF_8));
        }
        return entries;
    }
}
