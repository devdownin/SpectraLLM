package fr.spectra.docs;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie que les documents publiés en deux langues ont la <b>même structure</b>.
 *
 * <p><b>Pourquoi.</b> Le dépôt maintient plusieurs documents en français et en anglais, tenus
 * à jour à la main. Rien ne signalait qu'une section ajoutée d'un côté manquait de l'autre :
 * au moment d'écrire ce test, le manuel utilisateur français comptait 165 titres contre 161
 * pour l'anglais — quatre sous-sections décrivant l'interface du Playground n'existaient tout
 * simplement pas en anglais. Un lecteur anglophone ne pouvait pas savoir qu'il lui manquait
 * quelque chose.</p>
 *
 * <p><b>Ce qui est comparé, et pourquoi.</b> Uniquement la <b>séquence des niveaux de
 * titres</b> — les intitulés sont traduits, les comparer n'aurait aucun sens. C'est une
 * approximation volontairement grossière : elle n'atteste pas que le contenu est équivalent,
 * seulement que le plan l'est. Une traduction peut être périmée sans que ce test bronche ; en
 * revanche une section entière ajoutée d'un seul côté ne passe plus.</p>
 *
 * <p>Le diagnostic donne le rang du titre divergent et son contexte, pour qu'on sache où
 * regarder sans relancer d'outil.</p>
 */
class BilingualManualParityTest {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /** Paires de documents censés partager un plan, relatives à la racine du dépôt. */
    private static final List<String[]> PAIRS = List.of(
            new String[]{"docs/user/user-manual.fr.md", "docs/user/user-manual.en.md"},
            new String[]{"docs/user/documentation-pedagogique.fr.md",
                         "docs/user/documentation-pedagogique.en.md"},
            new String[]{"docs/tech/rag-pipeline.fr.md", "docs/tech/rag-pipeline.en.md"},
            new String[]{"README.fr.md", "README.md"}
    );

    /** Un titre : son niveau et son intitulé. L'intitulé ne sert qu'au diagnostic. */
    private record Heading(int level, String text) {}

    @Test
    void lesDocumentsBilinguesPartagentLeMemePlan() throws IOException {
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(repoRoot.resolve("docs")),
                "hors arborescence du dépôt (pas de docs/) — contrôle de parité ignoré");

        List<String> divergences = new ArrayList<>();
        for (String[] pair : PAIRS) {
            Path a = repoRoot.resolve(pair[0]);
            Path b = repoRoot.resolve(pair[1]);
            if (!Files.exists(a) || !Files.exists(b)) continue;   // paire non applicable
            compare(pair[0], headings(a), pair[1], headings(b)).ifPresent(divergences::add);
        }

        assertThat(divergences)
                .as("documents bilingues dont le plan a divergé — une section existe d'un côté "
                    + "et pas de l'autre")
                .isEmpty();
    }

    /** Le contrôle doit détecter une section ajoutée d'un seul côté, pas seulement compter. */
    @Test
    void uneSectionAjouteeDUnSeulCoteEstDetectee(@TempDir Path dir) throws IOException {
        Path fr = dir.resolve("a.fr.md");
        Path en = dir.resolve("a.en.md");
        Files.writeString(fr, "# Titre\n## Un\n### Détail\n## Deux\n", StandardCharsets.UTF_8);
        Files.writeString(en, "# Title\n## One\n## Two\n", StandardCharsets.UTF_8);

        var divergence = compare("a.fr.md", headings(fr), "a.en.md", headings(en));

        assertThat(divergence).isPresent();
        assertThat(divergence.get()).contains("Détail");
    }

    /** Des intitulés traduits, mais un même plan, doivent passer. */
    @Test
    void desIntitulesTraduitsNeSontPasUneDivergence(@TempDir Path dir) throws IOException {
        Path fr = dir.resolve("a.fr.md");
        Path en = dir.resolve("a.en.md");
        Files.writeString(fr, "# Titre\n## Démarrage\n### Prérequis\n", StandardCharsets.UTF_8);
        Files.writeString(en, "# Title\n## Getting started\n### Requirements\n", StandardCharsets.UTF_8);

        assertThat(compare("a.fr.md", headings(fr), "a.en.md", headings(en))).isEmpty();
    }

    // ── Mécanique ────────────────────────────────────────────────────────────

    private static List<Heading> headings(Path file) throws IOException {
        List<Heading> result = new ArrayList<>();
        boolean inFence = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            // Un `#` dans un bloc de code n'est pas un titre — souvent un commentaire shell.
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;
            Matcher m = HEADING.matcher(line);
            if (m.matches()) result.add(new Heading(m.group(1).length(), m.group(2).strip()));
        }
        return result;
    }

    /** Première divergence de plan, formulée pour être actionnable. Vide si les plans concordent. */
    private static java.util.Optional<String> compare(String nameA, List<Heading> a,
                                                      String nameB, List<Heading> b) {
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            Integer levelA = i < a.size() ? a.get(i).level() : null;
            Integer levelB = i < b.size() ? b.get(i).level() : null;
            if (java.util.Objects.equals(levelA, levelB)) continue;

            return java.util.Optional.of(String.format(
                    "%s (%d titres) et %s (%d titres) divergent au titre n°%d :%n"
                    + "    %s : %s%n    %s : %s%n"
                    + "    contexte : %s",
                    nameA, a.size(), nameB, b.size(), i + 1,
                    nameA, describe(a, i), nameB, describe(b, i),
                    context(a, i)));
        }
        return java.util.Optional.empty();
    }

    private static String describe(List<Heading> headings, int index) {
        if (index >= headings.size()) return "<fin du document>";
        Heading h = headings.get(index);
        return "#".repeat(h.level()) + " " + h.text();
    }

    /** Les trois titres précédents, pour situer la divergence dans le document. */
    private static String context(List<Heading> headings, int index) {
        return headings.subList(Math.max(0, index - 3), Math.min(index, headings.size())).stream()
                .map(Heading::text)
                .reduce((x, y) -> x + " → " + y)
                .orElse("(début du document)");
    }
}
