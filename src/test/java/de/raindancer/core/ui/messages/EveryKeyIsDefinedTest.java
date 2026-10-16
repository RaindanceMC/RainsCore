package de.raindancer.core.ui.messages;

import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message key the code asks for exists in the file, and every key in the file is asked for.
 *
 * <h2>Why this is worth a test</h2>
 * Because the failure is invisible to the compiler and nearly invisible to a reviewer. A key is a
 * string: rename a line in {@code messages.yml} and the code still compiles, still runs, and shows
 * the player {@code <invsee.opened>} instead of a sentence. Nobody notices until somebody hits that
 * exact path — which, for the messages that matter most, means somebody being refused something.
 *
 * <p>The other direction matters too, if less: a key in the file that nothing ever asks for is a line
 * somebody may spend time translating for no effect, and is usually the leftover of a rename.
 *
 * <h2>How it reads the code</h2>
 * By pattern, over the source. That is coarse — a key built by concatenation cannot be seen, and
 * those are listed below as known exceptions rather than pretended away. It still catches the whole
 * class of mistake that actually happens, which is a literal key that no longer matches the file.
 */
@DisplayName("every message key")
class EveryKeyIsDefinedTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/core");
    private static final Path MESSAGES = Path.of("src/main/resources/messages.yml");

    /**
     * A string literal that looks like a message key: dotted, lower case, hyphens allowed.
     *
     * <p>Deliberately not "every string passed to a Messages method": keys are also carried in enum
     * constants and returned from switch expressions, and a pattern that only looked at call sites
     * would miss every one of those — which is where most of them live.
     */
    private static final Pattern KEY = Pattern.compile(
            "\"([a-z][a-z0-9]*(?:-[a-z0-9]+)*(?:\\.[a-z][a-z0-9]*(?:-[a-z0-9]+)*)+)\"");

    /**
     * Keys the code builds rather than writes out, and so cannot be found by reading it.
     *
     * <p>Each one is a real key that a pattern cannot see. Listed here rather than left out, so that
     * the file's unused-key half stays honest: without this, every one of these would look like a
     * line nothing uses.
     */
    private static final Set<String> BUILT_AT_RUNTIME = builtAtRuntime();

    private static Set<String> builtAtRuntime() {
        Set<String> keys = new LinkedHashSet<>(Set.of(
                // PunishmentGuard appends "-temporary" to the mute and freeze keys.
                "punishment.muted-temporary",
                "punishment.frozen-temporary",
                // Written out only in the units a Duration happens to have.
                "punishment.length.forever",
                "punishment.length.days",
                "punishment.length.hours",
                "punishment.length.minutes",
                "punishment.length.seconds"));

        // Land carries no wording in its enums — see LandFlag's class comment. Every name and description
        // is looked up through nameKey()/descriptionKey(), so a pattern over the source cannot see any of
        // them.
        //
        // Generated from the enums rather than listed, which keeps the rule sharp in both directions: a key
        // in the file that no constant produces is still reported as unused, and a constant whose key is
        // missing from the file is still reported by the other half of this test.
        for (LandFlag flag : LandFlag.values()) {
            keys.add(flag.nameKey());
            keys.add(flag.descriptionKey());
        }
        for (LandAction action : LandAction.values()) {
            keys.add(action.nameKey());
            keys.add(action.descriptionKey());
        }
        // FriendCommand and PartyCommand answer with "friends."/"party." + the outcome's name.
        for (de.raindancer.core.social.friends.Friends.Outcome outcome : de.raindancer.core.social.friends.Friends.Outcome.values()) {
            keys.add("friends." + outcome.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        }
        for (de.raindancer.core.social.party.Parties.Outcome outcome : de.raindancer.core.social.party.Parties.Outcome.values()) {
            keys.add("party." + outcome.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        }
        for (LandAudience audience : LandAudience.values()) {
            keys.add(audience.nameKey());
            keys.add(audience.descriptionKey());
        }
        for (de.raindancer.core.world.protection.LandFlagGroup group
                : de.raindancer.core.world.protection.LandFlagGroup.values()) {
            keys.add(group.nameKey());
            keys.add(group.descriptionKey());
        }
        return Set.copyOf(keys);
    }

    /** Dotted paths that are not message keys at all, however much they look like ones. */
    private static final Pattern NOT_A_KEY = Pattern.compile(
            ".*\\.(java|yml|yaml|db|dat|log|txt|zip|png|json|nbt|jar)$"
                    + "|^(org|com|net|java|javax|io|de)\\..*"
                    + "|^minecraft:.*|.*:.*");

    private static String yaml() throws IOException {
        return Files.readString(MESSAGES);
    }

    /** Every key in the file, flattened to dotted form the way {@link Messages} flattens it. */
    private static Set<String> keysInFile() throws IOException {
        Messages messages = new Messages(Path.of("build", "no-such-file.yml"));
        messages.load(Files.newInputStream(MESSAGES));
        return new LinkedHashSet<>(messages.keys());
    }

    /** Every dotted literal in the source that could plausibly be a message key. */
    private static Set<String> keysInCode() throws IOException {
        Set<String> found = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    String candidate = matcher.group(1);
                    if (!NOT_A_KEY.matcher(candidate).matches()) {
                        found.add(candidate);
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("that the code asks for is in messages.yml")
    void everyKeyUsedIsDefined() throws IOException {
        Set<String> defined = keysInFile();
        // Only the ones that are unmistakably ours. A dotted string in the source might be anything,
        // and asserting that every one of them is a message would be a test about the pattern rather
        // than about the messages.
        List<String> missing = new ArrayList<>();
        for (String key : keysInCode()) {
            String topLevel = key.substring(0, key.indexOf('.'));
            boolean ourNamespace = defined.stream()
                    .anyMatch(known -> known.startsWith(topLevel + "."));
            if (ourNamespace && !defined.contains(key)) {
                missing.add(key);
            }
        }

        assertThat(missing)
                .as("these keys are asked for in the code and are not in messages.yml, so a player "
                        + "on that path is shown the key in angle brackets instead of a sentence")
                .isEmpty();
    }

    @Test
    @DisplayName("in messages.yml is asked for somewhere")
    void everyKeyDefinedIsUsed() throws IOException {
        Set<String> used = keysInCode();
        List<String> unused = new ArrayList<>();
        for (String key : keysInFile()) {
            if (key.equals(Messages.PREFIX_KEY) || used.contains(key)
                    || BUILT_AT_RUNTIME.contains(key)) {
                continue;
            }
            unused.add(key);
        }

        assertThat(unused)
                .as("these keys are in messages.yml and nothing asks for them. Either something "
                        + "should, or they are the leftovers of a rename and somebody will translate "
                        + "them for nothing. A key genuinely built at runtime belongs in "
                        + "BUILT_AT_RUNTIME, with a note saying where")
                .isEmpty();
    }

    @Test
    @DisplayName("is reachable through the loader, nesting and all")
    void theFileLoads() throws IOException {
        Messages messages = new Messages(Path.of("build", "no-such-file.yml"));
        messages.load(Files.newInputStream(MESSAGES));

        assertThat(messages.problems())
                .as("the bundled file failing to load means every message shows its key")
                .isEmpty();
        assertThat(messages.keys()).hasSizeGreaterThan(40);
        assertThat(messages.has(Messages.PREFIX_KEY)).isTrue();
    }

    @Test
    @DisplayName("has a comment above it, so somebody editing knows what they are changing")
    void theFileIsDocumented() throws IOException {
        String text = yaml();

        assertThat(text)
                .as("a message file with no explanation of MiniMessage or of the placeholders is a "
                        + "file people break and then blame")
                .contains("MiniMessage")
                .contains("PLACEHOLDER");
        // Whitespace-insensitive, so reflowing a comment does not fail the build. The phrase itself
        // has to stay on one line, though — a comment marker landing in the middle of it is exactly
        // how this assertion failed the first time.
        assertThat(text.replaceAll("\\s+", " "))
                .as("somebody has to be told their edits survive an update, or they will not make "
                        + "any")
                .contains("never overwritten");
    }
}
