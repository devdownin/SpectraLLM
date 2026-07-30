package fr.spectra.docs;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie que tous les liens Markdown **internes** du dépôt résolvent vers un fichier existant.
 *
 * <p>Déterministe et sans réseau : seuls les chemins relatifs sont contrôlés, les URL
 * {@code http(s)} et {@code mailto:} sont ignorées. Empêche la réapparition de liens morts
 * après un renommage ou une suppression de document.
 *
 * <p><b>Pourquoi un test JVM et non un script.</b> Ce contrôle était un script Python
 * ({@code scripts/check-doc-links.py}) exécuté par un workflow dédié, ce qui imposait une
 * troisième chaîne d'outils (Python) au dépôt pour 40 lignes de comparaison de chemins — et ne
 * détectait le lien cassé qu'après le push. Porté ici, il tourne dans le job de build existant
 * et échoue en local avant le commit. Voir {@code docs/process/audit-python-java.fr.md}.
 *
 * <p>Le test s'exécute depuis le module {@code backend/} ; la racine du dépôt est donc son
 * répertoire parent. Hors arborescence de sources (exécution depuis un jar packagé), il est
 * neutralisé plutôt qu'en échec.
 */
class DocumentationLinksTest {

    /** Même expression que le script d'origine : capture la cible, ignore l'ancre {@code #…}. */
    private static final Pattern LINK = Pattern.compile("\\[[^\\]]*]\\(([^)#\\s]+)(?:#[^)]*)?\\)");

    private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "target", "dist", "build");

    /** Lien non résolu : de quoi le corriger sans relancer d'outil. */
    private record BrokenLink(Path file, int line, String target) {
        @Override
        public String toString() {
            return file + ":" + line + " -> " + target;
        }
    }

    @Test
    void tousLesLiensInternesResolvent() throws IOException {
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(repoRoot.resolve("docs")),
                "hors arborescence du dépôt (pas de docs/) — contrôle des liens ignoré");

        List<BrokenLink> broken = findBrokenLinks(repoRoot);

        assertThat(broken)
                .as("liens Markdown internes cassés (chemin relatif ne résolvant vers aucun fichier)")
                .isEmpty();
    }

    /** Un lien vers une cible absente est bien signalé, avec son numéro de ligne. */
    @Test
    void unLienCasseEstDetecteAvecSaLigne(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("present.md"), "cible", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("index.md"), String.join("\n",
                "# Titre",
                "[ok](present.md) et [ok avec ancre](present.md#section)",
                "[absent](disparu.md)",
                "[externe](https://example.com/x.md) [mail](mailto:a@b.c)"), StandardCharsets.UTF_8);

        List<BrokenLink> broken = findBrokenLinks(dir);

        assertThat(broken).singleElement().satisfies(b -> {
            assertThat(b.target()).isEqualTo("disparu.md");
            assertThat(b.line()).isEqualTo(3);
        });
    }

    /** Les liens remontant l'arborescence sont résolus relativement au fichier qui les porte. */
    @Test
    void lesCheminsRelatifsSontResolusDepuisLeFichierPorteur(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("docs/process"));
        Files.writeString(dir.resolve("README.md"), "racine", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("docs/process/audit.md"),
                "[racine](../../README.md) [voisin inexistant](./autre.md)", StandardCharsets.UTF_8);

        assertThat(findBrokenLinks(dir))
                .extracting(BrokenLink::target)
                .containsExactly("./autre.md");
    }

    /** Les répertoires de build et de dépendances ne sont pas parcourus. */
    @Test
    void lesRepertoiresIgnoresNeSontPasParcourus(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("node_modules/pkg"));
        Files.writeString(dir.resolve("node_modules/pkg/README.md"),
                "[cassé](nulle-part.md)", StandardCharsets.UTF_8);

        assertThat(findBrokenLinks(dir)).isEmpty();
    }

    private static List<BrokenLink> findBrokenLinks(Path root) throws IOException {
        List<BrokenLink> broken = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Élagage à la descente : inutile (et lent) de parcourir node_modules ou target.
                return SKIP_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().endsWith(".md")) {
                    checkFile(file, broken);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        broken.sort(java.util.Comparator.comparing(BrokenLink::toString));
        return broken;
    }

    private static void checkFile(Path markdown, List<BrokenLink> broken) throws IOException {
        String text = Files.readString(markdown, StandardCharsets.UTF_8);
        Matcher matcher = LINK.matcher(text);
        while (matcher.find()) {
            String target = matcher.group(1);
            if (target.startsWith("http://") || target.startsWith("https://")
                    || target.startsWith("mailto:")) {
                continue;
            }
            Path resolved = markdown.getParent().resolve(target).normalize();
            if (!Files.exists(resolved)) {
                broken.add(new BrokenLink(markdown, lineOf(text, matcher.start()), target));
            }
        }
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }
}
