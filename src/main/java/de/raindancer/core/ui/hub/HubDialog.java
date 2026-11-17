package de.raindancer.core.ui.hub;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.command.CommandDirectory;
import de.raindancer.core.platform.command.CommandNote;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.time.Times;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.dialog.DialogPage;
import de.raindancer.core.ui.dialog.DialogReturn;
import de.raindancer.core.ui.dialog.Faces;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@code /hub} as the game's own window, like /help: the player's own doors (travel, preferences, profile), then every
 * feature installed. A feature with a command named like it opens straight away; any other gets a window of its
 * commands. A chest page a command opens goes Back to the hub.
 */
public final class HubDialog {

    /** What the hub shows and how it acts: the server, or a test. */
    interface Source {
        List<HubEntry> entries(Player viewer);

        List<CommandNote> notes(HubEntry entry, Player viewer);

        boolean exists(String command);

        boolean travel();

        String serverName();

        List<String> facts(Player viewer);

        void run(Player viewer, String command);

        void suggest(Player viewer, String command);
    }

    private HubDialog() {
    }

    public static void open(Player viewer) {
        Faces.load(viewer.getUniqueId()).thenRun(() -> Scheduling.entity(JavaPlugin.getProvidingPlugin(HubDialog.class),
                viewer, () -> {
                    if (viewer.isOnline()) {
                        page(viewer, LIVE).show(viewer);
                    }
                }));
    }

    /** One feature's window, as {@code /hub <feature>} opens it. */
    public static void openFeature(Player viewer, HubEntry entry) {
        feature(viewer, entry, LIVE).show(viewer);
    }

    static DialogPage page(Player viewer, Source source) {
        String name = Text.literal(viewer.getName());
        DialogPage page = DialogPage.titled("<bold><gradient:" + Style.brandFrom() + ":" + Style.brandTo() + ">"
                        + Text.literal(source.serverName()) + "</gradient></bold>").columns(2)
                .face(viewer.getUniqueId(), () -> Icons.head(viewer.getUniqueId(), "<white>" + name),
                        "<gray>Welcome, <white>" + name + "</white>.\n" + String.join("\n", source.facts(viewer)));
        if (source.travel()) {
            page.button("<gradient:#7DD3FC:#A78BFA>Travel</gradient>",
                    "<gray>Homes, warps, other players, a random spot and back.",
                    answers -> de.raindancer.core.ui.travel.TravelDialog.open(viewer));
        }
        page.button("<gradient:#A7F3D0:#7DD3FC>Your preferences</gradient>",
                "<gray>Everything you set for yourself: sidebar, health bars, requests and more.",
                answers -> de.raindancer.core.ui.preference.PreferencesDialog.open(viewer));
        page.button("<gradient:#F9A8D4:#FBCFE8>Your profile</gradient>", "<gray>How the server sees you.",
                answers -> de.raindancer.core.ui.profile.ProfileDialog.open(viewer, brand(), viewer.getUniqueId(),
                        viewer.getName(), () -> open(viewer)));
        if (source.exists("changelog")) {
            page.button("<gradient:#86EFAC:#6EE7B7>What's new</gradient>", "<gray>What changed on the server lately.",
                    answers -> source.run(viewer, "changelog"));
        }
        if (source.exists("commands")) {
            page.button("<gradient:#FDE68A:#FDBA74>All commands</gradient>", "<gray>Every command you may use, in one book.",
                    answers -> source.run(viewer, "commands"));
        }
        if (source.exists("settings") && viewer.hasPermission("rainscore.settings")) {
            page.button("<gradient:#7DD3FC:#A5B4FC>Server settings</gradient>",
                    "<gray>How every feature here is set up. Staff only.", answers -> source.run(viewer, "settings"));
        }
        for (HubEntry entry : source.entries(viewer)) {
            List<CommandNote> notes = source.notes(entry, viewer);
            Optional<CommandNote> front = frontDoor(entry, notes);
            page.button(label(entry), tooltip(entry, notes.size(), front), answers -> {
                if (front.isPresent()) {
                    source.run(viewer, front.get().command());
                } else {
                    feature(viewer, entry, source).show(viewer);
                }
            });
        }
        return page;
    }

    /** One feature: what it is, then a button for each of its commands the viewer may use. */
    static DialogPage feature(Player viewer, HubEntry entry, Source source) {
        List<CommandNote> notes = new ArrayList<>(source.notes(entry, viewer));
        // What runs with a click first, what still has to be typed after.
        notes.sort((one, other) -> Boolean.compare(!runsDirectly(one), !runsDirectly(other)));
        DialogPage page = DialogPage.titled(gradient(entry)).columns(2).back(() -> page(viewer, source).show(viewer));
        page.text("<gray>" + Text.literal(entry.description())
                + (entry.version().isEmpty() ? "" : "\n<dark_gray>Version " + Text.literal(entry.version())));
        if (notes.isEmpty()) {
            page.text("<gray>" + Text.literal(entry.name()) + " works without commands.");
        }
        guide(viewer, entry, page);
        for (CommandNote note : notes) {
            StringBuilder tooltip = new StringBuilder("<gray>" + Text.literal(note.sentence()));
            note.options().forEach(option -> tooltip.append("\n<dark_gray>/").append(Text.literal(option)));
            tooltip.append("\n\n<dark_gray>» <white>").append(runsDirectly(note) ? "Click to run" : "Click to type it");
            page.button("<" + entry.accent() + ">" + Text.literal(note.slashed()), tooltip.toString(), answers -> {
                if (runsDirectly(note)) {
                    source.run(viewer, note.command());
                } else {
                    source.suggest(viewer, note.slashed());
                }
            });
        }
        return page;
    }

