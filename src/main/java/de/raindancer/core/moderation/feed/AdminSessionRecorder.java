package de.raindancer.core.moderation.feed;

import de.raindancer.core.moderation.audit.AuditEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Writes down what staff do while in admin mode, from {@link AdminModeLog#switched going in} to coming out, for the
 * {@link AdminSessionRecap} on the way out. In memory only: a restart starts the stay over. Thread-safe — commands and
 * blocks come from region threads, audit entries from wherever they were recorded.
 */
public final class AdminSessionRecorder {

    /** Per list (commands, actions); a stay with more keeps the first ones and counts the rest. */
    public static final int MAX_LINES = 500;
    public static final int MAX_TEXT = 256;

    private static final String HIDDEN = "‹hidden›";
    private static final Set<String> LEAVING = Set.of("admin", "adminmode", "staffmode");
    private static final Set<String> SECRET_COMMANDS = Set.of("login", "l", "log", "register", "reg",
            "changepassword", "changepass", "changepw", "unregister");
    private static final Set<String> SECRET_WORDS = Set.of("hushtoken", "token", "password", "passwort", "secret");

    private final LongSupplier clock;
    private final Map<UUID, Stay> stays = new ConcurrentHashMap<>();

    public AdminSessionRecorder(LongSupplier clock) {
        this.clock = clock;
    }

    /** Starts a stay; one already running goes on (a join while still in admin mode). */
    public void begin(UUID staff) {
        stays.computeIfAbsent(staff, id -> new Stay(clock.getAsLong()));
    }

    public boolean recording(UUID staff) {
        return staff != null && stays.containsKey(staff);
    }

    /** @param line as typed, with or without the slash */
    public void command(UUID staff, String line) {
        Stay stay = stay(staff);
        if (stay == null || line == null || line.isBlank()) {
            return;
        }
        String typed = line.strip();
        String text = cut(hideSecrets(typed.startsWith("/") ? typed : "/" + typed));
        synchronized (stay) {
            stay.lastCommand = text;
            if (stay.commands.size() < MAX_LINES) {
                stay.commands.add(new AdminSessionRecap.Line(clock.getAsLong(), text));
            } else {
                stay.commandsOmitted++;
            }
        }
    }

    /** An audit entry from anywhere; kept only when its actor is in a stay. */
    public void audited(AuditEntry entry) {
        Stay stay = entry == null ? null : stay(entry.actor());
        if (stay == null) {
            return;
        }
        String said = entry.saying();
        String actor = entry.actorDescription();
        String text = said.startsWith(actor + " ") ? said.substring(actor.length() + 1) : said;
        synchronized (stay) {
            if (stay.actions.size() < MAX_LINES) {
                stay.actions.add(new AdminSessionRecap.Line(entry.at().toEpochMilli(), cut(text)));
            } else {
                stay.actionsOmitted++;
            }
        }
    }

    public void placed(UUID staff, String material) {
        count(staff, material, true);
    }

    public void broken(UUID staff, String material) {
        count(staff, material, false);
    }

    public void teleported(UUID staff, String fromWorld, String toWorld) {
        Stay stay = stay(staff);
        if (stay == null) {
            return;
        }
        synchronized (stay) {
            stay.teleports++;
            if (stay.firstWorld == null) {
                stay.firstWorld = fromWorld;
            }
            if (toWorld != null && !toWorld.equals(stay.firstWorld)) {
                stay.worlds.add(toWorld);
            }
        }
    }

    public void gameMode(UUID staff, String from, String to) {
        Stay stay = stay(staff);
        if (stay == null) {
            return;
        }
        synchronized (stay) {
            stay.gameModes.add(from + " → " + to);
        }
    }

    /** Ends the stay and says what happened in it; empty when no stay was running. */
    public Optional<AdminSessionRecap> end(UUID staff) {
        Stay stay = staff == null ? null : stays.remove(staff);
        if (stay == null) {
            return Optional.empty();
        }
        synchronized (stay) {
            if (stay.lastCommand != null && leaving(stay.lastCommand)) {
                if (stay.commandsOmitted > 0) {
                    stay.commandsOmitted--;
                } else {
                    stay.commands.removeLast();
                }
            }
            return Optional.of(new AdminSessionRecap(stay.started, clock.getAsLong(),
                    stay.commands, stay.commandsOmitted, stay.actions, stay.actionsOmitted,
                    stay.placed, stay.broken, stay.teleports, new ArrayList<>(stay.worlds), stay.gameModes));
        }
    }

    private void count(UUID staff, String material, boolean placing) {
        Stay stay = stay(staff);
        if (stay == null || material == null) {
            return;
        }
        synchronized (stay) {
            (placing ? stay.placed : stay.broken).merge(material, 1, Integer::sum);
        }
    }

    private Stay stay(UUID staff) {
        return staff == null ? null : stays.get(staff);
    }

    private static String label(String[] words) {
        String label = words[0].substring(1).toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        return namespace >= 0 ? label.substring(namespace + 1) : label;
    }

    private static boolean leaving(String command) {
        String[] words = command.split("\\s+");
        return LEAVING.contains(label(words))
                && (words.length == 1 || words.length == 2 && words[1].equalsIgnoreCase("off"));
    }

    static String hideSecrets(String command) {
        String[] words = command.split("\\s+");
        if (words.length > 1 && SECRET_COMMANDS.contains(label(words))) {
            return words[0] + " " + HIDDEN;
        }
        for (int i = 1; i < words.length - 1; i++) {
            if (SECRET_WORDS.contains(words[i].toLowerCase(Locale.ROOT))) {
                return String.join(" ", java.util.Arrays.copyOf(words, i + 1)) + " " + HIDDEN;
            }
        }
        return command;
    }

    private static String cut(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
    }

    private static final class Stay {
        final long started;
        final List<AdminSessionRecap.Line> commands = new ArrayList<>();
        final List<AdminSessionRecap.Line> actions = new ArrayList<>();
        int commandsOmitted;
        int actionsOmitted;
        String lastCommand;
        final Map<String, Integer> placed = new LinkedHashMap<>();
        final Map<String, Integer> broken = new LinkedHashMap<>();
        final Set<String> worlds = new LinkedHashSet<>();
        final List<String> gameModes = new ArrayList<>();
        String firstWorld;
        int teleports;

        Stay(long started) {
            this.started = started;
        }
    }
}
