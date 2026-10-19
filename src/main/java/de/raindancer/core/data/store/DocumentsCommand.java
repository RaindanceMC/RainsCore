package de.raindancer.core.data.store;

import de.raindancer.core.platform.util.Scheduling;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /documents} — editing by hand what used to be a file: {@code list [plugin]},
 * {@code export <plugin> <name>}, {@code import}. Everything runs off the server's threads.
 */
public final class DocumentsCommand implements BasicCommand {

    public static final String PERMISSION = "rainscore.documents";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String USAGE = "<gray>/documents list [plugin]  ·  export <plugin> <name>  ·  import";

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission(PERMISSION);
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Plugin core = Bukkit.getPluginManager().getPlugin("RainsCore");
        if (core == null || Documents.installed().isEmpty()) {
            say(sender, "<red>Documents are not in a database on this server: RainsCore is not running.");
            return;
        }
        DocumentEditing editing = new DocumentEditing(Documents.installed().get(),
                core.getDataFolder().toPath().resolve("edit"));
        String word = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        Scheduling.async(core, () -> {
            switch (word) {
                case "list" -> list(sender, editing, args.length > 1 ? args[1] : null);
                case "export" -> {
                    if (args.length != 3) {
                        say(sender, USAGE);
                        return;
                    }
                    editing.export(args[1], args[2]).ifPresentOrElse(
                            path -> say(sender, "<green>Written to <white>" + shown(core.getDataFolder().toPath(), path)
                                    + "</white>.</green> <gray>Edit it, then /documents import."),
                            () -> say(sender, "<red>There is no document " + args[1] + "/" + args[2] + "."));
                }
                case "import" -> {
                    DocumentEditing.Imported done = editing.importAll();
                    if (done.applied().isEmpty() && done.refused().isEmpty()) {
                        say(sender, "<gray>Nothing to import in " + editing.folder().getFileName() + "/.");
                    }
                    done.applied().forEach(name -> say(sender, "<green>Imported <white>" + name + "</white>."));
                    done.refused().forEach((name, why) -> say(sender, "<red>Not imported <white>" + name + "</white>: " + why));
                }
                default -> say(sender, USAGE);
            }
        });
    }

    /**
     * Where an exported file is, from the plugins folder. Both made absolute first: the data folder is relative on a
     * server started from its own folder, the written file absolute, and relativizing one against the other throws.
     */
    static java.nio.file.Path shown(java.nio.file.Path dataFolder, java.nio.file.Path written) {
        java.nio.file.Path folder = dataFolder.toAbsolutePath().normalize();
        java.nio.file.Path plugins = folder.getParent() == null ? folder : folder.getParent();
        return plugins.relativize(written.toAbsolutePath().normalize());
    }

    private static void list(CommandSender sender, DocumentEditing editing, String onlyOwner) {
        List<DocumentEditing.Entry> entries = editing.list().stream()
                .filter(entry -> onlyOwner == null || entry.owner().equalsIgnoreCase(onlyOwner)).toList();
        if (entries.isEmpty()) {
            say(sender, "<gray>No documents" + (onlyOwner == null ? "." : " for " + onlyOwner + "."));
            return;
        }
        Map<String, List<DocumentEditing.Entry>> byOwner = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        entries.forEach(entry -> byOwner.computeIfAbsent(entry.owner(), ignored -> new java.util.ArrayList<>()).add(entry));
        byOwner.forEach((owner, theirs) -> say(sender, "<gold>" + owner + "</gold> <dark_gray>·</dark_gray> "
                + String.join("<dark_gray>, </dark_gray>", theirs.stream()
                .map(entry -> (entry.scope().equals("network") ? "<aqua>" : "<white>") + entry.name()
                        + (entry.scope().equals("network") ? "</aqua>" : "</white>")).toList())));
        say(sender, "<gray><aqua>blue</aqua> is the network's, <white>white</white> this server's.");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return List.of("list", "export", "import");
        }
        if (args.length == 2 && "export".equalsIgnoreCase(args[0]) || args.length == 2 && "list".equalsIgnoreCase(args[0])) {
            return Documents.installed().map(documents -> new DocumentEditing(documents, documents.root()).list()
                    .stream().map(DocumentEditing.Entry::owner).distinct().toList()).orElse(List.of());
        }
        if (args.length == 3 && "export".equalsIgnoreCase(args[0])) {
            return Documents.installed().map(documents -> new DocumentEditing(documents, documents.root()).list()
                    .stream().filter(entry -> entry.owner().equalsIgnoreCase(args[1]))
                    .map(DocumentEditing.Entry::name).toList()).orElse(List.of());
        }
        return List.of();
    }

    private static void say(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
    }
}