    /** "Ask the guide": the feature's own topic in /help, when the guide has one this viewer may read. */
    private static void guide(Player viewer, HubEntry entry, DialogPage page) {
        var desk = de.raindancer.core.ui.help.HelpDesk.current().orElse(null);
        if (desk == null) {
            return;
        }
        var context = desk.contextFor(viewer);
        desk.library().visibleTo(context).stream()
                .filter(topic -> entry.id().equalsIgnoreCase(topic.module()) || entry.id().equalsIgnoreCase(topic.id()))
                .findFirst()
                .ifPresent(topic -> page.button("<gradient:#C084FC:#7DD3FC>Ask " + Text.literal(desk.settings().guideName())
                                + "</gradient>", "<gray>How " + Text.literal(entry.name()) + " works here, explained as it is"
                                + " set up.",
                        answers -> de.raindancer.core.ui.help.HelpDialogs.forReader(desk, viewer).topic(topic)));
    }

    private static String gradient(HubEntry entry) {
        return "<gradient:" + entry.accent() + ":" + HubLook.lighter(entry.accent(), 0.55) + ">"
                + Text.literal(entry.name()) + "</gradient>";
    }

    private static String label(HubEntry entry) {
        return "<bold>" + gradient(entry) + "</bold>";
    }

    private static String tooltip(HubEntry entry, int commands, Optional<CommandNote> front) {
        StringBuilder text = new StringBuilder();
        if (!entry.description().isBlank()) {
            text.append("<gray>").append(Text.literal(entry.description())).append("\n");
        }
        if (commands > 0) {
            text.append("<dark_gray>").append(commands).append(commands == 1 ? " command" : " commands").append("\n");
        }
        text.append("\n<dark_gray>» <white>").append(front.map(note -> "Click to open " + Text.literal(note.slashed()))
                .orElse("Click to see what it has"));
        return text.toString();
    }

    /**
     * The command a click on a feature runs outright: one of the feature's own that takes nothing after it and is
     * named like the feature, {@code /guild} for guilds, {@code /pets} for pets. A feature with no such command (the
     * economy's are /bank, /shop…) gets its window of commands.
     */
    static Optional<CommandNote> frontDoor(HubEntry entry, List<CommandNote> notes) {
        String id = entry.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        Set<String> names = new LinkedHashSet<>(List.of(id, id + "s", id.endsWith("s") ? id.substring(0, id.length() - 1) : id));
        for (String name : names) {
            for (CommandNote note : notes) {
                if (runsDirectly(note) && note.command().equalsIgnoreCase(name)) {
                    return Optional.of(note);
                }
            }
        }
        return Optional.empty();
    }

    /** A command with no arguments can simply be run; one that takes some needs the player to finish it. */
    static boolean runsDirectly(CommandNote note) {
        return note.options().isEmpty() && note.command().indexOf(' ') < 0;
    }

    static String serverName() {
        return RainsCore.isAvailable() ? RainsCore.get().serverName()
                : de.raindancer.core.ui.text.ServerName.resolve("", "", Bukkit.motd());
    }

    private static Brand brand() {
        return RainsCore.isAvailable() ? RainsCore.get().chatFor("SMP").brand() : new Brand("Hub");
    }

    private static final Source LIVE = new Source() {

        @Override
        public List<HubEntry> entries(Player viewer) {
            return RainsCore.get().hub().visibleTo(viewer::hasPermission, RainsCore.get().commands());
        }

        @Override
        public List<CommandNote> notes(HubEntry entry, Player viewer) {
            CommandDirectory directory = RainsCore.get().commands();
            return Hub.notesFor(entry, directory, viewer::hasPermission);
        }

        @Override
        public boolean exists(String command) {
            return Bukkit.getCommandMap().getCommand(command) != null;
        }

        @Override
        public boolean travel() {
            return de.raindancer.core.ui.travel.TravelDoors.any();
        }

        @Override
        public String serverName() {
            return HubDialog.serverName();
        }

        @Override
        public List<String> facts(Player viewer) {
            List<String> facts = new ArrayList<>();
            facts.add("<gray>Online now: <white>" + Bukkit.getOnlinePlayers().size());
            if (RainsCore.get().playtime().isKnown(viewer.getUniqueId())) {
                facts.add("<gray>You have played: <white>"
                        + Times.describe(RainsCore.get().playtime().played(viewer.getUniqueId())));
            }
            return facts;
        }

        @Override
        public void run(Player viewer, String command) {
            // A chest page the command opens knows nothing of the hub; this is how its Back finds the way here.
            Menu.cameFrom(viewer, new DialogReturn(viewer, brand(), "Hub", () -> open(viewer)));
            viewer.closeDialog();
            de.raindancer.core.platform.util.TypedCommands.run(viewer, command);
        }

        @Override
        public void suggest(Player viewer, String command) {
            viewer.closeDialog();
            viewer.sendMessage(Component.text("» Click to type ", NamedTextColor.GRAY)
                    .append(Component.text(command, NamedTextColor.WHITE)
                            .clickEvent(ClickEvent.suggestCommand(command + " "))));
        }
    };
}
