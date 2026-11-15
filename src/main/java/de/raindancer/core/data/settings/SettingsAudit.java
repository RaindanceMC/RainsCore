package de.raindancer.core.data.settings;

import de.raindancer.core.platform.log.LogChannel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What is wrong with a configuration that nothing else can notice.
 *
 * <h2>The gap this fills</h2>
 * {@link SettingsStore} already catches everything wrong with a settings <em>file</em>: a value of the
 * wrong type, a number outside its range, a key that is missing, YAML that will not parse. Every one of
 * those is about a single setting, and every one has a right answer the store can apply on its own.
 *
 * <p>The mistakes that survive all of that are the ones where <b>each value is individually valid and the
 * combination is wrong</b>. A world border told to close slower than the round is long. A cooldown longer
 * than the thing it gates. A minimum above its own maximum. A schedule whose later entries fall past the
 * end of the event. Nothing throws, nothing is out of range, the plugin comes up perfectly healthy — and it
 * is not the thing that was configured.
 *
 * <p>Those cannot be found by a schema, because they are not properties of a setting. They are properties
 * of a plugin's own subject matter, so only the plugin can work them out. What Core can do — and what this
 * is — is give every plugin the same shape to say them in, and the same place they come out.
 *
 * <h2>Why they are warnings and never refusals</h2>
 * Because every one of them is a judgement about how something will behave rather than a fact about
 * whether it can run, and an owner may have chosen any of them deliberately. A plugin that refused to start
 * over one would be deciding what somebody's server is.
 *
 * <p>So this logs and returns. The one thing it will not do is stay quiet: the whole value is being read
 * the evening before by whoever set it up, rather than discovered while it matters.
 *
 * <h2>The failure this class has to avoid itself</h2>
 * <b>Crying wolf.</b> A block of warnings that is routinely wrong gets scrolled past, including the line
 * that was right — which leaves a server worse off than having no audit at all. Two things follow, and both
 * are the caller's responsibility rather than something this can enforce:
 *
 * <ul>
 *   <li><b>A normal starting state is not a finding.</b> A fresh install with nothing configured yet must
 *       produce an empty audit, or the warning block appears on every first boot and is learned as noise.</li>
 *   <li><b>Every message says what to do.</b> "border.max-edge-speed is low" is not actionable;
 *       "the border cannot finish closing before the 180 min round ends — trigger the last phase earlier or
 *       raise the ceiling above 1.25" is.</li>
 * </ul>
 *
 * <h2>Using it</h2>
 * <pre>{@code
 * SettingsAudit audit = new SettingsAudit();
 * if (runUp.compareTo(round) >= 0) {
 *     audit.broken("The countdown and grace period together are %s, which is the whole %s round."
 *             .formatted(describe(runUp), describe(round)));
 * }
 * if (waves.compareTo(round) > 0) {
 *     audit.questionable("The later monster waves fall past the end of the round and will never arrive.");
 * }
 * audit.report(log, "This configuration");
 * }</pre>
 *
 * <p>Not thread-safe, and deliberately not: an audit is built in one go by one caller, usually during
 * {@code onEnable}, and then read. Making it safe for concurrent use would suggest it was meant to be held
 * on to, which it is not — it is a value describing one moment's configuration.
 */
public final class SettingsAudit {

    /** How much a finding matters. */
    public enum Severity {

        /**
         * The configuration contradicts itself: something it asks for cannot happen.
         *
         * <p>Not "the plugin will crash" — nothing here crashes. It is "this will not do what it says",
         * which is worse in the specific sense that nobody finds out.
         */
        BROKEN,

        /**
         * It will work, and it will probably not behave the way whoever wrote it expected.
         *
         * <p>The severity for a judgement somebody may genuinely have made on purpose. Anything that could
         * reasonably be deliberate belongs here rather than above, because {@link #BROKEN} is the level
         * people are meant to act on and it only keeps that meaning while it is rare.
         */
        QUESTIONABLE
    }

