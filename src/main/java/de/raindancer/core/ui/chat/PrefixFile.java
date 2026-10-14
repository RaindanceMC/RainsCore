package de.raindancer.core.ui.chat;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.text.NameStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code plugins/RainsCore/prefix.yml}: the {@link PrefixDesign}, readable and writable by hand as well
 * as through {@code /prefix}.
 *
 * <p>A broken file is reported and left exactly as it is — the owner's half-finished edit is theirs to
 * fix, not ours to overwrite with defaults the moment the menu saves something.
 */
public final class PrefixFile {

    private static final LogChannel log = Log.of("prefix");

    private static final List<String> HEADER = List.of(
            "How every plugin signs its messages. Edit here or in game with /prefix.",
            "",
            "mode:   per-plugin (each plugin's own name) or shared (one tag for all)",
            "shown:  false for no prefix at all",
            "tag:    the shared tag; empty keeps each plugin's own name",
            "style:  colours and decorations like a name: '#ff8800,#ffee00|bold' or '#5555ff|italic|animated';",
            "        empty is the theme's gradient in bold",
            "format: MiniMessage around the tag. {tag} is the painted tag, {plugin} the plugin's own name",
            "plugins: per-plugin overrides (per-plugin mode): tag, style, shown");

    private final Path file;
    /** One prefix design for the network: changed with /prefix on one server, it is the design on all of them. */
    private final de.raindancer.core.data.store.YamlStore store;
    private volatile boolean broken;

    public PrefixFile(Path file) {
        this.file = file;
        this.store = de.raindancer.core.data.store.YamlStore.network(file);
    }

    /** Runs {@code reload} when another server changes the design. */
    public void onChange(Runnable reload) {
        store.onChange(reload);
    }

    /** Whether the last load found a file it could not read — saving is refused until it is fixed. */
    public boolean isBroken() {
        return broken;
    }

    public PrefixDesign load() {
        broken = false;
        if (!store.exists()) {
            save(PrefixDesign.DEFAULT);
            return PrefixDesign.DEFAULT;
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            broken = true;
            log.error("{} could not be read, so every prefix looks as it does by default until it is "
                    + "fixed ({}).", file, String.join("; ", store.problems()));
            return PrefixDesign.DEFAULT;
        }
        Map<String, PrefixDesign.PluginPrefix> plugins = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("plugins");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                ConfigurationSection one = section.getConfigurationSection(name);
                if (one != null) {
                    plugins.put(name, new PrefixDesign.PluginPrefix(one.getString("tag", ""),
                            style(one.getString("style", ""), "plugins." + name + ".style"),
                            one.getBoolean("shown", true)));
                }
            }
        }
        return new PrefixDesign(mode(yaml.getString("mode", "")), yaml.getBoolean("shown", true),
                yaml.getString("tag", ""), style(yaml.getString("style", ""), "style"),
                yaml.getString("format", PrefixDesign.DEFAULT_FORMAT), plugins);
    }

    /** Writes {@code design}; false (and nothing written) while the file on disk is one we could not read. */
    public boolean save(PrefixDesign design) {
        if (broken) {
            log.warn("Not saving the prefix: {} is broken and saving would overwrite the owner's edit.", file);
            return false;
        }
        return store.write(yaml -> {
            yaml.options().setHeader(HEADER);
            yaml.set("mode", design.mode() == PrefixDesign.Mode.SHARED ? "shared" : "per-plugin");
            yaml.set("shown", design.shown());
            yaml.set("tag", design.tag());
            yaml.set("style", design.style().isEmpty() ? "" : design.style().encode());
            yaml.set("format", design.format());
            design.plugins().forEach((name, prefix) -> {
                String at = "plugins." + name;
                yaml.set(at + ".tag", prefix.tag());
                yaml.set(at + ".style", prefix.style().isEmpty() ? "" : prefix.style().encode());
                yaml.set(at + ".shown", prefix.shown());
        });
        });
    }

    private static PrefixDesign.Mode mode(String written) {
        String cleaned = written == null ? "" : written.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (cleaned) {
            case "shared", "one", "same" -> PrefixDesign.Mode.SHARED;
            case "per-plugin", "plugin", "each", "" -> PrefixDesign.Mode.PER_PLUGIN;
            default -> {
                log.warn("prefix.yml: mode '{}' is neither per-plugin nor shared; using per-plugin.", written);
                yield PrefixDesign.Mode.PER_PLUGIN;
            }
        };
    }

    private static NameStyle style(String written, String where) {
        NameStyle read = NameStyle.parse(written);
        if (written != null && !written.isBlank() && read.colours().isEmpty()
                && !written.strip().startsWith("|") && !written.contains("|")) {
            log.warn("prefix.yml: {} '{}' has no colour in it that could be read.", where, written);
        }
        return read;
    }
}
