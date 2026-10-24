package de.raindancer.core.ui.help;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.Setting;
import de.raindancer.core.data.settings.SettingsRegistry;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.ui.hub.HubEntry;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** This server, right now, for one reader: Core's settings registry, their permissions, what is installed. */
final class LiveHelpContext implements HelpContext {

    private final CommandSender reader;
    private final SettingsRegistry settings;
    private final Set<String> modules;
    private final Function<String, Optional<String>> titles;

    LiveHelpContext(CommandSender reader, SettingsRegistry settings, Function<String, Optional<String>> titles) {
        this.reader = reader;
        this.settings = settings;
        this.titles = titles;
        this.modules = RainsCore.isAvailable()
                ? RainsCore.get().hub().all().stream().map(HubEntry::id).map(id -> id.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet())
                : Set.of();
    }

    @Override
    public Optional<Object> setting(String key) {
        Optional<SettingsStore<?>> store = settings.storeOf(key);
        if (store.isEmpty()) {
            return Optional.empty();
        }
        String bare = key.indexOf(':') > 0 ? key.substring(key.indexOf(':') + 1) : key;
        Optional<Setting<?>> setting = store.get().schema().setting(bare);
        return setting.map(found -> found.valueIn(store.get().current()));
    }

    @Override
    public Optional<String> display(String key) {
        if (settings.storeOf(key).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(settings.display(key));
    }

    @Override
    public boolean has(String permission) {
        return reader.hasPermission(permission);
    }

    @Override
    public boolean hasPlugin(String name) {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.isEnabled() && plugin.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasModule(String id) {
        // Core alone, or plugins that are not modules, report nothing to the hub: then nothing can be ruled out.
        return modules.isEmpty() || modules.contains(id.toLowerCase(Locale.ROOT));
    }

    @Override
    public Optional<String> fact(String name) {
        return HelpFacts.ask(name, reader);
    }

    @Override
    public Optional<String> money(String amount) {
        Currency currency = Economies.current().map(economy -> economy.currency()).orElse(null);
        if (currency == null) {
            return HelpValues.numeric(amount).map(HelpValues::number);
        }
        return currency.parse(amount).map(currency::format);
    }

    @Override
    public Optional<String> topicTitle(String id) {
        return titles.apply(id);
    }

    @Override
    public String serverName() {
        return RainsCore.isAvailable() ? RainsCore.get().serverName() : "this server";
    }

    @Override
    public String readerName() {
        return reader instanceof Player player ? player.getName() : "you";
    }
}
