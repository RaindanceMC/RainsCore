package de.raindancer.core.ui.messages;

import de.raindancer.core.data.store.YamlStore;

import net.kyori.adventure.audience.Audience;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.text.Markup;
import de.raindancer.core.ui.text.Text;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Every message a plugin says, in a file somebody can edit.
 *
 * <h2>Why Core owns this</h2>
 * Because three plugins had written it and each got a different part wrong. It is boilerplate — but
 * with one hard rule in it: a key the owner's file does not have <b>must</b> fall back to the one the
 * plugin shipped. Get that wrong and a translation three versions old blanks out every message added
 * since, and the owner has no way of telling which of their edits did it.
 *
 * <p>Two more rules that only one of the three copies had. Anything a player typed is escaped, so a
 * home called {@code <red>} is nine characters rather than a colour change. And markup with a typo in
 * it still renders: a plugin that throws while refusing something has turned a refusal into a stack
 * trace, in front of the player it was refusing.
 *
 * <h2>Using it</h2>
 * <pre>{@code
 * Messages messages = new Messages(getDataFolder().toPath().resolve("messages.yml"));
 * messages.load(getResource("messages.yml"));
 * messages.writeIfMissing(getResource("messages.yml"));
 *
 * player.sendMessage(messages.prefixed("claimed", "blocks", 256));
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * Safe to read from any thread. {@link #load} touches disk and should not be called on a timer.
 */
public final class Messages {

    private static final LogChannel log = Log.of("messages");
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** The key the prefix lives under, because every one of the three copies used this name. */
    public static final String PREFIX_KEY = "prefix";

    private final Path file;

    /**
     * The four places a message can come from, in the order they beat each other.
     *
     * <p>Bottom to top: what the jar shipped, what a plugin supplied in code, what the owner wrote,
     * and what a plugin insists on. The middle two are the ones this class gained later, and the
     * ordering between them is the whole design:
     *
     * <ul>
     *   <li>A <b>{@link #define}</b> is a <em>default</em>, and the lowest layer of the four: it fills
     *       a key nobody else has. Both the jar and the owner's file beat it — the jar because its
     *       lines are what an owner reads to learn what they may change, and the file because
     *       somebody who edits a line has to get that line or the file is decoration.</li>
     *   <li>A <b>{@link #force}</b> beats the file. For the few texts that must not be freely
     *       editable, and for switching wording at runtime. Rare on purpose: every use of it is a
     *       line in the owner's file that silently does nothing.</li>
     * </ul>
     */
    /** What the owner wrote. Beats the jar and anything a plugin merely suggested. */
    private volatile Map<String, Object> theirs = Map.of();
    /** What the plugin shipped in its jar. The floor: nothing is ever missing from here. */
    private volatile Map<String, Object> shipped = Map.of();
    /** Defaults a plugin supplied in code. Below the owner's file. */
    private final Map<String, Object> defined = new ConcurrentHashMap<>();
    /** Wording a plugin insists on. Above everything. */
    private final Map<String, Object> forced = new ConcurrentHashMap<>();

    /**
     * A prefix per top-level section, so a message is signed by the plugin it came from.
     *
     * <p>There is one Messages on a server and one host prefix, and every module plugin sets that
     * prefix to its own brand as it starts — so the last one to enable signed everything. On a live
     * server the claims plugin's "You were shown out of …" arrived as <em>Moderation »</em>, which is
     * a sentence attributed to the wrong plugin. A module now claims the sections it supplies, and
     * anything unclaimed still falls back to the host.
     */
    private final Map<String, Supplier<String>> sectionPrefixes =
            new ConcurrentHashMap<>();

    /** What the host says it is called; see prefixFrom. Null means read the prefix key. */
    private volatile Supplier<String> prefixSource;

    private final List<String> problems = new CopyOnWriteArrayList<>();
    private final List<String> missing = new CopyOnWriteArrayList<>();

    public Messages(Path file) {
        this.file = file;
    }

    /**
     * Wording earlier versions shipped, key by key: a line in the owner's file that still reads exactly
     * like one of these was never theirs, and takes this version's wording instead.
     */
    private final Map<String, java.util.Set<Object>> retired = new ConcurrentHashMap<>();

    /**
     * Teaches this an older version's bundled messages.yml, so the lines a server still has unchanged
     * from it upgrade to the current wording on the next {@link #load}. The file on disk is not
     * rewritten — whatever the owner changed is theirs, and stays exactly as they left it.
     *
     * @param olderBundled an earlier release's own messages.yml; closed here
     */
    public void retired(InputStream olderBundled) {
        if (olderBundled == null) {
            return;
        }
        Map<String, Object> old = new LinkedHashMap<>();
        try (InputStream stream = olderBundled) {
            flatten(parse(stream), "", old);
        } catch (IOException | RuntimeException broken) {
            log.warn("An older version's messages could not be read ({}); lines from it stay as they are.",
                    broken.getMessage());
            return;
        }
        old.forEach((key, value) -> retired.computeIfAbsent(key, any -> ConcurrentHashMap.newKeySet()).add(value));
    }

    // ------------------------------------------------------------------------------- tone

    /** How the server talks: with a dry joke after a refusal, or without one. */
    public enum Tone { PLAYFUL, SERIOUS }

    private volatile Tone tone = Tone.PLAYFUL;

    /** The plain-spoken version of the lines that have a joke in them, from Core and every module. */
    private final Map<String, Object> serious = new ConcurrentHashMap<>();

    public Tone tone() {
        return tone;
    }

    /** Takes hold on the next message; nothing is cached anywhere that would need telling. */
    public void tone(Tone tone) {
        this.tone = tone == null ? Tone.PLAYFUL : tone;
    }

    /**
     * Takes a {@code messages-serious.yml}: the same keys as the playful file, said plainly.
     *
     * <p>Only the lines that need it — a key missing here keeps its one wording in both tones. Like
     * {@link #defineFrom}, the first file to name a key keeps it, so load order between modules cannot
     * reword somebody else's lines.
     *
     * @param bundled the serious file, from {@code getResourceAsStream}; closed here, null is nothing
     * @return how many lines were taken up
     */
    public int seriousFrom(InputStream bundled) {
        if (bundled == null) {
            return 0;
        }
        Map<String, Object> wording = new LinkedHashMap<>();
        try (InputStream stream = bundled) {
            flatten(parse(stream), "", wording);
        } catch (Exception broken) {
            problems.add("a serious wording file could not be read (" + broken.getMessage() + ")");
            log.warn("A serious wording file could not be read ({}); those lines keep their playful "
                    + "wording in serious mode. This is a fault in the plugin, not in your configuration.",
                    broken.getMessage());
            return 0;
        }
        int taken = 0;
        for (Map.Entry<String, Object> line : wording.entrySet()) {
            if (serious.putIfAbsent(line.getKey(), line.getValue()) == null) {
                taken++;
            }
        }
        return taken;
    }

    /**
     * A line with a built-in fallback, through the running Core's wording when there is one — for the
     * few places that say something before, or without, a Messages of their own.
     */
    public static Component spoken(String key, String builtIn, Object... values) {
        if (de.raindancer.core.RainsCore.isAvailable()) {
            Messages messages = de.raindancer.core.RainsCore.get().messages();
            if (messages.has(key)) {
                return messages.get(key, values);
            }
        }
        return Text.render(builtIn, values);
    }

    // ---------------------------------------------------------------------------- loading

    /**
     * Reads the owner's file, over the bundled defaults.
     *
     * @param bundledDefaults the plugin's own {@code messages.yml}, from {@code getResource} —
     *                        closed here, so the caller does not have to
     */
    public void load(InputStream bundledDefaults) {
        problems.clear();
        missing.clear();

        Map<String, Object> defaults = new LinkedHashMap<>();
        if (bundledDefaults != null) {
            try (InputStream stream = bundledDefaults) {
                flatten(parse(stream), "", defaults);
            } catch (IOException | RuntimeException broken) {
                // The plugin's own file being unreadable is the plugin's bug, not the owner's, and
                // it leaves every message showing its key. Loud on purpose.
                problems.add("the bundled messages could not be read (" + broken.getMessage() + ")");
                log.error("The bundled messages.yml could not be read; every message will show its "
                        + "key instead. This is a fault in the plugin, not in your configuration.");
            }
        }
        shipped = Map.copyOf(defaults);

        Map<String, Object> owner = new LinkedHashMap<>();
        Optional<String> ownersText = text();
        if (ownersText.isPresent()) {
            try {
                flatten(parse(ownersText.get()), "", owner);
            } catch (Exception broken) {
                // Their file, their mistake — and it costs them the translation rather than every
                // message the plugin has.
                problems.add(file.getFileName() + " could not be read (" + broken.getMessage()
                        + "); the built-in messages are being used");
                log.warn("{} could not be read ({}). The built-in messages are being used instead.",
                        file.getFileName(), broken.getMessage());
                owner.clear();
            }
        }
        // A line still exactly as an older version shipped it is ours, not theirs: it takes this
        // version's wording. Without this, a server keeps the wording of whatever release first wrote
        // its file, for ever.
        int upgraded = 0;
        for (var line : new ArrayList<>(owner.entrySet())) {
            Object current = shipped.get(line.getKey());
            java.util.Set<Object> old = retired.get(line.getKey());
            if (current != null && old != null && old.contains(line.getValue())
                    && !current.equals(line.getValue())) {
                owner.remove(line.getKey());
                upgraded++;
            }
        }
        if (upgraded > 0) {
            log.info("{} message(s) were still as an older version wrote them and now use this "
                    + "version's wording; lines you changed are untouched.", upgraded);
        }
        theirs = Map.copyOf(owner);

        for (String key : shipped.keySet()) {
            if (!theirs.containsKey(key)) {
                missing.add(key);
            }
        }

        if (!missing.isEmpty() && !theirs.isEmpty()) {
            log.info("{} is missing {} message(s) that this version added; the built-in wording is "
                    + "used for those.", file == null ? "messages.yml" : file.getFileName(),
                    missing.size());
        }
    }

    /**
     * Writes the bundled file out, if the owner does not have one.
     *
     * <p>Never over one they have. An owner who cannot see the file cannot edit it and will not know
     * it exists; an owner whose edits were overwritten will not use the plugin again.
     *
     * @return whether a file was written
     */
    public boolean writeIfMissing(InputStream bundledDefaults) {
        if (file == null || bundledDefaults == null || text().isPresent()) {
            return false;
        }
        try (InputStream stream = bundledDefaults) {
            return document().writeTextIfAbsent(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException failure) {
            log.warn("Could not write {} ({}); the built-in messages are being used.",
                    file, failure.getMessage());
            return false;
        }
    }

    /**
     * Adds the keys this version introduced to a file that predates them, and changes nothing else.
     *
     * <p>{@link #writeIfMissing} is right the first time and useless every time after: a server that
     * upgrades across a release keeps a file that simply does not mention the new messages. Nothing
     * breaks, because every read falls back to the wording in the jar, but an owner opening the file
     * cannot see the new lines, let alone reword them. After one release here that was over a hundred.
     *
     * <p>The rules, each of which is a way this could go wrong:
     * <ul>
     *   <li><b>An existing key is never touched</b>, whatever its value. Somebody who set a message to
     *       an empty string wanted silence, and filling it back in is the merge undoing a decision.</li>
     *   <li><b>A key the jar no longer has is left alone.</b> It may be a leftover, or something a fork
     *       reads. It is reported through {@link #problems()}, never removed.</li>
     *   <li><b>Nothing is written when nothing is missing</b>, down to the file's timestamp.</li>
     *   <li><b>A file that will not parse is left exactly as it is.</b> Half a parse is somebody
     *       mid-edit, or a write cut short by a full disk, and rewriting from it loses the rest.</li>
     *   <li><b>The old file is copied aside first.</b> This is the one place the plugin edits something
     *       a person wrote, and being able to undo it is the difference between a merge owners accept
     *       and one they turn off.</li>
     * </ul>
     *
     * <p>The merge is done on the text rather than by re-dumping the parsed tree, which would be a few
     * lines shorter and would throw away every comment in the file along with the owner's spacing and
     * quoting style. A messages.yml is mostly comments explaining what each key does.
     *
     * @return how many keys were added; {@code -1} if there was no file and the bundled one was
     *         written whole; {@code -2} if the file could not be read and was left alone
     */
    public int mergeMissing(InputStream bundledDefaults) {
        if (file == null || bundledDefaults == null) {
            return -2;
        }
        if (text().isEmpty()) {
            return writeIfMissing(bundledDefaults) ? -1 : -2;
        }

        Map<String, Object> fromJar = new LinkedHashMap<>();
        try (InputStream stream = bundledDefaults) {
            flatten(parse(stream), "", fromJar);
        } catch (IOException | RuntimeException broken) {
            log.error("The bundled messages.yml could not be read; {} was left untouched.",
                    file.getFileName());
            return -2;
        }

        String text;
        Map<String, Object> owned = new LinkedHashMap<>();
        try {
            text = text().orElseThrow(() -> new IOException("it is gone"));
            flatten(parse(text), "", owned);
        } catch (Exception broken) {
            // Deliberately not repaired. Somebody is mid-edit, or the disk filled during a write, and
            // a rewrite from half a parse loses whatever is not in the half that parsed.
            log.warn("{} could not be read ({}), so no new messages were merged into it. Fix the file "
                    + "and restart; nothing was changed.", file.getFileName(), broken.getMessage());
            return -2;
        }

        List<String> added = new ArrayList<>();
        for (String key : fromJar.keySet()) {
            if (!owned.containsKey(key)) {
                added.add(key);
            }
        }
        if (added.isEmpty()) {
            return 0;
        }

        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        for (String key : added) {
            insert(lines, key, fromJar.get(key));
        }

        Path backup = file.resolveSibling(file.getFileName() + "."
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".bak");
        YamlStore before = document().inDatabase() ? YamlStore.network(backup) : new YamlStore(backup);
        if (!before.writeText(text) || !document().writeText(String.join("\n", lines))) {
            log.warn("Could not update {}; the built-in wording is used for the new messages.", file.getFileName());
            return -2;
        }
        log.info("Added {} new message(s) to {}; your wording was kept and the previous one is kept as {}.",
                added.size(), file.getFileName(), backup.getFileName());
        return added.size();
    }

    /**
     * Puts one key into the file's text, under whichever part of its path already exists.
     *
     * <p>{@code claim.flag.pvp} in a file that has a {@code claim:} section but no {@code flag:} under
     * it appends {@code flag:} to the end of {@code claim:} and {@code pvp:} under that — so an added
     * key lands with its relatives rather than at the bottom under a second copy of a heading that is
     * already there. A duplicate heading is not a cosmetic problem: SnakeYAML rejects the file outright
     * on the next load, which turns "you have new messages" into "you have no messages".
     */
    private static void insert(List<String> lines, String key, Object value) {
        String[] parts = key.split("\\.");

        // The deepest ancestor that is already written, and where its last line is.
        int depth = 0;
        int after = lines.size();
        int indent = 0;
        for (int part = 1; part < parts.length; part++) {
            String ancestor = String.join(".", List.of(parts).subList(0, part));
            int header = lineOf(lines, ancestor);
            if (header < 0) {
                break;
            }
            depth = part;
            indent = indentOf(lines.get(header)) + 2;
            after = endOfSection(lines, header, indentOf(lines.get(header)));
        }

        List<String> written = new ArrayList<>();
        for (int part = depth; part < parts.length - 1; part++) {
            written.add(" ".repeat(indent) + parts[part] + ":");
            indent += 2;
        }
        written.addAll(render(" ".repeat(indent), parts[parts.length - 1], value));
        lines.addAll(after, written);
    }

    /** The line index of a dotted path's own line, or {@code -1}. */
    private static int lineOf(List<String> lines, String path) {
        String[] parts = path.split("\\.");
        int at = 0;
        int depth = 0;
        int inside = -1;
        for (int line = 0; line < lines.size(); line++) {
            String text = lines.get(line);
            if (text.isBlank() || text.stripLeading().startsWith("#")) {
                continue;
            }
            int indent = indentOf(text);
            if (indent != depth * 2) {
                if (indent < depth * 2 && at > 0) {
                    return -1;      // the section we were in ended without the key
                }
                continue;
            }
            String name = text.strip();
            int colon = name.indexOf(':');
            if (colon < 0) {
                continue;
            }
            if (!name.substring(0, colon).strip().equals(parts[at])) {
                continue;
            }
            inside = line;
            at++;
            if (at == parts.length) {
                return inside;
            }
            depth++;
        }
        return -1;
    }

    /** One past the last line belonging to the section whose header is at {@code header}. */
    private static int endOfSection(List<String> lines, int header, int headerIndent) {
        int end = header + 1;
        for (int line = header + 1; line < lines.size(); line++) {
            String text = lines.get(line);
            if (text.isBlank()) {
                continue;       // trailing blanks belong to whatever comes next, not to this section
            }
            if (indentOf(text) <= headerIndent) {
                break;
            }
            end = line + 1;
        }
        return end;
    }

    private static int indentOf(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }

    /** A key and its value as YAML lines, quoted the way the bundled file quotes things. */
    private static List<String> render(String indent, String name, Object value) {
        if (value instanceof List<?> items) {
            List<String> out = new ArrayList<>();
            out.add(indent + name + ":");
            for (Object item : items) {
                out.add(indent + "  - " + quoted(item));
            }
            return out;
        }
        return List.of(indent + name + ": " + quoted(value));
    }

    private static String quoted(Object value) {
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------------------------------------------------------------------- reading

    /**
     * One message, as it is written in the file.
     *
     * <p>A key nobody has anywhere comes back as the key itself in angle brackets, rather than as an
     * empty string: a blank in the middle of a sentence is a bug nobody can find, and the key at
     * least says which one is missing.
     */
    public String raw(String key) {
        Object found = lookUp(key);
        if (found == null) {
            String problem = "no message is defined for '" + key + "'";
            if (!problems.contains(problem)) {
                problems.add(problem);
                log.warn(problem);
            }
            return "<" + key + ">";
        }
        return String.valueOf(found);
    }

    /**
     * One message, filled in and rendered.
     *
     * @param values name, value, name, value — a player's own text is escaped, always
     */
    public Component get(String key, Object... values) {
        return render(fill(raw(key), values));
    }

    /** The same, with the prefix in front — the one belonging to whoever owns this key. */
    public Component prefixed(String key, Object... values) {
        return render(prefixFor(key) + fill(raw(key), values));
    }

    /**
     * The prefix for one message: its section's, or the host's.
     *
     * <p>The section is the first segment — {@code claim.none-here} belongs to {@code claim}. A key with
     * no dot has no section and takes the host's.
     */
    private String prefixFor(String key) {
        if (key != null) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                Supplier<String> owner = sectionPrefixes.get(key.substring(0, dot));
                if (owner != null) {
                    try {
                        String given = owner.get();
                        return given == null ? "" : given;
                    } catch (RuntimeException noBrandYet) {
                        // A prefix source that throws costs the prefix and nothing else — losing the
                        // message with it would be the framework swallowing command output over a
                        // decoration.
                        log.debug("A section prefix source failed ({}); falling back.",
                                noBrandYet.getMessage());
                    }
                }
            }
        }
        return prefix();
    }

    /**
     * Tells this server what it is called, so every message says so.
     *
     * <p>Without it, {@code prefixed()} reads the {@code prefix} message key — of which there is exactly one on
     * a server. RainsCore's own bundled wording defines <code>[Core]</code>, a module's wording arrives as a
     * <em>floor</em>, and the bundled file sits above a floor. So every message a module sent went out as
     * <code>[Core]</code> and the gradient tag it was branded with, already on every window title, was nowhere
     * in chat.
     *
     * <p>Usually {@code brand::chatPrefix}. Asked every time rather than copied, so a server renaming itself
     * does not need a restart to be believed — and because {@link de.raindancer.core.ui.chat.Brand} decides for
     * itself whether the tag is shown at all.
     *
     * <p>One identity per server, whether its features arrive as one plugin or as six modules: a player does not
     * care which jar a line came from. {@code null} puts it back to reading the key.
     */
    public void prefixFrom(Supplier<String> source) {
        this.prefixSource = source;
    }

    /**
     * The tag to put in front of a message.
     *
     * <p>A source that throws costs the prefix and nothing else. Losing the message with it would be the
     * framework swallowing somebody's command output over a decoration.
     */
    private String prefix() {
        Supplier<String> source = prefixSource;
        if (source != null) {
            try {
                String given = source.get();
                return given == null ? "" : given;
            } catch (RuntimeException noBrandYet) {
                log.debug("The message prefix source failed ({}); sending unprefixed.",
                        noBrandYet.getMessage());
                return "";
            }
        }
        return has(PREFIX_KEY) ? raw(PREFIX_KEY) : "";
    }

    /**
     * Sends a prefixed message. The normal case for command feedback.
     *
     * <p>Here rather than at every call site because {@code recipient.sendMessage(messages.prefixed(key, …))}
     * is the same line in three hundred places, and the one that forgets the prefix is the one nobody notices
     * until a player asks which plugin just talked to them.
     */
    public void send(Audience recipient, String key, Object... values) {
        if (recipient != null) {
            String text = prefixFor(key) + fill(raw(key), values);
            recipient.sendMessage(render(withPlaceholders(recipient, text)));
        }
    }

    /** The same without the prefix, for the rows of a list where a prefix per line is noise. */
    public void sendPlain(Audience recipient, String key, Object... values) {
        if (recipient != null) {
            String text = fill(raw(key), values);
            recipient.sendMessage(render(withPlaceholders(recipient, text)));
        }
    }

    /**
     * Resolves PlaceholderAPI's own {@code %placeholder%} syntax for a player, if that plugin is
     * installed.
     *
     * <p>Applied after this class's own {@code <name>} substitution and before the result is parsed
     * as MiniMessage, so whatever a placeholder expands to is treated exactly like the text it sits
     * beside — this class trusts none of it any more than it trusts the file it came from.
     *
     * <p>A no-op for anyone who is not a {@link Player} — the console and command blocks have nothing
     * for PlaceholderAPI to resolve against — and for a server with no PlaceholderAPI installed at
     * all, which is why this is guarded rather than called directly: the class is a compile-time-only
     * dependency and must never be touched unless the plugin manager says it is actually there.
     */
    private String withPlaceholders(Audience recipient, String text) {
        if (!(recipient instanceof Player player)
                || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return text;
        }
        try {
            return PlaceholderAPI.setPlaceholders(player, text);
        } catch (RuntimeException broken) {
            // A badly written expansion costs its own placeholders, never the message it sits in.
            log.debug("PlaceholderAPI failed to resolve a message's placeholders ({}); sending it "
                    + "as it is.", broken.getMessage());
            return text;
        }
    }

    /** Per list key: the order its lines are dealt in this round, and what was said last. */
    private final Map<String, Deck> decks = new ConcurrentHashMap<>();

    private static final class Deck {
        private List<?> dealtFrom = List.of();
        private final java.util.ArrayDeque<Integer> left = new java.util.ArrayDeque<>();
        private int last = -1;
    }

    /**
     * One line of a list, dealt rather than drawn: every line is said once before any is said twice,
     * and a round never starts with the line the last one ended on.
     *
     * <p>For lists people ask for on purpose and notice repeats in — jokes, roasts. Plain random picks
     * repeat one of sixty within about ten tries. Not prefixed: the caller decides what goes in front,
     * or sends it as somebody's chat line. A key that is not a list behaves like {@link #get}.
     */
    public Component fresh(String key, Object... values) {
        Object found = lookUp(key);
        if (!(found instanceof List<?> options) || options.isEmpty()) {
            return get(key, values);
        }
        int index;
        Deck deck = decks.computeIfAbsent(key, any -> new Deck());
        synchronized (deck) {
            if (!deck.dealtFrom.equals(options)) {
                // Reloaded, reworded or switched tone: a new list is a new game.
                deck.dealtFrom = List.copyOf(options);
                deck.left.clear();
                deck.last = -1;
            }
            if (deck.left.isEmpty()) {
                List<Integer> order = new ArrayList<>();
                for (int line = 0; line < options.size(); line++) {
                    order.add(line);
                }
                java.util.Collections.shuffle(order, ThreadLocalRandom.current());
                if (order.size() > 1 && order.getFirst() == deck.last) {
                    java.util.Collections.swap(order, 0, 1 + ThreadLocalRandom.current().nextInt(order.size() - 1));
                }
                deck.left.addAll(order);
            }
            index = deck.left.poll();
            deck.last = index;
        }
        return render(fill(String.valueOf(options.get(index)), values));
    }

    /**
     * One of several wordings for the same thing, chosen at random.
     *
     * <p>For the lines a player sees over and over — a refusal, an arrival. A key whose value is a list gets
     * one of its entries; a key with a single value behaves exactly like {@link #get}, so making a message
     * varied is editing {@code messages.yml} and changing nothing in code.
     *
     * <p>Prefixed, because the callers are all feedback. The one place this matters: a variant list with one
     * entry must not read differently from a plain key, or turning a message into a list would silently move
     * the prefix.
     */
    public Component variant(String key, Object... values) {
        Object found = lookUp(key);
        if (!(found instanceof List<?> options) || options.isEmpty()) {
            return prefixed(key, values);
        }
        Object chosen = options.size() == 1
                ? options.getFirst()
                : options.get(ThreadLocalRandom.current().nextInt(options.size()));
        // prefixFor rather than the raw prefix key: a variant is as much this module's sentence as any
        // other, and reading the key directly ignored both the host's brand and the section's.
        return render(prefixFor(key) + fill(String.valueOf(chosen), values));
    }

    /** A message that is several lines — a help page, a description. */
    public List<Component> lines(String key, Object... values) {
        Object found = lookUp(key);
        if (!(found instanceof List<?> list)) {
            return List.of(get(key, values));
        }
        List<Component> rendered = new ArrayList<>(list.size());
        for (Object line : list) {
            rendered.add(render(fill(String.valueOf(line), values)));
        }
        return rendered;
    }

    /**
     * The winner among the four layers, or null when nobody has this key.
     *
     * <p>One method, so every way of reading a message agrees about precedence. The first version of
     * the override API had {@code raw} and {@code lines} each work it out, and they disagreed about
     * whether a forced value beat the file.
     */
    private Object lookUp(String key) {
        Object insisted = forced.get(key);
        if (insisted != null) {
            return insisted;
        }
        Object owner = theirs.get(key);
        Object bundled = shipped.get(key);
        if (tone == Tone.SERIOUS) {
            Object plain = serious.get(key);
            // The owner's file holds every line we shipped, written out on the first start, so a line
            // there is only theirs if it differs from ours. One that still reads like the playful
            // default was never chosen by anybody, and serious mode is the owner choosing now.
            if (plain != null && (owner == null || owner.equals(bundled) || owner.equals(defined.get(key)))) {
                return plain;
            }
        }
        if (owner != null) {
            return owner;
        }
        // The bundled file last but one, above a define rather than below it. A define is a *floor*:
        // it fills a key nobody else has. Letting it beat the jar would make every line in the
        // shipped messages.yml a suggestion the code could silently ignore — and that file is the one
        // an owner reads to find out what they may change.
        return bundled != null ? bundled : defined.get(key);
    }

    // ------------------------------------------------------------------ per-plugin overrides

    /** plugin → (key → the key that plugin says it with instead). */
    private final Map<String, Map<String, String>> overrides = new ConcurrentHashMap<>();

    /**
     * Makes one plugin say {@code key} with {@code replacementKey} instead — for that plugin only.
     *
     * <p>Keys are global, so a module can neither reword a Core line through its own wording (that
     * arrives as a floor) nor {@link #force} it without rewording it for every plugin on the server.
     * An override is the narrow version: wherever Core sends {@code key} on behalf of {@code plugin}
     * (see {@link #prefixedFor}), the replacement is sent. The replacement is an ordinary key — usually
     * in the plugin's own section — so the owner can still edit it and it carries that plugin's prefix.
     *
     * @param plugin a plugin's or module's id, as the caller that sends on its behalf knows it
     * @return whether it was taken
     */
    public boolean overrideFor(String plugin, String key, String replacementKey) {
        if (plugin == null || plugin.isBlank() || key == null || key.isBlank()
                || replacementKey == null || replacementKey.isBlank()) {
            return false;
        }
        overrides.computeIfAbsent(plugin, ignored -> new ConcurrentHashMap<>())
                .put(key, replacementKey);
        return true;
    }

    /** Drops every override {@code plugin} made — for a plugin or module unloading. @return how many */
    public int forgetOverridesFor(String plugin) {
        Map<String, String> gone = plugin == null ? null : overrides.remove(plugin);
        return gone == null ? 0 : gone.size();
    }

    /**
     * The key {@code plugin} says {@code key} with: its override, or {@code key} itself. An override
     * whose replacement nobody defined falls back to the original rather than printing a bare key.
     */
    public String keyFor(String plugin, String key) {
        if (plugin == null || key == null) {
            return key;
        }
        Map<String, String> own = overrides.get(plugin);
        String replacement = own == null ? null : own.get(key);
        return replacement != null && has(replacement) ? replacement : key;
    }

    /** {@link #prefixed}, as {@code plugin} says it — see {@link #overrideFor}. */
    public Component prefixedFor(String plugin, String key, Object... values) {
        return prefixed(keyFor(plugin, key), values);
    }

    /** {@link #send}, as {@code plugin} says it — see {@link #overrideFor}. */
    public void sendFor(String plugin, Audience recipient, String key, Object... values) {
        send(recipient, keyFor(plugin, key), values);
    }

    /** Whether a key is defined anywhere at all. */
    public boolean has(String key) {
        return lookUp(key) != null;
    }

    // ------------------------------------------------------------------ what a plugin can say

    /**
     * Supplies a default for one message.
     *
     * <p>Used below the owner's file: for a message this version invented, or one built at runtime.
     * If the owner has written that key, theirs is what players see.
     *
     * @param value a string, or a {@link List} of them for something several lines long
     * @return whether it was taken
     */
    public boolean define(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return false;
        }
        defined.put(key, value);
        return true;
    }

    /** Supplies several at once — what a plugin registering its own set of messages wants. */
    public int defineAll(Map<String, ?> values) {
        if (values == null) {
            return 0;
        }
        int taken = 0;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (define(entry.getKey(), entry.getValue())) {
                taken++;
            }
        }
        return taken;
    }

    /**
     * Takes a module's own bundled wording as a floor.
     *
     * <p>A module is not a plugin: it has no data folder and no {@code messages.yml} on disk, it ships its
     * wording inside its jar and runs on whichever {@code Messages} its host owns. This is how that wording
     * gets in. Every key lands at {@link #define} level, which means the owner's file wins, the host's own
     * bundled file wins, and two modules that name the same key do not fight — the first one keeps it.
     *
     * <p>Skipping this is not a subtle failure: every key the module uses comes back as the key itself.
     * The claims module shipped a full {@code messages.yml} and nothing that read it, and {@code /claim}
     * answered {@code claim.nonehere}.
     *
     * @param bundled the module's {@code messages.yml}, from {@code getResourceAsStream} — closed here.
     *                {@code null} (a file that is not in the jar) is nothing to do rather than a crash
     * @return how many keys were taken up
     */
    public int defineFrom(InputStream bundled) {
        return defineFrom(bundled, null);
    }

    /**
     * The same, and the supplier signs every section this wording defines.
     *
     * <p>What makes a message say which plugin it came from. Without it the last plugin to start signs
     * the whole server — see {@link #prefixFor}.
     *
     * @param signature what to put in front of these sections' messages; null keeps the host's
     */
    public int defineFrom(InputStream bundled, Supplier<String> signature) {
        if (bundled == null) {
            return 0;
        }
        Map<String, Object> wording = new LinkedHashMap<>();
        try (InputStream stream = bundled) {
            flatten(parse(stream), "", wording);
            if (signature != null) {
                // The top-level sections this file defines, which is exactly what this module owns.
                for (String key : wording.keySet()) {
                    int dot = key.indexOf('.');
                    if (dot > 0) {
                        sectionPrefixes.put(key.substring(0, dot), signature);
                    }
                }
            }
        } catch (IOException | RuntimeException broken) {
            // The module's bug, and it costs that module its wording rather than the server its start.
            problems.add("a module's bundled messages could not be read (" + broken.getMessage() + ")");
            log.warn("A module's bundled messages could not be read ({}); its messages will show their "
                    + "keys. This is a fault in that module, not in your configuration.",
                    broken.getMessage());
            return 0;
        }
        // First one in keeps the key, unlike define(), which is last-wins because a plugin redefining its
        // own wording means it. Between modules there is no "its own": load order decides who runs first,
        // and a module silently rewording another because of that is a bug nobody can see from either jar.
        int taken = 0;
        for (Map.Entry<String, Object> entry : wording.entrySet()) {
            if (!defined.containsKey(entry.getKey()) && define(entry.getKey(), entry.getValue())) {
                taken++;
            }
        }
        return taken;
    }

    /**
     * Signs every section this file already defines, for whoever bundled it.
     *
     * <p>{@link #defineFrom} does this for a module, because a module hands over its wording and its
     * brand together. {@link #load} could not: it is the <em>host's</em> own wording, loaded before
     * the host has a brand to sign it with.
     *
     * <p>Which left Core's own sections unowned, and an unowned section falls back to the global
     * prefix — whatever plugin called {@link #prefixFrom} last. On a server where moderation happens
     * to enable last, that is every warp, home and teleport line on the server announcing itself as
     * <code>Moderation</code>. It is not cosmetic: the prefix is how a player knows which plugin is
     * talking to them, and the one thing worse than no answer is a confident wrong one.
     *
     * <p>{@code putIfAbsent}, so this can be called at any point without taking a section from the
     * module that actually owns it — and so calling it twice changes nothing.
     *
     * @return how many sections were signed
     */
    public int claimSections(Supplier<String> signature) {
        if (signature == null) {
            return 0;
        }
        int signed = 0;
        for (String key : keys()) {
            int dot = key.indexOf('.');
            if (dot > 0 && sectionPrefixes.putIfAbsent(key.substring(0, dot), signature) == null) {
                signed++;
            }
        }
        return signed;
    }

    /**
     * Insists on one message, over anything the owner wrote.
     *
     * <p>Rare on purpose. Every use is a line in somebody's {@code messages.yml} that silently does
     * nothing, and an owner who cannot see why their edit is ignored will conclude the file is
     * broken. Use {@link #define} unless the text genuinely must not be editable.
     *
     * @return whether it was taken
     */
    public boolean force(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return false;
        }
        forced.put(key, value);
        return true;
    }

    /**
     * Stops insisting, so the owner's file comes through again.
     *
     * @return whether anything was being insisted on
     */
    public boolean release(String key) {
        return key != null && forced.remove(key) != null;
    }

    /** Which keys a plugin is insisting on — for a page that explains why an edit does nothing. */
    public List<String> forcedKeys() {
        List<String> keys = new ArrayList<>(forced.keySet());
        keys.sort(String::compareTo);
        return keys;
    }

    /** Every key the owner's file does not have — for a line at startup, or a menu. */
    public List<String> missingFromFile() {
        return List.copyOf(missing);
    }

    /** What went wrong: an unreadable file, a key nobody defined, a bad call. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Every key there is, for tab completion or a settings page. */
    public List<String> keys() {
        Set<String> all = new LinkedHashSet<>(shipped.keySet());
        all.addAll(defined.keySet());
        all.addAll(theirs.keySet());
        all.addAll(forced.keySet());
        List<String> sorted = new ArrayList<>(all);
        sorted.sort(String::compareTo);
        return sorted;
    }

    // ---------------------------------------------------------------------------- internals

    /**
     * Puts the values where their names are.
     *
     * <p>Text, every one, unless wrapped in {@link Markup}. A value is usually something a player typed —
     * a home's name, another player's name, an item they renamed — and pasting that into markup is how a
     * home called {@code <rainbow>} recolours the sentence, or an item becomes a button that runs a
     * command as whoever clicks it. A {@code Component} is inserted as the component it is.
     */
    private String fill(String message, Object... values) {
        if (values == null || values.length == 0) {
            return message;
        }
        if (values.length % 2 != 0) {
            String problem = "a message was given a placeholder name with no value";
            if (!problems.contains(problem)) {
                problems.add(problem);
                log.warn("{} ({} argument(s)); it was left as it is.", problem, values.length);
            }
            return message;
        }
        // One pass, each value as exactly the text it is (Text.fill): replacing placeholders one after
        // another re-scanned what earlier values had inserted, and a value ending in a backslash
        // unescaped the next one — a renamed item could become a live /op button that way. A value is
        // only markup when it is a Markup, a decision made in code.
        return Text.fill(message, values);
    }

    /**
     * Markup to something a player can see, whatever is wrong with it.
     *
     * <p>A typo in a colour name must not throw. The message still has to arrive, because the times
     * this matters are the times somebody is being told they cannot do something.
     */
    private Component render(String markup) {
        try {
            return MINI.deserialize(markup);
        } catch (RuntimeException badMarkup) {
            String problem = "a message could not be read as MiniMessage (" + badMarkup.getMessage()
                    + ")";
            if (!problems.contains(problem)) {
                problems.add(problem);
                log.warn(problem);
            }
            // The text without its markup beats nothing at all.
            return Component.text(markup.replaceAll("<[^>]*>", ""));
        }
    }

    /**
     * A nested section as flat dotted keys.
     *
     * <p>So {@code nested.deeper} works whether the owner wrote it nested or flat — which they will
     * do inconsistently, and should not have to think about.
     */
    /** A bare {@code off:}, {@code on:}, {@code yes:} or {@code no:} key, the whole word, at the start of a line. */
    private static final java.util.regex.Pattern YES_NO_KEY = java.util.regex.Pattern.compile(
            "(?m)^(\\s*)(on|off|yes|no|y|n|true|false)(\\s*):", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Quotes keys YAML 1.1 would read as booleans. Bukkit's loader turns {@code off:} into the key
     * {@code false}, so {@code shop.off} becomes {@code shop.false} and the line is never found.
     */
    static String quoteYesNoKeys(String text) {
        return YES_NO_KEY.matcher(text).replaceAll("$1\"$2\"$3:");
    }

    private static YamlConfiguration parse(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(quoteYesNoKeys(text));
        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
            throw new IllegalArgumentException(broken.getMessage(), broken);
        }
        return yaml;
    }

    private static YamlConfiguration parse(java.io.InputStream stream) throws java.io.IOException {
        return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void flatten(ConfigurationSection section, String prefix,
                                Map<String, Object> into) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof ConfigurationSection nested) {
                flatten(nested, path, into);
            } else if (value != null) {
                into.put(path, value);
            }
        }
    }

    /**
     * The owner's messages.yml: one document for the whole network (2.0.0), so a line reworded once reads the
     * same on every server; a file when Core's documents are not installed.
     */
    private YamlStore document() {
        YamlStore here = store;
        if (here == null) {
            here = YamlStore.network(file);
            store = here;
        }
        return here;
    }

    private volatile YamlStore store;

    private Optional<String> text() {
        return file == null ? Optional.empty() : document().readText();
    }
}
