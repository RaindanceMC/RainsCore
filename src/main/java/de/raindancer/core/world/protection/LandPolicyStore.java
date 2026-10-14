package de.raindancer.core.world.protection;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The flag decisions an admin made, on disk.
 *
 * <p>{@link LandPolicies} lives in memory and starts from what each flag says about itself. Without this,
 * turning a flag off lasted until the next restart — which is worse than not offering the setting at all,
 * because the server then behaves differently after a restart than it did before one and nothing says why.
 *
 * <h2>What gets written</h2>
 * Only what differs from the built-in behaviour. A file naming all twenty-eight flags at their default
 * values is unreadable for the two lines that matter, and it freezes those defaults: improve one in a
 * release and every existing server keeps the old value forever, because their file spells it out.
 *
 * <p>The shape is the one the standalone plugin already used —
 *
 * <pre>
 * flags:
 *   pvp:
 *     policy: forced-off
 *     default: false
 * </pre>
 *
 * <p>— so an upgrading server's {@code config.yml} can be pointed straight at this and keeps meaning what
 * it said. That file is the only record of what its admin decided.
 *
 * <h2>What a bad line costs</h2>
 * A flag this version does not have, and a policy word nobody recognises, are both skipped rather than
 * guessed at or thrown over. One stale key must not cost an admin every other decision in the file, and
 * guessing at a protection setting is how a server ends up unprotected quietly. A file that will not parse
 * at all is reported through {@link #problem()} rather than passed off as an empty one.
 */
public final class LandPolicyStore {

    private static final LogChannel log = Log.of("land");
    private static final String FLAGS = "flags";

    private final Path file;
    /** The network's one set of decisions: a flag forced off on one server is forced off on all of them. */
    private final YamlStore store;
    private volatile String problem;

    public LandPolicyStore(Path file) {
        this.file = file;
        this.store = file == null ? null : YamlStore.network(file);
    }

    /** Runs {@code reload} when another server changes the decisions. */
    public void onChange(Runnable reload) {
        if (store != null) {
            store.onChange(reload);
        }
    }

    /** What the file says, or everything as it ships when there is no file. */
    public LandPolicies load() {
        LandPolicies policies = LandPolicies.builtIn();
        problem = null;
        if (store == null || !store.exists()) {
            return policies;
        }

        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            String why = String.join("; ", store.problems());
            problem = file.getFileName() + " could not be read (" + why + "); every flag is at its built-in setting";
            log.warn("{} could not be read ({}). Every flag is at its built-in setting until this is "
                    + "fixed — your saved flag decisions are NOT being applied.", file.getFileName(), why);
            return policies;
        }

        ConfigurationSection flags = yaml.getConfigurationSection(FLAGS);
        if (flags == null) {
            return policies;
        }
        for (String key : flags.getKeys(false)) {
            Optional<LandFlag> flag = LandFlag.byKey(key);
            if (flag.isEmpty()) {
                // A flag this version no longer has. Left in the file, ignored here.
                log.debug("{} names a flag this version does not have ({}); ignoring it.",
                        file.getFileName(), key);
                continue;
            }
            ConfigurationSection line = flags.getConfigurationSection(key);
            if (line == null) {
                continue;
            }
            if (line.isString("policy")) {
                FlagPolicy.byKey(line.getString("policy"))
                        .ifPresentOrElse(
                                found -> policies.policy(flag.get(), found),
                                () -> log.warn("{}: '{}' is not a policy I know for flag '{}'. Leaving it "
                                                + "as it ships rather than guessing.",
                                        file.getFileName(), line.getString("policy"), key));
            }
            if (line.isBoolean("default")) {
                policies.flagDefault(flag.get(), line.getBoolean("default"));
            }
        }
        return policies;
    }

    /** Writes the decisions down, and removes the file when there are none left. */
    public void save(LandPolicies policies) throws IOException {
        if (file == null) {
            return;
        }
        if (problem != null && store.exists()) {
            // Loaded as built-ins because it would not parse: what is in memory is not what the admin
            // decided, and writing it — or deleting the file for having "no changes" — loses every
            // decision in there. Set aside first, and refuse if even that fails.
            if (store.quarantine().isEmpty()) {
                throw new IOException(file.getFileName() + " could not be read and could not be set "
                        + "aside, so it was not written over");
            }
            problem = null;
        }
        LandPolicies.Changed changed = policies.changed();
        if (changed.isEmpty()) {
            // Not an empty file: no file. An admin who undid every change should not be left with
            // something on disk implying they made one.
            if (!store.delete()) {
                throw new IOException(file.getFileName() + " could not be removed");
            }
            return;
        }

        // Through YamlStore: a temporary, then an atomic move. Written in place, a crash or a full disk
        // mid-write left half a file, and every flag fell back to its built-in on the next start.
        boolean written = store.write(yaml -> {
            yaml.options().setHeader(List.of(
                    "What this server decided about the land flags.",
                    "",
                    "Only decisions that differ from the built-in behaviour are written here, so a flag that is",
                    "absent is a flag as it ships — and improving a built-in default in a later release reaches",
                    "this server rather than being overruled by a line nobody meant to write.",
                    "",
                    "  policy:  available | forced-on | forced-off | disabled",
                    "  default: what a new claim starts with while the policy is 'available'",
                    "",
                    "Set through /claimadmin flags rather than by hand, unless you prefer a file."));

            // Written in the order the flags are declared, grouped as Core groups them, so the file reads in
            // the same order as the screen an admin just used.
            // EnumMap's copy constructor refuses an empty map, and exactly one of these two is routinely empty.
            Map<LandFlag, FlagPolicy> policyChanges = new EnumMap<>(LandFlag.class);
            policyChanges.putAll(changed.flagPolicies());
            Map<LandFlag, Boolean> defaultChanges = new EnumMap<>(LandFlag.class);
            defaultChanges.putAll(changed.flagDefaults());
            for (LandFlag flag : LandFlag.values()) {
                FlagPolicy policy = policyChanges.get(flag);
                Boolean value = defaultChanges.get(flag);
                if (policy != null) {
                    yaml.set(FLAGS + "." + flag.key() + ".policy", policy.key());
                }
                if (value != null) {
                    yaml.set(FLAGS + "." + flag.key() + ".default", value);
                }
            }

        });
        if (!written) {
            throw new IOException("could not write " + file + "; it is unchanged");
        }
    }

    /** What went wrong reading the file, if anything did. */
    public Optional<String> problem() {
        return Optional.ofNullable(problem);
    }
}
