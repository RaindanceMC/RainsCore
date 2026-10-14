package de.raindancer.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This plugin ships without external dependencies, and stays that way.
 *
 * <h2>Why a test and not just a decision</h2>
 * Adding a dependency is one line, and every one of them is a line somebody added for a good reason
 * at the time. The reasons against are not visible at that line: a {@code libraries:} entry is a
 * download at startup, so a server behind a firewall or a repository having a bad morning becomes a
 * plugin that does not load; and a jar on the shared classpath is a chance to collide with whatever
 * another plugin brought. What this project does instead is copy the code in — see
 * {@code THIRD-PARTY.md} — which costs a repository to keep an eye on and gives a jar that always
 * works.
 *
 * <p>So the rule is written down here, with its reasoning, rather than left as a habit that quietly
 * lapses.
 */
class NoExternalDependenciesTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path DESCRIPTOR = Path.of("src/main/resources/paper-plugin.yml");
    private static final Path THIRD_PARTY = Path.of("THIRD-PARTY.md");
    private static final Path VENDORED = Path.of("src/main/java/de/raindancer/core/internal");

    // ------------------------------------------------------------------ no runtime downloads

    @Test
    @DisplayName("Paper is not asked to fetch anything at startup")
    void declaresNoRuntimeLibraries() throws IOException {
        assertThat(declaredLibraries())
                .as("a libraries: entry is a download at startup, so a firewall or a repository "
                        + "outage becomes a plugin that does not load. Copy the code in instead, "
                        + "and record it in THIRD-PARTY.md.")
                .isEmpty();
    }

    @Test
    @DisplayName("nothing is on the build path but what the server provides and what tests need")
    void everyDependencyIsProvidedOrTest() throws IOException {
        List<String> shipped = new ArrayList<>();
        Matcher dependencies = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL)
                // A build plugin's own dependencies (the shade plugin's ASM) are tools, never shipped.
                .matcher(Files.readString(POM).replaceAll("(?s)<build>.*</build>", ""));
        while (dependencies.find()) {
            String block = dependencies.group(1);
            String scope = group(block, "<scope>([^<]+)</scope>");
            if (scope == null || !(scope.equals("provided") || scope.equals("test"))) {
                shipped.add(group(block, "<artifactId>([^<]+)</artifactId>")
                        + " (scope " + (scope == null ? "compile" : scope) + ")");
            }
        }
        shipped.removeAll(SHADED);
        assertThat(shipped)
                .as("a compile-scoped dependency ships inside the jar. Only the PostgreSQL driver does "
                        + "(NETWORK-PLAN.md N10); anything else is copied in, or not used.")
                .isEmpty();
    }

    /** What ships inside the jar, shaded and relocated: the PostgreSQL driver and nothing else. */
    private static final List<String> SHADED = List.of("postgresql (scope compile)");

    @Test
    @DisplayName("the shaded driver is relocated under core.internal and recorded with its licence")
    void shadedDriverIsRelocatedAndRecorded() throws IOException {
        String pom = Files.readString(POM);
        assertThat(pom)
                .as("relocated, so another plugin's own copy of the driver cannot collide with ours")
                .contains("<pattern>org.postgresql</pattern>")
                .contains("<shadedPattern>de.raindancer.core.internal.postgresql</shadedPattern>")
                .contains("<include>org.postgresql:postgresql</include>");
        assertThat(Files.readString(THIRD_PARTY).toLowerCase())
                .contains("postgresql")
                .contains("bsd-2-clause");
    }

    // ------------------------------------------------------------------ what was copied in

    @Test
    @DisplayName("copied-in code is recorded, so nobody has to guess where it came from")
    void vendoredCodeIsDocumented() throws IOException {
        if (!Files.isDirectory(VENDORED)) {
            return;
        }
        String record = Files.readString(THIRD_PARTY);
        List<String> undocumented = new ArrayList<>();
        try (Stream<Path> packages = Files.list(VENDORED)) {
            for (Path each : packages.filter(Files::isDirectory).toList()) {
                if (!record.toLowerCase().contains(each.getFileName().toString().toLowerCase())) {
                    undocumented.add(each.getFileName().toString());
                }
            }
        }
        assertThat(undocumented)
                .as("these are copied into core.internal but THIRD-PARTY.md does not say where "
                        + "from, under what licence, or which version — which is what makes the "
                        + "next upgrade guesswork")
                .isEmpty();
    }

    /**
     * MIT and the licences like it require the copyright notice to travel with the code.
     *
     * <p>Deleting a header while tidying is an easy mistake to make and an actual breach of the
     * terms the code was taken under, so it is worth a test rather than a good intention.
     */
    @Test
    @DisplayName("every copied-in file still carries its original copyright notice")
    void vendoredFilesKeepTheirNotice() throws IOException {
        if (!Files.isDirectory(VENDORED)) {
            return;
        }
        List<String> stripped = new ArrayList<>();
        try (Stream<Path> files = Files.walk(VENDORED)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                // package-info is ours: the note explaining the vendoring, not vendored code.
                if (file.getFileName().toString().equals("package-info.java")) {
                    continue;
                }
                if (!Files.readString(file).contains("Copyright")) {
                    stripped.add(VENDORED.relativize(file).toString());
                }
            }
        }
        assertThat(stripped)
                .as("the licence these were copied under requires the notice to stay with them")
                .isEmpty();
    }

    /**
     * Vendored code is a copy, not a fork.
     *
     * <p>The moment somebody fixes something inside it, the next upgrade stops being "unpack and
     * rename" and becomes a merge — which is how vendored code ends up years out of date. Anything
     * we want differently belongs in the wrapper around it.
     */
    @Test
    @DisplayName("only the wrapper package reaches into the copied-in code")
    void nothingElseImportsVendoredCode() throws IOException {
        if (!Files.isDirectory(VENDORED)) {
            return;
        }
        List<String> trespassers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.startsWith(VENDORED)) {
                    continue;
                }
                String source = Files.readString(file);
                if (source.contains("de.raindancer.core.internal.")
                        && !file.toString().contains("/scoreboard/")) {
                    trespassers.add(file.toString());
                }
            }
        }
        assertThat(trespassers)
                .as("copied-in code is an implementation detail of core.scoreboard; swapping it "
                        + "out should cost one class, not a search across the project")
                .isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    /** The coordinates under {@code libraries:} in the descriptor. */
    private static List<String> declaredLibraries() throws IOException {
        List<String> found = new ArrayList<>();
        boolean inBlock = false;
        for (String line : Files.readAllLines(DESCRIPTOR)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("libraries:")) {
                inBlock = true;
                continue;
            }
            if (inBlock) {
                if (trimmed.startsWith("- ")) {
                    found.add(trimmed.substring(2).trim());
                } else if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    break;
                }
            }
        }
        return found;
    }

    private static String group(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