    /** One thing worth saying, and how loudly. */
    public record Finding(Severity severity, String message) {

        public Finding {
            Objects.requireNonNull(severity, "severity");
            message = message == null ? "" : message.strip();
        }

        public boolean isBroken() {
            return severity == Severity.BROKEN;
        }
    }

    private final List<Finding> findings = new ArrayList<>();

    /** Something the configuration asks for that cannot happen. Blank messages are ignored. */
    public SettingsAudit broken(String message) {
        return add(Severity.BROKEN, message);
    }

    /** Something that will work and probably surprise somebody. Blank messages are ignored. */
    public SettingsAudit questionable(String message) {
        return add(Severity.QUESTIONABLE, message);
    }

    /** Adds a finding only when a condition holds — for the common {@code if} that would wrap one. */
    public SettingsAudit brokenIf(boolean condition, String message) {
        return condition ? broken(message) : this;
    }

    /** Adds a finding only when a condition holds. */
    public SettingsAudit questionableIf(boolean condition, String message) {
        return condition ? questionable(message) : this;
    }

    private SettingsAudit add(Severity severity, String message) {
        // A blank message is dropped rather than logged as an empty bullet: a caller building a sentence
        // from a value that turned out to be absent would otherwise produce a warning that says nothing,
        // and a warning that says nothing is the fastest way to teach somebody to skip the block.
        if (message != null && !message.isBlank()) {
            findings.add(new Finding(severity, message));
        }
        return this;
    }

    /** Everything found, worst first. Stable within a severity, so the order is the order it was noticed. */
    public List<Finding> findings() {
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.comparing(finding -> finding.isBroken() ? 0 : 1));
        return List.copyOf(sorted);
    }

    /** Only the ones that will not do what they say. */
    public List<Finding> broken() {
        return findings.stream().filter(Finding::isBroken).toList();
    }

    public boolean isEmpty() {
        return findings.isEmpty();
    }

    public int size() {
        return findings.size();
    }

    /** Whether anything found will not do what it says. */
    public boolean hasBroken() {
        return findings.stream().anyMatch(Finding::isBroken);
    }

    /**
     * Puts the findings in the log, in one block, worst first.
     *
     * <p>Nothing at all when there is nothing to say — an audit that announced itself every boot to report
     * that everything was fine would be the same noise as one that cried wolf, and it would train the same
     * habit.
     *
     * <p>One block rather than a line per finding scattered through startup, because the value of these is
     * comparative: three of them together usually name one mistake, and a reader who sees them apart
     * chases three.
     *
     * @param subject what is being audited, as a sentence's subject — "This configuration", "The border"
     * @return how many findings were reported, so a caller can act on there having been any
     */
    public int report(LogChannel log, String subject) {
        List<Finding> all = findings();
        if (log == null || all.isEmpty()) {
            return all.size();
        }
        long broken = all.stream().filter(Finding::isBroken).count();

        String heading = broken > 0
                ? "{} has {} problem(s), {} that will not work as written:"
                : "{} has {} thing(s) worth a look:";
        if (broken > 0) {
            log.warn(heading, subject, all.size(), broken);
        } else {
            log.warn(heading, subject, all.size());
        }

        for (Finding finding : all) {
            // [!] and [?] rather than two log levels. Both are warnings — nothing here is an error, since
            // nothing is broken in the sense of throwing — and splitting them across levels would hide the
            // questionable ones on a server that filters to warn-and-above, which is most of them.
            log.warn(finding.isBroken() ? "  [!] {}" : "  [?] {}", finding.message());
        }

        log.warn("None of this stops the plugin running. Every one of them may have been chosen on "
                + "purpose. They are said out loud so that the ones that were not are noticed now.");
        return all.size();
    }

    /** The findings as lines, for a screen or an API that wants them without a log. */
    public List<String> lines() {
        return findings().stream()
                .map(finding -> (finding.isBroken() ? "[!] " : "[?] ") + finding.message())
                .toList();
    }
}
