package de.raindancer.core.world.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The model engine Core brings along: BetterModel (MIT, toxicity188), shipped inside Core's jar and put into
 * plugins/ by Core itself, so an owner installs RainsCore and gets custom models with it. Paper only loads a plugin
 * found at startup, so a jar installed or updated here takes effect at the next restart.
 *
 * <p>An owner's own BetterModel of the same or a newer version is theirs and is left alone; an older one is moved
 * into {@code RainsCore/replaced}, never deleted.
 */
public final class BundledEngine {

    public static final String VERSION = "3.6.0";
    public static final String FILE_NAME = "bettermodel-" + VERSION + "-paper.jar";
    static final String JAR = "/de/raindancer/core/vendor/bettermodel/" + FILE_NAME;
    static final String LICENCE = "/de/raindancer/core/vendor/bettermodel/LICENSE.md";
    private static final Pattern NAME = Pattern.compile("^bettermodel-(\\d+(?:\\.\\d+)*)(.*)\\.jar$");

    public enum Action { NONE, INSTALL, UPDATE }

    /** What to do, and the jar of BetterModel already there if any. */
    public record Plan(Action action, Optional<String> present) {
    }

    private BundledEngine() {
    }

    public static Plan plan(List<String> jarNames, String bundled) {
        for (String name : jarNames) {
            Matcher found = NAME.matcher(name.toLowerCase(Locale.ROOT));
            if (found.matches()) {
                return new Plan(compare(found.group(1), bundled) < 0 ? Action.UPDATE : Action.NONE,
                        Optional.of(name));
            }
        }
        return new Plan(Action.INSTALL, Optional.empty());
    }

    /** Puts the bundled jar into {@code plugins} as the plan says; off the server's threads, it is file work. */
    public static Action install(Path plugins, Path replaced) {
        try {
            List<String> names = new ArrayList<>();
            try (Stream<Path> listed = Files.list(plugins)) {
                listed.filter(Files::isRegularFile).forEach(file -> names.add(file.getFileName().toString()));
            }
            Plan plan = plan(names, VERSION);
            if (plan.action() == Action.NONE) {
                return Action.NONE;
            }
            if (plan.action() == Action.UPDATE) {
                Files.createDirectories(replaced);
                Path old = plugins.resolve(plan.present().orElseThrow());
                Files.move(old, replaced.resolve(old.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
            try (InputStream jar = BundledEngine.class.getResourceAsStream(JAR)) {
                if (jar == null) {
                    throw new IOException(JAR + " is missing from RainsCore's jar");
                }
                Path written = plugins.resolve(FILE_NAME + ".part");
                Files.copy(jar, written, StandardCopyOption.REPLACE_EXISTING);
                Files.move(written, plugins.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
            return plan.action();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static int compare(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? Integer.parseInt(left[i]) : 0;
            int r = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }
}
