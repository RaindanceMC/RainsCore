package de.raindancer.core;

import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.content.achievement.Achievements;
import de.raindancer.core.content.advancement.BukkitAdvancementSink;
import de.raindancer.core.content.advancement.CustomAdvancements;
import de.raindancer.core.ui.banner.Banner;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.ChatChannels;
import de.raindancer.core.ui.profile.ProfileExtensions;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.chat.ClickActions;
import de.raindancer.core.ui.chat.ClickResult;
import de.raindancer.core.ui.chat.Style;
import de.raindancer.core.ui.menu.MenuListener;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.permission.GrantListener;
import de.raindancer.core.platform.permission.Grants;
import de.raindancer.core.content.items.CustomItemListener;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.content.loot.LootFiller;
import de.raindancer.core.content.loot.LootTables;
import de.raindancer.core.moderation.punishment.PunishmentGuard;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.moderation.punishment.PunishmentListener;
import de.raindancer.core.moderation.punishment.Punishments;
import de.raindancer.core.moderation.punishment.VanillaBanBridge;
import de.raindancer.core.platform.bukkit.BukkitActionBarSink;
import de.raindancer.core.platform.bukkit.BukkitAudiences;
import de.raindancer.core.platform.bukkit.BukkitBarViewers;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.core.ui.prompt.PromptListener;
import de.raindancer.core.ui.scoreboard.FastBoardFactory;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.data.sql.Databases;
import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.data.settings.SettingsChatInput;
import de.raindancer.core.data.settings.SettingsCommand;
import de.raindancer.core.data.settings.SettingsNavigation;
import de.raindancer.core.data.settings.SettingsRegistry;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.tablist.TablistModel;
import de.raindancer.core.ui.tablist.Tablists;
import de.raindancer.core.world.chunk.BukkitChunkLoader;
import de.raindancer.core.world.chunk.ChunkHolds;
import de.raindancer.core.platform.health.CoreHealth;
import de.raindancer.core.ui.checklist.Checklist;
import de.raindancer.core.ui.checklist.ChecklistChat;
import de.raindancer.core.data.settings.SettingsMenu;
import de.raindancer.core.data.runs.RunHistory;
import de.raindancer.core.ui.prompt.AnvilInput;
import de.raindancer.core.world.locate.StructureLocator;
import de.raindancer.core.ui.effect.BukkitEffectSink;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.moderation.invsee.Inventories;
import de.raindancer.core.moderation.invsee.InvseeListener;
import de.raindancer.core.moderation.invsee.InventoryViews;
import de.raindancer.core.moderation.invsee.OfflineEdits;
import de.raindancer.core.moderation.players.BukkitPlayerAdminSink;
import de.raindancer.core.moderation.players.PlayerAdmin;
import de.raindancer.core.moderation.players.PlayerPowerListener;
import de.raindancer.core.moderation.players.PlayerPowers;
import de.raindancer.core.moderation.vanish.BukkitVanishSink;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishListener;
import de.raindancer.core.moderation.vanish.VanishSight;
import de.raindancer.core.content.vote.Votes;
import de.raindancer.core.content.pack.BukkitPackSink;
import de.raindancer.core.content.pack.PackListener;
import de.raindancer.core.content.pack.PackMode;
import de.raindancer.core.content.pack.PackServer;
import de.raindancer.core.content.pack.ResourcePacks;
import de.raindancer.core.world.safety.BukkitBlocks;
import de.raindancer.core.world.safety.Safety;
import de.raindancer.core.world.combat.Combat;
import de.raindancer.core.world.combat.CombatListener;
import de.raindancer.core.world.protection.BlockProtectionListener;
import de.raindancer.core.world.protection.EnvironmentProtectionListener;
import de.raindancer.core.world.protection.InteractionProtectionListener;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandPolicies;
import de.raindancer.core.world.protection.LandPolicyStore;
import de.raindancer.core.world.protection.MobControlListener;
import de.raindancer.core.world.protection.MovementProtectionListener;
import de.raindancer.core.world.protection.Seclusion;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import de.raindancer.core.content.items.BoundItemListener;
import de.raindancer.core.platform.command.MistypedCommand;
import de.raindancer.core.platform.command.CommandDirectory;
import de.raindancer.core.platform.command.CommandNote;
import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.world.chunk.ChunkAt;
import de.raindancer.core.world.chunk.ChunkPregen;
import de.raindancer.core.world.manage.SeedHistory;
import de.raindancer.core.world.manage.WorldEntryPoints;
import de.raindancer.core.world.manage.WorldEntryRules;
import de.raindancer.core.world.manage.WorldRegenerator;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.World;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Rain's Core, as Paper loads it.
 *
 * <h2>What this class is allowed to do</h2>
 * Wiring, and nothing else. Every rule worth getting right lives behind a seam in a class that can
 * be tested without a server — {@link ActionBars} takes a sink and a clock, {@link Chat} takes an
 * {@link de.raindancer.core.ui.chat.Audiences}, {@link ClickActions} takes a clock — and this is where
 * the real implementations of those seams are plugged in. If logic starts appearing here, it is in
 * the wrong place.
 */
public final class RainsCorePlugin extends JavaPlugin implements RainsCore, Listener {

    private static final LogChannel log = Log.of("core");

    /** How often the action bar is repainted. Four times a second: well inside the client's fade. */
    private static final long ACTION_BAR_PERIOD_TICKS = 5L;

    /** How often expired chat buttons are swept. They are cheap; once a minute is plenty. */
    private static final long SWEEP_PERIOD_TICKS = 20L * 60L;

    /**
     * How long the protection bypass runs before somebody is asked whether they still need it.
     *
     * <p>Ten minutes, because that is comfortably longer than any normal fix — moving a chest,
     * clearing a grief — and short enough that a forgotten toggle is caught the same session it was
     * left on, not the next time somebody happens to notice their own claim has stopped protecting
     * them.
     */
    private static final Duration BYPASS_REMINDER_AFTER = Duration.ofMinutes(10);

    /** How often saved places are written out, if anything changed. */
    /**
     * How often the stores are written, in the seconds the async scheduler counts in.
     *
     * <p>Seconds rather than ticks because this is off the server's threads, where a tick is not a
     * unit of anything.
     */
    private static final long SAVE_PERIOD_SECONDS = 120L;

    /**
     * How often the audit journal is written, in seconds.
     *
     * <p>Ten, rather than the two minutes the YAML stores use. An audit entry lost to a crash is an
     * entry nobody can get back, and the cost of writing more often is one transaction per ten
     * seconds on a database nothing else is contending for.
     */
    private static final long AUDIT_FLUSH_PERIOD_SECONDS = 10L;

    /**
     * How often entries past the retention period are deleted, in seconds.
     *
     * <p>Hourly. Retention is measured in days, so anything more frequent is a range delete over the
     * largest table on the server to remove nothing.
     */
    private static final long AUDIT_PRUNE_PERIOD_SECONDS = 60L * 60L;

    /**
     * How often the tablist is rebuilt.
     *
     * <p>Two seconds. A player's world changes on an event and their ping changes continuously, so
     * an event-only tablist shows a stale latency for ever — but nothing here is urgent, and a
     * tablist rebuilt every tick is packets nobody asked for.
     */
    private static final long TABLIST_PERIOD_TICKS = 40L;

    /**
     * How often it is worked out who can see whom.
     *
     * <p>Ten ticks. Half a second of being visible while walking out of a private garden is not a privacy
     * failure, and doing it per move event is a lookup per player per tick for an answer that changes when
     * somebody crosses a line.
     */
    private static final long SECLUSION_PERIOD_TICKS = 10L;

    /**
     * How long after startup the resource pack is built.
     *
     * <p>A tick after everything has enabled, not during our own onEnable: the plugins that
     * contribute assets have not been enabled yet at that point, so building then would build a
     * pack with nothing in it.
     */
    private static final long PACK_BUILD_DELAY_TICKS = 20L;

    private static volatile RainsCorePlugin instance;

    private SettingsStore<CoreConfig> settings;
    private Chat chat;
    private ActionBars actionBars;
    private ClickActions clickActions;
    private ChatButtons buttons;
    private Scoreboards scoreboards;
    private BossBars bossBars;
    private final WorldEntryPoints worldEntryPoints =
            new WorldEntryPoints();
    private PoiStore places;
    private SeedHistory seedHistory;
    private WorldRegenerator worldRegenerator;
    private final WorldEntryRules worldEntryRules =
            new WorldEntryRules();
    private Identities identities;
    private de.raindancer.core.ui.identity.Nicknames nicknames;
    private de.raindancer.core.ui.chat.PrefixService prefixes;
    private de.raindancer.core.ui.identity.Nametags nametags;
    private Grants grants;
    /**
     * Who can still see a vanished moderator.
     *
     * <p>Registered rather than left to Bukkit's default, and registered as {@code FALSE} — nobody,
     * including operators. An unregistered node resolves to the operator default, which on a server
     * where the staff are operators means every one of them sees straight through vanish, so the
     * feature quietly does nothing for the people it exists for. It is granted explicitly instead.
     */
    private static final String SEE_VANISHED = "rainscore.vanish.see";

    private GrantListener grantListener;
    private Punishments punishments;
    private PunishmentGuard punishmentGuard;
    private de.raindancer.core.moderation.punishment.BlockedAttempts blockedAttempts;
    private VanillaBanBridge banBridge;
    private CustomItems items;
    private ItemFactory itemFactory;
    private ItemAbilities itemAbilities;
    private Achievements achievements;
    private final CustomAdvancements advancements = new CustomAdvancements(new BukkitAdvancementSink());
    private LootTables lootTables;
    private LootFiller lootFiller;

    /** Every plugin's settings, so the combined GUI can find them. Keyed by the schema's id. */
    private final Map<String, SettingsStore<?>> stores = new ConcurrentHashMap<>();
    /** The same, merged into one tree for the menu and the command. */
    private final SettingsRegistry registry = new SettingsRegistry();
    private SettingsNavigation navigation;
    private Tablists tablists;
    private ChatPrompts prompts;
    private Land land;
    private LandPolicies landPolicies;
    private LandPolicyStore landPolicyStore;
    private MovementProtectionListener movementProtection;
    private InteractionProtectionListener interactionProtection;
    private Seclusion seclusion;
    private ResourcePacks resourcePacks;
    private ChunkHolds chunks;
    private Safety safety;
    private Effects effects;
    private de.raindancer.core.world.teleport.TravelShow travelShow;
    private Votes votes;
    private Vanish vanish;
    private de.raindancer.core.social.presence.Playtime playtime;
    private de.raindancer.core.moderation.chatlog.ChatLog chatLog;
    private de.raindancer.core.social.presence.KnownNames knownNames;
    private de.raindancer.core.ui.changelog.Changelog changelog;
    private de.raindancer.core.moderation.maintenance.Maintenance maintenance;
    private de.raindancer.core.ui.chat.MentionCompletions mentionCompletions;
    private PlayerPowers powers;
    private PlayerAdmin players;
    private de.raindancer.core.moderation.players.PlayerBody bodies;
    private InventoryViews inventoryViews;
    private Inventories inventories;
    private Databases databases;
    private Combat combat;
    private StructureLocator structures;
    /** Whether this is the first start on this server — no config.yml existed yet. */
    private boolean firstRun;
    /** Owners already shown the start-up hint since this start; once each is enough. */
    private final Set<UUID> hinted = ConcurrentHashMap.newKeySet();
    private final Map<String, RunHistory> histories = new ConcurrentHashMap<>();
    private CombatListener combatListener;
    private Messages messages;
    /** False while the stores are being read, true once players can be on. */
    private volatile boolean watchingThreads;

    /**
     * The timers that write to disk, kept so shutdown can stop them.
     *
     * <p>Discarded handles were a race at shutdown: {@code onDisable} flushes and then closes the
     * databases, and an async timer firing in between writes into a database that is closing. The
     * entries it was carrying are then lost, quietly, exactly when a shutdown is the last chance to
     * write them.
     */
    private ScheduledTask auditFlushTask;
    private ScheduledTask savingTask;
    private Audit audit;
    private PackServer packServer;
    /** This server's link to another of its network, when link.yml asks for one. */
    private volatile de.raindancer.core.platform.link.ServerLink link;
    private volatile de.raindancer.core.platform.link.LinkRoster roster;
    private volatile de.raindancer.core.platform.link.LinkProfiles profiles;
    private java.util.Map<String, de.raindancer.core.platform.link.ServerLink> peerLinks = java.util.Map.of();
    private final java.util.Map<String, de.raindancer.core.platform.link.LinkRoster> rosters =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.List<de.raindancer.core.platform.link.LinkProfiles> allProfiles =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean linkFollows;
    private volatile de.raindancer.core.platform.link.LinkDirectory linkDirectory =
            de.raindancer.core.platform.link.LinkDirectory.NONE;
    private volatile de.raindancer.core.social.economy.LinkedEconomy linkedEconomy;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask profileTimer;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask rosterTimer;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask fileSyncTimer;
    private volatile de.raindancer.core.platform.link.FileSync fileSync;

    private de.raindancer.core.platform.network.NetworkConfig network;

    private static final long INVENTORY_SAVE_SECONDS = 300;
    private volatile de.raindancer.core.data.inventory.SavedInventories savedInventories;
    private volatile de.raindancer.core.data.inventory.InventoryCapture inventoryCapture;

    /** Every server's copy of every player's inventory. */
    @Override
    public de.raindancer.core.data.inventory.SavedInventories savedInventories() {
        return savedInventories;
    }

    /** The network's name on its database: one network per database, so one fixed channel. */
    private static final String BUS_CHANNEL = "rains";

    private volatile de.raindancer.core.platform.network.NetworkBus bus;

    /** How the servers of this network tell each other what changed. Silent on a server of its own. */
    @Override
    public de.raindancer.core.platform.network.NetworkBus bus() {
        return bus;
    }

    /**
     * SQLite files beside the plugin on a server of its own; namespaces in the network's central
     * PostgreSQL when network.yml says so. A central database that cannot be reached is said once,
     * loudly: every store then answers "nothing" instead of the network's data, and an owner who reads
     * no further than the first error must still learn why.
     */
    private Databases openDatabases(java.util.function.BooleanSupplier onServerThread) {
        if (!network.isNetwork()) {
            log.info("This server keeps its data in its own database files ({}).", network.serverId());
            return new Databases(getDataFolder().toPath(), onServerThread);
        }
        de.raindancer.core.data.sql.PostgresPool pool = de.raindancer.core.data.sql.PostgresPool.open(network.postgres());
        if (pool.isReachable()) {
            log.info("This server is '{}' (group '{}') on a network; its data is in {}.",
                    network.serverId(), network.group(), network.postgres().describe());
        } else {
            log.error("THE NETWORK DATABASE AT {} CANNOT BE REACHED. Nothing this server keeps — bans, homes, "
                    + "balances, settings — can be read or saved until it can. Check network.yml and that the "
                    + "database is running, then restart.", network.postgres().describe());
        }
        return Databases.central(pool, network.serverId(), onServerThread, getDataFolder().toPath());
    }

    /** Who this server is on its network, and where the network's data is. */
    @Override
    public de.raindancer.core.platform.network.NetworkConfig network() {
        return network;
    }

    static RainsCorePlugin instance() {
        return instance;
    }

    /** Present only when Vault is installed; see Economies. */
    private de.raindancer.core.social.economy.VaultBridge vaultBridge;

    @Override
    public void onEnable() {
        long startedAt = System.nanoTime();
        instance = this;

        // Before everything: which server this is on its network, and where the network's data is.
        network = de.raindancer.core.platform.network.NetworkConfig.load(getDataFolder().toPath(), System.getenv(),
                () -> getServer().getPort() > 0 ? "server_" + getServer().getPort() : "server");
        network.problems().forEach(problem -> log.warn("network.yml: {}", problem));

        // The database next, before any setting is read: from 2.0.0 every YAML document of every plugin —
        // config.yml included — is a row in it, brought over from its 1.x file the first time it is used.
        databases = openDatabases(() -> watchingThreads && getServer().isPrimaryThread());
        // On a network: which server this is, for places and seeds kept per server; and the bus the
        // servers tell each other what changed on. A server on its own keeps both blank and silent.
        if (network.isNetwork()) {
            de.raindancer.core.platform.network.ThisServer.set(network.serverId());
            bus = de.raindancer.core.platform.network.PostgresBus.start(databases.pool(), network.serverId(),
                    BUS_CHANNEL, java.time.Duration.ofSeconds(1));
        } else {
            de.raindancer.core.platform.network.ThisServer.set("");
            bus = de.raindancer.core.platform.network.LocalBus.alone(network.serverId());
        }
        de.raindancer.core.data.store.Documents.install(new de.raindancer.core.data.store.Documents(
                databases.of(de.raindancer.core.data.store.Documents.NAMESPACE, de.raindancer.core.data.store.Documents.SCHEMA),
                bus, getDataFolder().toPath().toAbsolutePath().getParent(),
                de.raindancer.core.platform.network.ThisServer.id(), System::currentTimeMillis));
        firstRun = !new de.raindancer.core.data.store.YamlStore(getDataFolder().toPath().resolve("config.yml")).exists();

        // Settings first: everything after this reads them.
        settings = settingsFor(SettingsSchema.of(CoreConfig.class, CoreConfig.DEFAULTS),
                getDataFolder().toPath().resolve("config.yml"));

        startLogging();
        settings.onChange(config -> startLogging());

        Style.configure(this::colourFor);

        actionBars = new ActionBars(new BukkitActionBarSink(), System::currentTimeMillis);
        scoreboards = new Scoreboards(new FastBoardFactory());
        bossBars = new BossBars(new BukkitBarViewers());
        getServer().getPluginManager().registerEvents(worldEntryPoints, this);

        // Before every store, because each of them is handed one. Opening a database applies its
        // schema, so a subsystem given one whose tables are not there yet fails a query at a time in
        // a different place each time.
        //
        // Loading on this thread is deliberate and is the one exemption: a plugin must not report
        // itself as enabled before its data has arrived, so the stores are read before onEnable
        // returns. Nothing is being stalled that anybody can see, because nobody is on yet.
        //
        // The guard is therefore armed at the end of onEnable rather than switched off. Passing a
        // supplier that always answers false would have disabled it for the whole run — a safety net
        // that never fires, which is worse than none because it reads as proof.
        audit = new Audit(databases.audit(), System::currentTimeMillis);

        // Before anything that says anything. The file is written out on the first start, and on every
        // start after that only the keys this version added are appended to it — the owner's wording,
        // ordering and comments are left exactly as they are, and the previous file is copied aside
        // first. A key that is missing anyway falls back to the jar's wording, so the merge failing
        // costs the owner the chance to reword the new lines rather than the lines themselves.
        messages = new Messages(getDataFolder().toPath().resolve("messages.yml"));
        messages.mergeMissing(getResource("messages.yml"));
        // Every earlier wording of messages.yml (listed in the index), so a server's untouched lines
        // upgrade instead of keeping the words of whichever version first wrote its file. When the
        // wording changes again, copy the old file in and add it to the index.
        try (java.io.InputStream index = getResource("messages-retired/index.txt")) {
            if (index != null) {
                for (String name : new String(index.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .split("\\R")) {
                    if (!name.isBlank()) {
                        messages.retired(getResource("messages-retired/" + name.strip()));
                    }
                }
            }
        } catch (java.io.IOException unreadable) {
            de.raindancer.core.platform.log.Log.of("core").warn("The list of older message wordings could not be read: {}", unreadable.toString());
        }
        messages.load(getResource("messages.yml"));
        // The same lines without the jokes, for a server that set the tone to serious. Modules hand
        // theirs over the same way, next to their messages.yml.
        messages.seriousFrom(getResource("messages-serious.yml"));
        messages.tone(settings.current().messageTone());
        if (!messages.problems().isEmpty()) {
            log.warn("messages.yml: {}", String.join("; ", messages.problems()));
        }

        places = new PoiStore(databases.core());
        places.load();
        places.connect(bus);
        places.writeSoon(write -> Scheduling.async(this, write));

        seedHistory = new SeedHistory(databases.core(),
                // Off the world's threads, now: waiting for the saving timer leaves a seed recorded
                // just before a stop to be written during onDisable, on the server thread.
                System::currentTimeMillis, flush -> Scheduling.async(this, flush));
        seedHistory.load();
        worldRegenerator = new WorldRegenerator(seedHistory,
                task -> Scheduling.global(this, task));

        identities = new Identities(databases.core());
        identities.load();
        identities.connect(bus);
        de.raindancer.core.ui.chat.PrefixFile prefixFile =
                new de.raindancer.core.ui.chat.PrefixFile(getDataFolder().toPath().resolve("prefix.yml"));
        prefixes = new de.raindancer.core.ui.chat.PrefixService(prefixFile);
        prefixes.reload();
        prefixFile.onChange(prefixes::reload);
        nicknames = new de.raindancer.core.ui.identity.Nicknames(databases.core());
        nicknames.load();
        nicknames.connect(bus);
        de.raindancer.core.platform.command.PlayerTargets.useNicknames(nicknames);

        // Permissions this server has granted individuals. Not a permissions plugin — see Grants —
        // but the thing a server without one needs, and the thing the moderation module's staff
        // presets are built on. Registered LOWEST so everything else that fires on join already sees
        // what it grants.
        // Registered before anything asks. An unregistered node answers with the operator default,
        // which for this one means every operator sees through vanish — see SEE_VANISHED.
        if (getServer().getPluginManager().getPermission(SEE_VANISHED) == null) {
            getServer().getPluginManager().addPermission(new Permission(
                    SEE_VANISHED, "See players who have vanished",
                    PermissionDefault.FALSE));
        }

        if (de.raindancer.core.social.economy.VaultBridge.vaultPresent(getServer())) {
            vaultBridge = new de.raindancer.core.social.economy.VaultBridge(getServer());
            de.raindancer.core.social.economy.Economies.bridge(vaultBridge);
            log.info("Vault is installed: a Rain economy is offered to every Vault plugin, and without one "
                    + "Rain's plugins charge through whatever economy Vault has.");
        }

        grants = new Grants(getDataFolder().toPath(), this);
        grants.load();
        log.info(grants.usesLuckPerms()
                ? "Permissions granted through moderation and elsewhere are managed through LuckPerms."
                : "Permissions granted through moderation and elsewhere use the built-in local store — "
                        + "install LuckPerms for groups, inheritance and contexts.");
        grantListener = new GrantListener(this, grants);
        // What makes a promotion or a demotion take effect while the player is standing there. Without
        // it the grant sat in grants.yml doing nothing until they reconnected — and the revocation case
        // is the sharp one: somebody stripped of their powers kept them for the rest of the session.
        // Registered here rather than left to each caller, because every caller forgot.
        grants.onChange(who -> {
            // Written now rather than by the saving timer: a revocation lost to a crash is somebody
            // keeping powers that were taken away.
            Scheduling.async(this, grants::flush);
            Player affected = getServer().getPlayer(who);
            if (affected == null) {
                return;     // they get it all on join anyway
            }
            Runnable reapply = () -> {
                grantListener.apply(affected);
                // Vanish decides who may see hidden players once, at join, and caches it. A moderator
                // who is granted that node mid-session would otherwise not be able to see anybody until
                // they relogged — the same defect one layer up.
                if (vanish != null) {
                    vanish.maySeeVanished(who, VanishSight.sees(affected, vanish, SEE_VANISHED));
                }
            };
            // A grant arrives on whoever made it — a moderator's command, on their region. The
            // attachment and the recalculation belong to the affected player's own thread.
            Scheduling.onOwner(this, affected, reapply);
        });
        getServer().getPluginManager().registerEvents(grantListener, this);

        punishments = new Punishments(databases.core(),
                System::currentTimeMillis);
        punishments.load();
        punishments.connect(bus);
        punishments.writeSoon(write -> Scheduling.async(this, write));
        punishmentGuard = new PunishmentGuard(punishments, System::currentTimeMillis);
        punishmentGuard.messages(messages);
        banBridge = new VanillaBanBridge(punishments);
        applyModerationSettings();
        settings.onChange(config -> applyModerationSettings());
        blockedAttempts = new de.raindancer.core.moderation.punishment.BlockedAttempts(audit, System::currentTimeMillis);
        getServer().getPluginManager().registerEvents(
                new PunishmentListener(punishmentGuard, blockedAttempts, addressKey(), getServer()), this);

        items = new CustomItems(getDataFolder().toPath().resolve("items.yml"));
        items.load();
        itemFactory = new ItemFactory(this);
        itemAbilities = new ItemAbilities(System::currentTimeMillis);
        // The one thing that makes a custom item do anything when it is clicked. Without it every
        // definition and every ability on the server is inert, and nothing says so: the boot log still
        // counts the items, and clicking one is indistinguishable from clicking the block it is made of.
        getServer().getPluginManager().registerEvents(
                new CustomItemListener(items, itemFactory, itemAbilities, messages), this);

        lootTables = new LootTables(getDataFolder().toPath().resolve("loot.yml"));
        lootTables.load();
        lootFiller = new LootFiller(items, itemFactory);

        achievements = new Achievements(getDataFolder().toPath().resolve("achievements.yml"),
                databases.core(),
                System::currentTimeMillis);
        achievements.load();
        achievements.connect(bus);

        tablists = new Tablists(new TablistModel(identities), serverName());
        tablists.load(this::serverLoad);
        applyTablistSettings();
        // Applied again whenever they change, so switching the custom list off in a menu puts
        // every name back rather than freezing whatever was last drawn.
        settings.onChange(config -> applyTablistSettings());

        // World protection. Registered here with nothing to protect: the ground itself comes from whichever
        // plugin owns regions, and it registers a LandProvider once it is enabled. Until then every question
        // answers UNKNOWN rather than "nothing is protected" — see Land and LandVerdict.
        // Read from disk, not built fresh. Held in memory alone, an admin turning a flag off got what they
        // asked for until the next restart — which is worse than not offering the setting, because the
        // server then behaves differently after a restart than it did before one and nothing says why.
        landPolicyStore = new LandPolicyStore(getDataFolder().toPath().resolve("land-flags.yml"));
        landPolicies = landPolicyStore.load();
        landPolicyStore.problem().ifPresent(trouble -> log.warn("land-flags.yml: {}", trouble));
        // Another server's admin changed a flag: the same object every claim asks is brought in line, in place.
        landPolicyStore.onChange(() -> {
            de.raindancer.core.world.protection.LandPolicies fresh = landPolicyStore.load();
            // Only what was actually read: a failed read answers with the built-ins, and taking those would
            // quietly undo every flag an admin forced — protection switched off by a database hiccup.
            if (landPolicyStore.problem().isEmpty()) {
                landPolicies.restore(fresh.changed());
            } else {
                log.warn("Land flags changed elsewhere could not be read; the decisions in force are kept.");
            }
        });
        land = new Land(landPolicies, messages, System::currentTimeMillis);
        getServer().getPluginManager().registerEvents(new BlockProtectionListener(land), this);
        // Worlds a plugin locked (a hub, an arena) are enforced here, the same for every plugin that locks one.
        getServer().getPluginManager().registerEvents(new de.raindancer.core.world.protection.LockedWorldListener(), this);
        if (getServer().getPluginManager().getPermission(de.raindancer.core.world.protection.LockedWorlds.BUILD_PERMISSION) == null) {
            getServer().getPluginManager().addPermission(new org.bukkit.permissions.Permission(
                    de.raindancer.core.world.protection.LockedWorlds.BUILD_PERMISSION,
                    "Build in worlds a plugin locked (in creative)", org.bukkit.permissions.PermissionDefault.OP));
        }
        getServer().getPluginManager().registerEvents(new AnvilInput.Listener(this), this);
        interactionProtection = new InteractionProtectionListener(land, messages, audit);
        getServer().getPluginManager().registerEvents(interactionProtection, this);
        EnvironmentProtectionListener environmentProtection = new EnvironmentProtectionListener(land);
        getServer().getPluginManager().registerEvents(environmentProtection, this);
        getServer().getPluginManager().registerEvents(new MobControlListener(land), this);
        getServer().getPluginManager().registerEvents(
                new BoundItemListener(), this);
        // Coins, banknotes and tokens are never worth their material — see NonIngredients.
        getServer().getPluginManager().registerEvents(
                new de.raindancer.core.content.items.NonIngredientListener(), this);
        // Where players put watched blocks, so mined ore is told from placed ore — see PlacedBlocks.
        getServer().getPluginManager().registerEvents(new de.raindancer.core.world.blocks.PlacedBlocks.Listener(), this);
        getServer().getPluginManager().registerEvents(new de.raindancer.core.world.blocks.BlockChanges.Listener(), this);
        // Eggs a shop sold hatch mobs but never set a spawner — see NotForSpawners.
        getServer().getPluginManager().registerEvents(new de.raindancer.core.content.items.NotForSpawnersListener(
                player -> messages.send(player, "items.not-for-spawners")), this);
        movementProtection = new MovementProtectionListener(land, messages);
        // Told about each other after both exist, rather than one taking the other in its constructor: the
        // damage listener has to be registered before this one, and a constructor argument would be a cycle.
        environmentProtection.grounding(movementProtection);
        getServer().getPluginManager().registerEvents(movementProtection, this);

        // Who can be seen from outside a private area. On a timer rather than on every move: two players
        // walking towards each other generate a move event each per tick, and the answer only changes when
        // one of them crosses a border.
        seclusion = new Seclusion(this, land);
        Scheduling.globalTimer(this, SECLUSION_PERIOD_TICKS, SECLUSION_PERIOD_TICKS,
                task -> seclusion.refresh());

        clickActions = new ClickActions(System::currentTimeMillis);
        // Deliberately empty: Core registers no commands at all, so it has no callback command to
        // point buttons at until a plugin registers one and says what it called it. Until then
        // buttons render as readable text without a click, rather than as a button that silently
        // does nothing — see ChatButtons and CoreCommands.
        buttons = new ChatButtons(clickActions, "");
        chat = chatFor("Core");
        getServer().getPluginManager().registerEvents(
                new MistypedCommand.Listener(getServer()::getCommandMap, messages, buttons), this);
        // Core's own sections, signed with Core's own brand. Until this, they were unowned, and an
        // unowned section wears whatever plugin called prefixFrom last — which is how every warp and
        // home line on the test server announced itself as "Moderation".
        int signed = messages.claimSections(chat.brand()::chatPrefix);
        de.raindancer.core.social.desk.ServiceDesks.install(messages, chat.brand());
        getServer().getPluginManager().registerEvents(new de.raindancer.core.social.desk.DeskListener(), this);
        if (signed > 0) {
            log.debug("{} message section(s) signed as Core's own.", signed);
        }
        // Core's own commands, in the directory /commands reads. Reported rather than assumed: a
        // server registers whichever of CoreCommands' handlers it wants, so this is what Core offers
        // and the book is honest about a server that took none of them.
        commandDirectory.declareAll(List.of(
                CommandNote
                        .of("Core", "commands", "This book.",
                                "[plugin] — only that plugin's"),
                CommandNote
                        .of("Core", "changelog", "What changed on this server lately."),
                CommandNote
                        .of("Core", "maintenance", "Close the server for maintenance: only ops and its own list can join.",
                                "on [reason] — close it", "off — open it", "add|remove <player> — the list", "list")
                        .needing("rainscore.maintenance"),
                CommandNote
                        .of("Core", "settings", "Read and change what every plugin on this server does.")
                        .needing("rainscore.settings"),
                CommandNote
                        .of("Core", "documents", "Edit a plugin's settings or texts by hand: they are kept in the database.",
                                "list [plugin] — what there is", "export <plugin> <name> — write it to edit/",
                                "import — read every edited file back")
                        .needing("rainscore.documents"),
                CommandNote
                        .of("Core", "warp", "Go to a named place.",
                                "<name> — go there",
                                "list — every warp you may use")
                        .needing("rainscore.warp.use"),
                CommandNote
                        .of("Core", "farmworld", "Go to a farm world, where digging is meant to happen.",
                                "<name> — go to that one",
                                "regen <name> confirm — rebuild it, which deletes it first")
                        .needing("rainscore.farmworld.use")));

        navigation = new SettingsNavigation(registry);
        // One chat listener for every prompt on the server. Three plugins each registering their
        // own is three plugins fighting over the next line a player types.
        prompts = new ChatPrompts(System::currentTimeMillis);
        // A chat answer arrives on a Netty thread, and a plugin's callback almost always touches the
        // player. Dispatched onto the thread that owns them, so no plugin using this has to know.
        prompts.runCallbacksOn((who, task) -> {
            Player answering = getServer().getPlayer(who);
            if (answering == null) {
                // They left between typing and this running. Answering "no" rather than dropping it
                // silently, so the line is reported as undelivered instead of as answered.
                return false;
            }
            Scheduling.entity(this, answering, task);
            return true;
        });
        getServer().getPluginManager().registerEvents(new PromptListener(prompts), this);
        // The commands are registered by RainsCoreBootstrap, before this runs. Paper fires the
        // COMMANDS lifecycle event during bootstrap, so a handler registered here would never fire
        // at all — silently, which is how both commands were dead until a live server was tried.
        new SettingsChatInput(this, navigation, chat, chat.brand(), prompts);

        effects = new Effects(new BukkitEffectSink(this), System::currentTimeMillis);
        // Asked through the field at teleport time: vanish is built later in onEnable than this.
        travelShow = new de.raindancer.core.world.teleport.TravelShow(effects,
                who -> vanish != null && vanish.isVanished(who));
        // Without this every '>delay' of a layered cue played at once. Waited out off-thread; the sink
        // then plays it on the thread that owns the player or the place.
        effects.delayedPlaybackVia((millis, what) ->
                Scheduling.asyncLater(this, millis, TimeUnit.MILLISECONDS, what));

        votes = new Votes(System::currentTimeMillis);
        players = new PlayerAdmin(new BukkitPlayerAdminSink(this));
        bodies = new de.raindancer.core.moderation.players.PlayerBody(
                new de.raindancer.core.moderation.players.BukkitPlayerBodySink(this));

        // God mode and instakill. Here rather than in a moderation plugin because they are answers to a
        // damage event, and there must be exactly one plugin on the server deciding what one means —
        // see Combat's own class note. Neither survives a restart; see PlayerPowers.
        powers = new PlayerPowers();
        getServer().getPluginManager().registerEvents(new PlayerPowerListener(powers), this);

        vanish = new Vanish(new BukkitVanishSink(this));
        playtime = new de.raindancer.core.social.presence.Playtime(databases.core());
        playtime.load();
        // A 1.x server's playtime.yml, brought into the database once (its minutes added) and kept renamed.
        playtime.importFrom(getDataFolder().toPath().resolve("playtime.yml"));
        playtime.connect(bus);
        // Every player's inventory into the network's database, per server: staff see it from anywhere, and it
        // outlives the server's files. Not applied on join — inventories stay each server's own.
        savedInventories = new de.raindancer.core.data.inventory.SavedInventories(databases.core(), System::currentTimeMillis);
        inventoryCapture = new de.raindancer.core.data.inventory.InventoryCapture(this, savedInventories,
                de.raindancer.core.platform.network.ThisServer::id);
        getServer().getPluginManager().registerEvents(inventoryCapture, this);
        Scheduling.asyncTimer(this, INVENTORY_SAVE_SECONDS, INVENTORY_SAVE_SECONDS,
                task -> Scheduling.global(this, inventoryCapture::saveEveryone));
        knownNames = new de.raindancer.core.social.presence.KnownNames(getDataFolder().toPath().resolve("known-names.yml"));
        knownNames.load();
        de.raindancer.core.platform.command.PlayerTargets.useKnownNames(knownNames);
        // Everybody with a player file, once: a server older than this still finds the players its name cache
        // forgot. Off the server's threads — reading a name may read the player's file.
        org.bukkit.OfflinePlayer[] everybody = getServer().getOfflinePlayers();
        Scheduling.async(this, () -> {
            // Most recently seen first: two old files with one name, and the one who has it now claims it.
            java.util.List<org.bukkit.OfflinePlayer> newestFirst = new java.util.ArrayList<>(java.util.List.of(everybody));
            newestFirst.sort(java.util.Comparator.comparingLong((org.bukkit.OfflinePlayer who) -> {
                try {
                    return who.getLastSeen();
                } catch (RuntimeException unreadable) {
                    return 0L;
                }
            }).reversed());
            for (org.bukkit.OfflinePlayer who : newestFirst) {
                try {
                    knownNames.seenIfUnknown(who.getUniqueId(), who.getName());
                } catch (RuntimeException unreadable) {
                    // One broken player file is one name fewer.
                }
            }
        });
        getServer().getPluginManager().registerEvents(
                new de.raindancer.core.social.presence.PresenceListener(playtime, knownNames), this);
        changelog = new de.raindancer.core.ui.changelog.Changelog(getDataFolder().toPath().resolve("changelog.yml"),
                getDataFolder().toPath().resolve("changelog-seen.yml"), System::currentTimeMillis);
        changelog.reload();
        changelog.problems().forEach(problem -> log.warn("{}", problem));
        getServer().getPluginManager().registerEvents(
                new de.raindancer.core.ui.changelog.ChangelogListener(this, changelog), this);
        maintenance = new de.raindancer.core.moderation.maintenance.Maintenance(
                getDataFolder().toPath().resolve("maintenance.yml"), System::currentTimeMillis);
        maintenance.load();
        maintenance.problems().forEach(problem -> log.error("maintenance.yml: {} — kept closed (ops only) until it is fixed or /maintenance off.", problem));
        if (maintenance.isOn()) {
            log.warn("Maintenance mode is on: only ops and the maintenance list can join. /maintenance off opens the server.");
        }
        getServer().getPluginManager().registerEvents(
                new de.raindancer.core.moderation.maintenance.MaintenanceListener(getServer(), maintenance), this);
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
            de.raindancer.core.platform.util.Scheduling.entity(this, online,
                    () -> playtime.seed(online.getUniqueId(), online.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE)));
        }
        Scheduling.globalTimer(this, 20L * 60, 20L * 60, ignored -> {
            for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
                playtime.minute(online.getUniqueId(), de.raindancer.core.social.presence.Away.isAway(online.getUniqueId()));
            }
        });
        // Every five minutes at most a few minutes are lost to a crash; the write is off the server's threads.
        Scheduling.globalTimer(this, 20L * 60 * 5, 20L * 60 * 5, ignored -> {
            if (playtime.isDirty()) {
                Scheduling.async(this, playtime::save);
            }
            if (knownNames.isDirty()) {
                Scheduling.async(this, knownNames::save);
            }
            if (changelog.isDirty()) {
                Scheduling.async(this, changelog::save);
            }
        });
        mentionCompletions = new de.raindancer.core.ui.chat.MentionCompletions(this, getServer(), vanish, nicknames);
        // A vanished player's line is held and they are asked first — one chat line undoes vanish.
        getServer().getPluginManager().registerEvents(new de.raindancer.core.moderation.vanish.VanishChatGuard(
                this, vanish, new de.raindancer.core.moderation.vanish.VanishedSpeech(), messages, buttons), this);
        nametags = new de.raindancer.core.ui.identity.Nametags(this, identities, vanish);
        getServer().getPluginManager().registerEvents(nametags, this);
        getServer().getPluginManager().registerEvents(
                new VanishListener(this, vanish, player -> VanishSight.sees(player, vanish, SEE_VANISHED)), this);
        // Hiding a player's entity does not take them off a *custom* tablist — that list is built from
        // getOnlinePlayers(), so a vanished moderator was on it with their name, world and ping, in the
        // one place anybody looks to see who is about.
        tablists.hiddenPlayers(vanish::everybodyVanished);
        inventoryViews = new InventoryViews(watcher -> {
            Player looking =
                    getServer().getPlayer(UUID.fromString(watcher));
            if (looking != null) {
                // On their own thread. A window is closed from wherever the reason arrived — a
                // login on the connection thread, a quit on somebody else's region thread — and on
                // Folia touching another player's inventory from the wrong region is an
                // IllegalStateException that takes the event with it. On Paper this is simply the
                // next tick.
                Scheduling.entity(this, looking, looking::closeInventory);
            }
        });
        // Where the server writes everybody who is not on it. Every place it can be is handed over and looked
        // through on each read: deciding once at startup picked the old folder on a fresh 26.x world (the new
        // one is only made by the first save), and every offline /invsee answered "never seen".
        inventories = new Inventories(this, inventoryViews, new OfflineEdits(System::currentTimeMillis),
                playerDataDirsOf(getServer().getWorlds().get(0)));
        // Every look and every change written down. Handed over here rather than taken in the
        // constructor because the journal needs its database, which needs the data folder, which is
        // not there until the plugin is enabling.
        inventories.audit(audit);
        inventories.messages(messages);
        // Applied again now that it exists: applyModerationSettings() ran once already, before this
        // constructor, and skipped these four because there was nothing yet to set them on.
        applyModerationSettings();
        getServer().getPluginManager().registerEvents(new InvseeListener(inventories), this);
        applyNewSettings(settings.current());
        // Re-applied on every change, so a toggle in the menu takes hold without a restart —
        // which is the difference between a setting somebody uses and one they read about.
        settings.onChange(this::applyNewSettings);
        combat = new Combat();
        structures = new StructureLocator(this);
        applyCombatSettings();
        // Kept, because it holds one entry per player it has had to refuse and the quit handler has
        // to be able to drop them. A map that only grows is a leak on a server that runs for months.
        combatListener = new CombatListener(combat, System::currentTimeMillis, messages);
        // The damage event fires in the victim's region, and the attacker may be standing in another —
        // shooting across a boundary is ordinary. So the message goes out on their own thread.
        combatListener.tellOn((player, task) -> Scheduling.entity(this, player, task));
        getServer().getPluginManager().registerEvents(combatListener, this);

        chunks = new ChunkHolds(new BukkitChunkLoader(this));
        // A world by name, or null when it is not loaded — the seam that keeps every rule about
        // what is safe testable without a server.
        safety = new Safety(this, chunks, BukkitBlocks::of);

        // The one pack every plugin's assets end up in. Built off the main thread, because it is a
        // zip of everything every plugin contributed and a server that stalls on startup for it is
        // a server nobody waits for.
        resourcePacks = new ResourcePacks(getDataFolder().toPath().resolve("packs"),
                new BukkitPackSink());
        startResourcePacks(settings.current());
        java.nio.file.Path linkFile = getDataFolder().toPath().resolve("link.yml");
        if (network.isNetwork()) {
            // On a network every server reaches every other through the network's database: the two-server link
            // of 1.x stands down, and link.yml is left as it is for a server that leaves the network again.
            link = null;
            peerLinks = java.util.Map.of();
            linkServerName = "";
            log.info("On a network: link.yml is not used; the servers talk through the network's database.");
        } else {
            link = de.raindancer.core.platform.link.NetworkLink.start(linkFile,
                    de.raindancer.core.platform.log.Log.of("link")).orElse(null);
            peerLinks = de.raindancer.core.platform.link.NetworkLink.peers(linkFile, de.raindancer.core.platform.log.Log.of("link"));
            linkServerName = de.raindancer.core.platform.link.NetworkLink.serverName(linkFile);
        }
        java.util.Map<String, de.raindancer.core.platform.link.ServerLink> allLinks = new java.util.LinkedHashMap<>();
        if (link != null) {
            allLinks.put("", link);
        }
        allLinks.putAll(peerLinks);
        linkFollows = link != null && de.raindancer.core.platform.link.NetworkLink.follows(linkFile);
        // Vanished staff are not on the list: a name that completes over there is somebody seen here.
        java.util.function.Supplier<java.util.List<String>> seen = () -> getServer().getOnlinePlayers().stream()
                .filter(online -> !vanish.isVanished(online.getUniqueId()))
                .map(org.bukkit.entity.Player::getName).toList();
        de.raindancer.core.platform.link.LinkDirectory directory = new de.raindancer.core.platform.link.LinkDirectory();
        allLinks.forEach((peerName, end) -> {
            de.raindancer.core.platform.link.LinkRoster each =
                    new de.raindancer.core.platform.link.LinkRoster(end, this::linkName, seen, System::currentTimeMillis);
            rosters.put(peerName, each);
            directory.add(peerName, end, each);
            de.raindancer.core.platform.link.LinkProfiles parts = new de.raindancer.core.platform.link.LinkProfiles(end);
            parts.provide("identity", identities::export);
            parts.provide("punishments", punishments::export);
            allProfiles.add(parts);
            if (end == link) {
                roster = each;
                profiles = parts;
            }
            if (!(end == link && linkFollows)) {
                de.raindancer.core.social.economy.LinkedEconomy.serve(end,
                        de.raindancer.core.social.economy.Economies::current);
            }
        });
        linkDirectory = allLinks.isEmpty() ? de.raindancer.core.platform.link.LinkDirectory.NONE : directory;
        if (!allLinks.isEmpty()) {
            directory.serve(new de.raindancer.core.platform.link.LinkDirectory.Here() {
                @Override
                public java.util.Optional<de.raindancer.core.platform.link.LinkDirectory.Found> find(String name) {
                    return seenHere(name).map(online -> new de.raindancer.core.platform.link.LinkDirectory.Found(
                            online.getUniqueId(), online.getName(), linkName()));
                }

                @Override
                public boolean tell(String name, String key, java.util.List<String> values) {
                    java.util.Optional<org.bukkit.entity.Player> online = seenHere(name);
                    online.ifPresent(player -> de.raindancer.core.platform.util.Scheduling.entity(RainsCorePlugin.this,
                            player, () -> messages.send(player, key, values.toArray())));
                    return online.isPresent();
                }
            });
        }
        if (!allLinks.isEmpty()) {
            // Both sides call: the guest's call keeps the host fresh too, but either alone must be enough.
            rosterTimer = de.raindancer.core.platform.util.Scheduling.asyncTimer(this, 2, 5,
                    task -> rosters.values().forEach(each -> each.exchange().exceptionally(unreachable -> null)));
            tablists.elsewhere(this::playersByServer);
        }
        if (network.isNetwork()) {
            startPresence();
        }
        if (!allLinks.isEmpty()) {
            fileSync = new de.raindancer.core.platform.link.FileSync(getDataFolder().toPath().getParent(),
                    allLinks.values(), linkFollows ? link : null);
            fileSync.register("RainsCore/prefix.yml", () -> de.raindancer.core.platform.util.Scheduling.global(this,
                    prefixes::reload));
            if (linkFollows) {
                fileSyncTimer = de.raindancer.core.platform.util.Scheduling.asyncTimer(this, 10, 30,
                        task -> fileSync.pullAll());
            }
        }
        if (linkFollows) {
            profiles.follow("identity", identities::adopt);
            profiles.follow("punishments", (player, exported) -> {
                punishments.adopt(player, exported);
                org.bukkit.entity.Player online = getServer().getPlayer(player);
                if (online != null && de.raindancer.core.moderation.punishment.Punishments.banned(exported)) {
                    de.raindancer.core.platform.util.Scheduling.entity(this, online, () -> online.kick(
                            net.kyori.adventure.text.Component.text("You are banned: "
                                    + exported.getOrDefault("ban.reason", "no reason given"))));
                }
            });
            log.info("Server link: player names and styles are taken from the other server.");
            // A mute given over there while somebody is here holds here within half a minute, not at their next visit.
            profileTimer = de.raindancer.core.platform.util.Scheduling.asyncTimer(this, 30, 30, task -> {
                de.raindancer.core.platform.link.LinkProfiles current = profiles;
                if (current != null) {
                    getServer().getOnlinePlayers().forEach(online -> current.fetch(online.getUniqueId()));
                }
            });
            // After every plugin is enabled: only a server with no economy of its own uses the other's.
            de.raindancer.core.platform.util.Scheduling.globalLater(this, 1L, () -> {
                if (link != null && de.raindancer.core.social.economy.Economies.current().isEmpty()) {
                    linkedEconomy = new de.raindancer.core.social.economy.LinkedEconomy(link);
                    de.raindancer.core.social.economy.Economies.provide(this, linkedEconomy);
                    linkedEconomy.refreshCurrency().exceptionally(unreachable -> null);
                    log.info("Server link: money is the other server's — one balance on both.");
                }
            });
        }

        // From here on, database work on a thread running the world is a mistake and is reported as
        // one. Before here it was the startup read, which is allowed.
        watchingThreads = true;

        getServer().getPluginManager().registerEvents(this, this);
        // One listener for every menu in every plugin: a menu is its inventory's holder, so this
        // recognises our windows without a registry that would outlive the players in it.
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        // The scheduler hands the task its own handle, which neither of these wants.
        Scheduling.globalTimer(this, ACTION_BAR_PERIOD_TICKS, ACTION_BAR_PERIOD_TICKS,
                task -> actionBars.tick());
        Scheduling.globalTimer(this, SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS,
                task -> {
                    clickActions.sweep();
                    prompts.sweep();
                    votes.sweep();
                    // An offline edit whose moderator crashed would otherwise hold that player out
                    // of the server until a restart.
                    inventories.sweep();
                    checkBypassReminders();
                });
        Scheduling.globalTimer(this, TABLIST_PERIOD_TICKS, TABLIST_PERIOD_TICKS,
                task -> tablists.refresh());
        // Written on a timer rather than on every change: a disk write every time somebody sets a
        // home would be a disk write on the main thread, and isDirty() means an idle server writes
        // nothing at all.
        // The audit journal is written off the server's threads: recording an entry only queues it,
        // and this is where the queue is turned into rows. Separate from the flushes below because
        // those write YAML on the main thread by design and this must not.
        chatLog = new de.raindancer.core.moderation.chatlog.ChatLog(
                databases.of("chatlog", de.raindancer.core.moderation.chatlog.ChatLog.SCHEMA), System::currentTimeMillis);
        chatLog.enabled(settings.current().chatLogEnabled());
        getServer().getPluginManager().registerEvents(
                new de.raindancer.core.moderation.chatlog.ChatLogListener(chatLog, messages,
                        () -> settings.current().chatLogRetention()), this);
        Scheduling.asyncTimer(this, AUDIT_FLUSH_PERIOD_SECONDS, AUDIT_FLUSH_PERIOD_SECONDS, task -> chatLog.flush());
        // The network's event log only has to reach servers that are up; an hour covers any restart.
        if (bus instanceof de.raindancer.core.platform.network.PostgresBus central) {
            Scheduling.asyncTimer(this, 600, 600, task -> central.prune(java.time.Duration.ofHours(1)));
        }
        Scheduling.asyncTimer(this, AUDIT_PRUNE_PERIOD_SECONDS, AUDIT_PRUNE_PERIOD_SECONDS,
                task -> chatLog.forgetOlderThan(Duration.ofDays(settings.current().chatLogRetention())));
        auditFlushTask = Scheduling.asyncTimer(this, AUDIT_FLUSH_PERIOD_SECONDS,
                AUDIT_FLUSH_PERIOD_SECONDS, task -> audit.flush());
        Scheduling.asyncTimer(this, AUDIT_PRUNE_PERIOD_SECONDS, AUDIT_PRUNE_PERIOD_SECONDS,
                task -> audit.forgetOlderThan(
                        Duration.ofDays(settings.current().auditRetentionDays())));
        // On the ASYNC timer, not the global one. That was wrong until a review pointed it out: every
        // one of these writes a file or a database, and the global timer runs on the thread that ticks
        // the world. Two minutes between freezes is still a freeze.
        //
        // Worth noting how it survived this long: the guard in Databases would have reported it, and
        // the live checks never ran long enough for the first tick to arrive. A safety net only helps
        // if something reaches it.
        savingTask = Scheduling.asyncTimer(this, SAVE_PERIOD_SECONDS, SAVE_PERIOD_SECONDS, task -> {
            places.flush();
            seedHistory.flush();
            histories.values().forEach(RunHistory::flush);
            identities.flush();
            nicknames.flush();
            grants.flush();
            punishments.flush();
            items.flush();
            achievements.flush();
            lootTables.flush();
        });

        Banner banner = Banner.of(getName(), "core utils for Raindancer118's plugins")
                .version(getPluginMeta().getVersion())
                .by("Raindancer118")
                .fact("Settings", registry.keys().size() + " across "
                        + registry.topics().visibleRoots().size() + " categories")
                .fact("Logs", getDataFolder().toPath().resolve("logs").toString())
                .fact("Places", places.all().size() + " remembered")
                // A home is a Poi of kind "home", a warp one of kind "warp" — see homes-module's
                // HomeCatalogue and warp-module's WarpRegistry. RainsCore has no domain class of its
                // own for either any more, since nothing in Core needs to reach for one directly; the
                // counts are still worth their own line rather than being lost inside "Places"
                // reasoned about as a single undifferentiated total.
                .fact("Homes", places.ofKind("home").size() + " kept")
                .fact("Warps", places.ofKind("warp").size() + " kept")
                .fact("In force", punishments.allActive().size() + " punishment(s)")
                .fact("Items", items.all().size() + " defined")
                .fact("Achievements", achievements.all().size() + " defined")
                .fact("Loot tables", lootTables.all().size() + " defined")
                .fact("Scheduler", Scheduling.isFolia() ? "Folia, regionised" : "Paper");
        for (String problem : places.problems()) {
            banner.warning("places.yml: " + problem);
        }
        for (String problem : settings.problems()) {
            log.warn("config.yml: {}", problem);
            banner.warning("config.yml: " + problem);
        }
        banner.took(Duration.ofNanos(System.nanoTime() - startedAt))
                .print(getComponentLogger());
        adoptPlayersAlreadyOnline();
        // Who may see vanished players is worked out at join and on Core's own grants, but somebody can be
        // made op or given a node through LuckPerms mid-session, and no event says so. Asking again every
        // few seconds costs a permission lookup per player; maySeeVanished does nothing when it is unchanged.
        Scheduling.globalTimer(this, 100L, 100L, ignored -> {
            for (Player online : getServer().getOnlinePlayers()) {
                Scheduling.entity(this, online, () -> vanish.maySeeVanished(online.getUniqueId(),
                        VanishSight.sees(online, vanish, SEE_VANISHED)));
            }
        });
    }

    /**
     * What a join would have done, for everybody already on the server when Core starts — a
     * {@code /reload}, or Core enabled late. No join event comes for them, so without this every
     * granted permission was missing and every moderator saw nobody vanished until they relogged.
     */
    private void adoptPlayersAlreadyOnline() {
        for (Player online : getServer().getOnlinePlayers()) {
            Scheduling.entity(this, online, () -> {
                grantListener.apply(online);
                vanish.maySeeVanished(online.getUniqueId(), VanishSight.sees(online, vanish, SEE_VANISHED));
            });
        }
    }

    /**
     * Puts the settings for the newer subsystems into effect.
     *
     * <p>Called at startup and whenever the settings change, so a toggle in the menu takes hold
     * without a restart — which is the difference between a setting somebody uses and one they read
     * about.
     */
    private void applyNewSettings(CoreConfig config) {
        de.raindancer.core.social.desk.ServiceDesks.inPersonOnly(
                de.raindancer.core.social.desk.ServiceDesk.INSURANCE, config.insuranceInPersonOnly());
        if (chatLog != null) {
            chatLog.enabled(config.chatLogEnabled());
        }
        if (messages != null) {
            messages.tone(config.messageTone());
        }
        if (travelShow != null) {
            travelShow.densities(config.teleportWaitParticles(), config.teleportArrivalParticles());
        }
        if (combat != null) {
            // A change in the menu takes hold without a restart, which is the difference between a
            // setting somebody uses and one they read about.
            applyCombatSettings();
        }
        effects.enabled(config.effectsEnabled());
        effects.minimumGap(Duration.ofMillis(config.effectsRepeatGapMillis()));
        vanish.flightWhileVanished(config.vanishFlight());
        if (vanish.isStaffSeeStaff() != config.vanishStaffSeeStaff()) {
            vanish.staffSeeStaff(config.vanishStaffSeeStaff());
            for (Player online : getServer().getOnlinePlayers()) {
                Scheduling.entity(this, online, () -> vanish.maySeeVanished(online.getUniqueId(),
                        VanishSight.sees(online, vanish, SEE_VANISHED)));
            }
        }
        if (tablists != null) {
            tablists.model().showPing(config.tablistShowPing());
            tablists.model().title(config.tablistTitle());
            tablists.model().logo("auto".equalsIgnoreCase(config.tablistLogo().trim())
                    ? TablistModel.logoFor(serverName())
                    : framesOf(config.tablistLogo()));
            tablists.headerFrames(framesOf(config.tablistHeaderFrames()), config.tablistFrameTicks());
            tablists.footerFrames(framesOf(config.tablistFooterFrames()), config.tablistFrameTicks());
        }
    }

    /**
     * Animation frames as a server owner writes them: one line, separated by a bar.
     *
     * <p>A list in a flat settings file has to be a string, and a bar is the one separator that does
     * not appear in MiniMessage or in anything anybody would put in a header.
     */
    private static List<String> framesOf(String written) {
        if (written == null || written.isBlank()) {
            return List.of();
        }
        return Arrays.stream(written.split("\\|"))
                .map(String::trim)
                .filter(frame -> !frame.isEmpty())
                .toList();
    }

    /**
     * Brings the resource pack up: settings, the web server, then a build off the main thread.
     *
     * <p>Deliberately survives every part of itself failing. A port that is taken, a contributed
     * pack that is not a zip, or no contributions at all must leave a server that starts normally
     * and plays normally — a resource pack is not worth refusing to boot over.
     */
    private void startResourcePacks(CoreConfig config) {
        resourcePacks.required(config.packsRequired());
        resourcePacks.description(config.packsDescription());
        resourcePacks.mode(config.packsCombine() ? PackMode.COMBINED : PackMode.STACKED);
        if (!config.packsEnabled()) {
            return;
        }
        if (config.packsServe()) {
            packServer = new PackServer(getDataFolder().toPath().resolve("packs"),
                    config.packsBind(), config.packsPort());
            packServer.publicAddress(config.packsPublicAddress());
            if (!packServer.givesClientsAReachableAddress()) {
                log.warn("The resource pack server is bound to {} and no public address is set, so "
                        + "players would be told to download from an address only this machine can "
                        + "reach. Set packs-public-address to how players reach this server.",
                        config.packsBind());
            }
            try {
                packServer.start();
                resourcePacks.urls(packServer::urlFor);
            } catch (IOException failure) {
                // Said plainly rather than thrown: the rest of the server is fine, and an owner
                // whose port 8123 is taken needs to read that sentence, not a stack trace.
                log.error("The resource pack server could not start on {}:{} ({}). Plugin assets "
                        + "will not be sent.", config.packsBind(), config.packsPort(),
                        failure.getMessage());
                packServer = null;
            }
        }
        getServer().getPluginManager().registerEvents(
                new PackListener(resourcePacks, config.packsOnJoin()), this);
        // Off the main thread on purpose, and after everything else has enabled: the plugins that
        // contribute have not all been enabled yet at this point in our own onEnable.
        Scheduling.globalLater(this, PACK_BUILD_DELAY_TICKS, () -> Scheduling.async(this, () -> {
            if (resourcePacks.contributions().isEmpty()) {
                return;
            }
            resourcePacks.rebuild().ifPresent(built -> {
                log.info("Resource pack ready: {} from {} contribution(s), {}",
                        built.digest().substring(0, 12), built.contributions(), built.readableSize());
                // Whoever joined before it was ready — or was online across a reload — was sent
                // nothing, and is not going to join again to be asked. sendTo skips anybody who
                // already has exactly this.
                if (settings.current().packsOnJoin()) {
                    resourcePacks.sendToAll(getServer().getOnlinePlayers().stream()
                            .map(Player::getUniqueId).toList());
                }
            });
        }));
    }

    @Override
    public void onDisable() {
        // The logfile last: everything above may want to say something on the way out.
        instance = null;
        if (vaultBridge != null) {
            vaultBridge.close();
        }
        de.raindancer.core.social.economy.Economies.clear();
        de.raindancer.core.social.economy.ItemValues.clear();
        de.raindancer.core.social.economy.PriceModifiers.clear();
        de.raindancer.core.social.economy.SaleStops.clear();
        de.raindancer.core.social.desk.ServiceDesks.clear();
        de.raindancer.core.social.roles.PlayerRoles.clear();
        de.raindancer.core.data.loadout.SideData.clear();
        de.raindancer.core.world.blocks.PlacedBlocks.clear();
        de.raindancer.core.social.economy.EconomyLevers.clear();
        de.raindancer.core.social.economy.PriceIndex.clear();
        de.raindancer.core.social.economy.Debts.clear();
        de.raindancer.core.content.items.InsuredItems.clear();
        de.raindancer.core.social.presence.Away.clear();
        de.raindancer.core.social.presence.PresenceLines.clear();
        de.raindancer.core.moderation.rules.ServerRules.clear();
        de.raindancer.core.moderation.rules.RuleBreaches.clear();
        de.raindancer.core.moderation.reports.AutoReports.clear();
        de.raindancer.core.moderation.players.AdminModes.clear();
        de.raindancer.core.moderation.players.CheatRecords.clear();
        de.raindancer.core.moderation.chatlog.ChatLog.clearWatchers();
        de.raindancer.core.moderation.punishment.BlockedAttempts.clearWatchers();
        // The mirror of the startup exemption in onEnable. On the way out the scheduler is already
        // shutting down, so the final flushes below have to run on this thread, and there is nobody
        // left on the server for them to stall — reporting them as a mistake put an ERROR line in every
        // clean shutdown that had anything to write, which trains people to ignore the real ones.
        watchingThreads = false;
        if (chatLog != null) {
            chatLog.flush();
        }
        if (playtime != null && playtime.isDirty()) {
            playtime.save();
        }
        if (knownNames != null && knownNames.isDirty()) {
            knownNames.save();
        }
        if (changelog != null && changelog.isDirty()) {
            changelog.save();
        }
        de.raindancer.core.platform.command.PlayerTargets.useKnownNames(null);
        if (seclusion != null) {
            // Before anything else, and for the same reason as the chunk holds below: somebody left hidden by
            // a reload is invisible until they reconnect, with nothing on their screen to explain it.
            seclusion.revealEverybody();
        }
        if (chunks != null) {
            // Before anything else that touches the world: a force-loaded chunk is written into the
            // world's own data and survives a restart, so one left behind here is one ticking for
            // ever with nothing to say why.
            int released = chunks.releaseAll();
            if (released > 0) {
                log.info("Let go of {} chunk(s) that were being kept loaded.", released);
            }
        }
        if (fileSyncTimer != null) {
            fileSyncTimer.cancel();
            fileSyncTimer = null;
        }
        if (rosterTimer != null) {
            rosterTimer.cancel();
            rosterTimer = null;
        }
        rosters.values().forEach(de.raindancer.core.platform.link.LinkRoster::stop);
        rosters.clear();
        linkDirectory.stopServing();
        linkDirectory = de.raindancer.core.platform.link.LinkDirectory.NONE;
        roster = null;
        if (profileTimer != null) {
            profileTimer.cancel();
            profileTimer = null;
        }
        allProfiles.forEach(de.raindancer.core.platform.link.LinkProfiles::stop);
        allProfiles.clear();
        profiles = null;
        if (linkedEconomy != null) {
            de.raindancer.core.social.economy.Economies.retract(linkedEconomy);
            linkedEconomy = null;
        }
        if (link != null && !linkFollows) {
            de.raindancer.core.social.economy.LinkedEconomy.stopServing(link);
        }
        peerLinks.values().forEach(de.raindancer.core.platform.link.ServerLink::close);
        peerLinks = java.util.Map.of();
        if (link != null) {
            // Like the pack server: a port and threads that a reload must not leave behind.
            link.close();
            link = null;
        }
        if (packServer != null) {
            // Before anything else: it holds a port and two threads, and a reload that left them
            // behind would stop the next start from binding at all.
            packServer.stop();
            packServer = null;
        }
        if (inventoryViews != null) {
            // Before the flushes, while the listeners are still registered. The server disables
            // plugins before it kicks anybody, so a window still open here would be closed after
            // InvseeListener is gone: an offline edit never written, and whatever the moderator had
            // put into it destroyed with the window. Closed now, each one is written in place.
            int closed = inventoryViews.closeEverything();
            if (closed > 0) {
                log.info("Closed {} inventory window(s) on the way out.", closed);
            }
        }
        // Before anything is flushed by hand: a timer that fires while this method is closing the
        // databases writes into one that is going away, and loses whatever it was carrying.
        stopTimer(savingTask);
        stopTimer(auditFlushTask);
        if (audit != null) {
            // Before the databases are closed, and on this thread rather than scheduled: the
            // scheduler is already shutting down, so a task handed to it now may never run — and
            // this is the one flush whose entries cannot be recovered from anywhere else.
            int written = audit.flush();
            if (written > 0) {
                log.info("Wrote {} audit entr{} on the way out.", written,
                        written == 1 ? "y" : "ies");
            }
        }
        if (places != null) {
            places.flush();
        }
        if (seedHistory != null) {
            seedHistory.flush();
        }
        histories.values().forEach(RunHistory::flush);
        if (identities != null) {
            identities.flush();
        }
        if (nicknames != null) {
            nicknames.flush();
            de.raindancer.core.platform.command.PlayerTargets.useNicknames(null);
        }
        if (grants != null) {
            grants.flush();
        }
        if (grantListener != null) {
            // Before the plugin goes: an attachment outliving the plugin that owns it is a permission
            // nothing can take away again.
            grantListener.removeEverything();
        }
        if (punishments != null) {
            punishments.flush();
        }
        if (items != null) {
            items.flush();
        }
        if (achievements != null) {
            achievements.flush();
        }
        if (lootTables != null) {
            lootTables.flush();
        }
        if (nametags != null) {
            nametags.shutdown();
        }
        if (tablists != null) {
            // Before anything else: the teams it makes live on the main scoreboard, and one left
            // behind survives a /reload with nothing holding it.
            tablists.shutdown();
        }
        if (bossBars != null) {
            bossBars.shutdown();
        }
        if (scoreboards != null) {
            // Before the settings are written: a board left behind survives a /reload and there is
            // then nothing holding it to take it away.
            scoreboards.shutdown();
        }
        if (settings != null) {
            settings.save();
        }
        if (inventoryCapture != null) {
            // Everybody still on, while the database is still open.
            inventoryCapture.saveEveryoneNow();
        }
        if (networkCalls != null) {
            networkCalls.close();
        }
        if (presence != null) {
            // Off the list at once rather than when the heartbeat goes stale.
            presence.clearThisServer();
            networkServers.leave();
        }
        // Before the databases close: a plugin disabled after Core must not find a store on a closed database.
        de.raindancer.core.data.store.Documents.uninstall();
        if (bus != null) {
            // After the last flush has announced itself, before the pool it publishes through is closed.
            bus.close();
        }
        if (databases != null) {
            // After every flush above and before the logfile: closing a database folds its
            // write-ahead log back into the file, and a database left open keeps a .db-wal beside it.
            databases.close();
        }
        log.info("Rain's Core is going down.");
        Log.shutdown();
    }

    /**
     * Registers the one command this plugin owns.
     *
     * <p>Through Paper's lifecycle rather than a {@code commands:} block in the plugin file, because
     * that form also registers the bare {@code /click}, and taking a word that common out of every
     * other plugin's hands to serve an implementation detail would be rude. Here only the namespaced
     * form exists, which is the only one a button ever uses.
     */
    /**
     * Where the given world keeps everybody's saved inventory.
     *
     * <p>Minecraft 26.1 moved this from {@code <world>/playerdata/} to {@code <world>/players/data/},
     * folding it in beside {@code advancements} and {@code stats} under one {@code players/} folder.
     * A world that has been converted has the new folder; one that has not — an older save, or a
     * server not yet on 26.1 — still has only the old one. Every candidate is handed over and the
     * one holding a player's file is found on each read: a fresh world has neither folder at startup.
     */
    private java.util.List<Path> playerDataDirsOf(World world) {
        java.util.Set<Path> roots = new java.util.LinkedHashSet<>();
        // The level's own folder; on 26.x a world's folder may be its dimension's, three levels down.
        roots.add(getServer().getWorldContainer().toPath().resolve(world.getName()).toAbsolutePath().normalize());
        roots.add(world.getWorldFolder().toPath().toAbsolutePath().normalize());
        java.util.List<Path> folders = new java.util.ArrayList<>();
        for (Path root : roots) {
            folders.add(root.resolve("players").resolve("data"));
        }
        for (Path root : roots) {
            folders.add(root.resolve("playerdata"));
        }
        return folders;
    }

    /**
     * Reads the moderation half of the settings onto the guard.
     *
     * <p>Recording and enforcing are separate switches on purpose: the record is useful on its own —
     * it is the history a moderator reads — and a server driving punishments from somewhere else
     * should be able to keep its own behaviour without losing it.
     */
    private void applyModerationSettings() {
        CoreConfig config = settings.current();
        punishmentGuard.enabled(config.enforcePunishments());
        punishmentGuard.enforce(PunishmentKind.MUTE, config.enforceMutes());
        punishmentGuard.enforce(PunishmentKind.FREEZE, config.enforceFreezes());
        punishmentGuard.appealMessage(config.appealMessage());

        // Four settings the appearance/invsee page has had since it was written, and none of them
        // did anything until now — Inventories never asked, so switching "Looking inside inventories"
        // off in the running config changed nothing a player could see.
        //
        // Null the first time this runs: it is called once before Inventories exists yet, and again
        // right after it is built, so the current config still reaches it immediately rather than
        // waiting for the next change.
        if (inventories != null) {
            inventories.enabled(config.invseeEnabled());
            inventories.allowEditing(config.invseeAllowEditing());
            inventories.allowEquipment(config.invseeAllowEquipment());
            inventories.showEnderChest(config.invseeShowEnderChest());
        }
    }

    /**
     * Reads the combat half of the settings onto the rules.
     *
     * <p>Only the server-wide switches. A world's own rule is set by whoever wants one — a plugin, or
     * a command — and reading the settings must not wipe those: somebody who reloads the config would
     * otherwise turn PvP back on inside an arena without touching anything to do with it.
     */
    private void applyCombatSettings() {
        CoreConfig config = settings.current();
        combat.pvp(config.combatPvp());
        combat.playersMayHurtMobs(config.combatPlayersMayHurtMobs());
        combat.mobsMayHurtPlayers(config.combatMobsMayHurtPlayers());
    }

    /**
     * Asks whoever has been bypassing protection for a while whether they still need it.
     *
     * <p>One question, two ways to answer it — extend, or say "stop asking" outright — because the
     * point is not to force the bypass off. Plenty of admin work genuinely runs long. The point is
     * that it must never again be a thing somebody discovers by accident three days later.
     */
    private void checkBypassReminders() {
        for (UUID id : land.dueForBypassReminder(BYPASS_REMINDER_AFTER)) {
            Player player = getServer().getPlayer(id);
            if (player == null) {
                continue;
            }
            // Asked either way, so a player who never answers is asked again in another ten minutes
            // rather than every minute until they do.
            land.postponeBypassReminder(id);

            ChatButton stillWorking = buttons.label("<green>[Still working]</green>")
                    .tooltip("<gray>Keep the bypass on — ask again in ten minutes")
                    .forOnly(id).expiringIn(BYPASS_REMINDER_AFTER)
                    .does(clicker -> {
                        land.postponeBypassReminder(clicker);
                        messages.send(player, "land.bypass-reminder-extended");
                    });
            ChatButton stopAsking = buttons.label("<gray>[Stop asking]</gray>")
                    .tooltip("<gray>Keep the bypass on without being asked again this session")
                    .forOnly(id).expiringIn(BYPASS_REMINDER_AFTER)
                    .does(clicker -> {
                        land.silenceBypassReminder(clicker);
                        messages.send(player, "land.bypass-reminder-silenced");
                    });
            player.sendMessage(messages.prefixed("land.bypass-reminder")
                    .appendNewline().append(buttons.row(stillWorking, stopAsking)));
        }
    }

    /** Reads the tablist's half of the settings onto it. */
    private void applyTablistSettings() {
        CoreConfig config = settings.current();
        tablists.enabled(config.tablistEnabled());
        tablists.groupByWorld(config.tablistGroupByWorld());
        tablists.showWorldOnEachLine(config.tablistWorldOnEachLine());
        tablists.header(config.tablistHeader());
        tablists.footer(config.tablistFooter());
        tablists.serverName(serverName());
        tablists.showPerformance(config.tablistShowPerformance());
    }

    /**
     * Points the logger at the data folder and at whatever the settings currently say.
     *
     * <p>Called again whenever the settings change, which is how a level or a retention edited in
     * game takes effect without a restart.
     */
    private void startLogging() {
        CoreConfig config = settings.current();
        Log.configure(getDataFolder().toPath().resolve("logs"), getLogger(),
                config.consoleLevel(), config.fileLevel(), config.logRetentionDays());
    }

    /**
     * One palette colour: what the server set, or empty to let the theme decide.
     *
     * <p>{@link Style} asks for a key like {@code style.item-name}; the settings record holds the
     * same values under names of its own, so this is the one place the two vocabularies meet.
     */
    private String colourFor(String key) {
        CoreConfig config = settings.current();
        return switch (key) {
            case Style.PRESET -> config.theme().name().toLowerCase(Locale.ROOT);
            case Style.TITLE_LABEL -> config.titleLabel();
            case Style.TITLE_VALUE -> config.titleValue();
            case Style.TITLE_SEPARATOR -> config.titleSeparator();
            case Style.ITEM_NAME -> config.itemName();
            case Style.ITEM_LORE -> config.itemLore();
            case Style.OK -> config.ok();
            case Style.WARN -> config.warn();
            case Style.BAD -> config.bad();
            case Style.DANGER -> config.danger();
            case Style.BRAND_FROM -> config.brandFrom();
            case Style.BRAND_TO -> config.brandTo();
            default -> null;
        };
    }

    // ------------------------------------------------------------------------ the API

    @Override
    public Chat chatFor(String tag) {
        return chatFor(new Brand(tag));
    }

    @Override
    public Chat chatFor(Brand brand) {
        return new Chat(brand, new BukkitAudiences());
    }

    @Override
    public ActionBars actionBars() {
        return actionBars;
    }

    @Override
    public Scoreboards scoreboards() {
        return scoreboards;
    }

    @Override
    public CustomItems items() {
        return items;
    }

    @Override
    public ItemAbilities itemAbilities() {
        return itemAbilities;
    }

    @Override
    public ItemFactory itemFactory() {
        return itemFactory;
    }

    @Override
    public LootTables lootTables() {
        return lootTables;
    }

    @Override
    public LootFiller lootFiller() {
        return lootFiller;
    }

    @Override
    public Achievements achievements() {
        return achievements;
    }

    @Override
    public CustomAdvancements advancements() {
        return advancements;
    }

    /** A data reload throws away every advancement loaded at runtime. */
    @EventHandler
    public void onDataReload(io.papermc.paper.event.server.ServerResourcesReloadedEvent event) {
        advancements.republish();
    }

    @Override
    public Punishments punishments() {
        return punishments;
    }

    @Override
    public PunishmentGuard punishmentGuard() {
        return punishmentGuard;
    }

    @Override
    public VanillaBanBridge banBridge() {
        return banBridge;
    }

    @Override
    public Identities identities() {
        return identities;
    }

    @Override
    public de.raindancer.core.ui.identity.Nicknames nicknames() {
        return nicknames;
    }

    @Override
    public de.raindancer.core.ui.chat.PrefixService prefixes() {
        return prefixes;
    }

    @Override
    public de.raindancer.core.ui.chat.MentionCompletions mentionCompletions() {
        return mentionCompletions;
    }

    @Override
    public Grants grants() {
        return grants;
    }

    @Override
    public void reapplyGrants(Player player) {
        if (grantListener != null) {
            grantListener.apply(player);
        }
    }

    @Override
    public PoiStore places() {
        return places;
    }

    @Override
    public BossBars bossBars() {
        return bossBars;
    }

    @Override
    public WorldEntryPoints worldEntryPoints() {
        return worldEntryPoints;
    }

    @Override
    public SeedHistory seedHistory() {
        return seedHistory;
    }

    @Override
    public WorldRegenerator worldRegenerator() {
        return worldRegenerator;
    }

    @Override
    public java.util.Optional<de.raindancer.core.platform.link.ServerLink> link() {
        return java.util.Optional.ofNullable(link);
    }

    /** Online under that name, case aside, and not vanished — whom another server may know is here. */
    private java.util.Optional<org.bukkit.entity.Player> seenHere(String name) {
        org.bukkit.entity.Player online = name == null || name.isBlank() ? null : getServer().getPlayerExact(name);
        if (online == null) {
            online = getServer().getOnlinePlayers().stream().filter(each -> each.getName().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        }
        return online == null || vanish.isVanished(online.getUniqueId()) ? java.util.Optional.empty()
                : java.util.Optional.of(online);
    }

    @Override
    public de.raindancer.core.platform.link.LinkDirectory links() {
        return linkDirectory;
    }

    @Override
    public java.util.Optional<de.raindancer.core.platform.link.LinkProfiles> profiles() {
        return java.util.Optional.ofNullable(profiles);
    }

    @Override
    public void sharePlayerData(String namespace) {
        de.raindancer.core.platform.link.LinkProfiles current = profiles;
        if (allProfiles.isEmpty() || namespace == null || namespace.isBlank()) {
            return;
        }
        String part = "data:" + namespace;
        java.util.function.Function<java.util.UUID, java.util.Map<String, String>> answer = player -> {
            // The saved data of somebody not here is read on the server's thread, as Bukkit expects.
            java.util.concurrent.CompletableFuture<java.util.Map<String, String>> read = new java.util.concurrent.CompletableFuture<>();
            de.raindancer.core.platform.util.Scheduling.global(this, () -> {
                try {
                    read.complete(de.raindancer.core.platform.link.PlayerDataPart.export(
                            getServer().getOfflinePlayer(player).getPersistentDataContainer(), namespace));
                } catch (RuntimeException | java.io.IOException failed) {
                    read.complete(java.util.Map.of());
                }
            });
            try {
                return read.get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception slow) {
                return java.util.Map.of();
            }
        };
        allProfiles.forEach(parts -> parts.provide(part, answer));
        if (linkFollows && current != null) {
            current.follow(part, (player, exported) -> {
                org.bukkit.entity.Player online = getServer().getPlayer(player);
                if (online != null && exported.containsKey("bytes")) {
                    de.raindancer.core.platform.util.Scheduling.entity(this, online, () ->
                            de.raindancer.core.platform.link.PlayerDataPart.adopt(online.getPersistentDataContainer(),
                                    namespace, exported));
                }
            });
        }
    }

    @Override
    public void shareProfile(String part,
                             java.util.function.Function<java.util.UUID, java.util.Map<String, String>> provide,
                             java.util.function.BiConsumer<java.util.UUID, java.util.Map<String, String>> adopt) {
        if (allProfiles.isEmpty() || part == null || part.isBlank()) {
            return;
        }
        allProfiles.forEach(parts -> parts.provide(part, provide));
        de.raindancer.core.platform.link.LinkProfiles current = profiles;
        if (linkFollows && current != null) {
            current.follow(part, adopt);
        }
    }

    @Override
    public void unshareProfile(String part) {
        if (part != null) {
            allProfiles.forEach(parts -> parts.forget(part));
        }
    }

    @Override
    public void syncFile(String relativePath, Runnable changed) {
        de.raindancer.core.platform.link.FileSync sync = fileSync;
        if (sync == null || relativePath == null) {
            return;
        }
        sync.register(relativePath, () -> de.raindancer.core.platform.util.Scheduling.global(this, changed));
        if (linkFollows) {
            de.raindancer.core.platform.util.Scheduling.async(this, () -> sync.pull(relativePath));
        }
    }

    @Override
    public boolean linkFollows() {
        return profiles != null && linkFollows;
    }

    // ---------------------------------------------------------------------------- the network's players

    private volatile de.raindancer.core.platform.network.NetworkServers networkServers;
    private volatile de.raindancer.core.platform.network.Presence presence;
    private volatile de.raindancer.core.platform.network.NetworkPlayers networkPlayers;
    private volatile de.raindancer.core.platform.network.NetworkCalls networkCalls;
    private volatile de.raindancer.core.platform.network.NetworkTravel networkTravel;

    @Override
    public java.util.Optional<de.raindancer.core.platform.network.NetworkCalls> networkCalls() {
        return java.util.Optional.ofNullable(networkCalls);
    }

    @Override
    public java.util.Optional<de.raindancer.core.platform.network.NetworkTravel> networkTravel() {
        return java.util.Optional.ofNullable(networkTravel);
    }

    /** Staff who were vanished when they left their last server, read before they are let in. */
    private final java.util.Set<java.util.UUID> arrivingVanished = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Who is on which server of the network: a heartbeat for this server, a row for each player here, and
     * the whole list read again every few seconds. Vanish follows staff from server to server.
     */
    private void startPresence() {
        networkServers = new de.raindancer.core.platform.network.NetworkServers(databases.core(), network.serverId(),
                network.group(), System::currentTimeMillis);
        presence = new de.raindancer.core.platform.network.Presence(databases.core(), networkServers, System::currentTimeMillis);
        presence.connect(bus);
        networkPlayers = new de.raindancer.core.platform.network.NetworkPlayers(presence, networkServers, bus,
                (player, key, values) -> {
                    Player online = getServer().getPlayer(player);
                    // Vanished is offline to anything sent from elsewhere: answering "said" would give them away.
                    if (online == null || vanish.isVanished(player)) {
                        return false;
                    }
                    Scheduling.entity(this, online, () -> messages.send(online, key, values.toArray()));
                    return true;
                });
        vanish.onChange((who, vanished) -> Scheduling.async(this, () -> presence.vanished(who, vanished)));
        networkCalls = new de.raindancer.core.platform.network.NetworkCalls(bus);
        networkTravel = new de.raindancer.core.platform.network.NetworkTravel(this,
                new de.raindancer.core.platform.network.Arrivals(databases.core()), network::serverId,
                System::currentTimeMillis, (player, key, server) -> messages.send(player, key, "server",
                        networkServers.byId(server).map(de.raindancer.core.platform.network.NetworkServers.Server::name)
                                .orElse(server)));
        getServer().getPluginManager().registerEvents(networkTravel, this);
        Scheduling.async(this, () -> {
            // Whatever a crash of this server left listed goes first.
            presence.clearThisServer();
            networkServers.heartbeat(serverName(), 0);
            presence.refreshAll();
        });
        Scheduling.asyncTimer(this, 5, 5, task -> {
            // Visible players only: the count is shown on other servers, and vanished staff are not there to count.
            networkServers.heartbeat(serverName(), (int) getServer().getOnlinePlayers().stream()
                    .filter(online -> !vanish.isVanished(online.getUniqueId())).count());
            presence.refreshAll();
        });
        tablists.elsewhere(this::playersByServer);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onPreLoginForPresence(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        if (presence != null && event.getLoginResult() == org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED
                && presence.wasVanished(event.getUniqueId())) {
            arrivingVanished.add(event.getUniqueId());
        }
    }

    /** Whether this server lets {@code player} vanish: one of the nodes a plugin registered as staff's, or op. */
    private boolean mayVanishHere(Player player) {
        return player.isOp() || vanish.staffNodes().stream().anyMatch(player::hasPermission);
    }

    @Override
    public java.util.Optional<de.raindancer.core.platform.network.NetworkPlayers> networkPlayers() {
        return java.util.Optional.ofNullable(networkPlayers);
    }

    @Override
    public java.util.Set<String> playersElsewhere() {
        if (networkPlayers != null) {
            java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            networkPlayers.everyone(false).stream().filter(here -> !here.server().equals(networkPlayers.self()))
                    .forEach(here -> names.add(here.name()));
            return names;
        }
        java.util.Set<String> all = new java.util.HashSet<>();
        rosters.values().forEach(each -> all.addAll(each.elsewhere()));
        return all;
    }

    @Override
    public java.util.Map<String, java.util.Set<String>> playersByServer() {
        if (networkPlayers != null) {
            java.util.Map<String, java.util.Set<String>> byName = new java.util.TreeMap<>();
            networkPlayers.byServer(false).forEach((server, here) -> {
                if (!server.equals(networkPlayers.self())) {
                    String shown = networkServers.byId(server).map(de.raindancer.core.platform.network.NetworkServers.Server::name)
                            .orElse(server);
                    here.forEach(each -> byName.computeIfAbsent(shown, key -> new java.util.TreeSet<>()).add(each.name()));
                }
            });
            return byName;
        }
        java.util.Map<String, java.util.Set<String>> byServer = new java.util.TreeMap<>();
        rosters.forEach((peerName, each) -> {
            String shown = !each.serverName().isBlank() ? each.serverName()
                    : peerName.isBlank() ? "the linked server" : peerName;
            byServer.computeIfAbsent(shown, key -> new java.util.TreeSet<>()).addAll(each.elsewhere());
        });
        return byServer;
    }

    @Override
    public WorldEntryRules worldEntryRules() {
        return worldEntryRules;
    }

    @Override
    public ResourcePacks resourcePacks() {
        return resourcePacks;
    }

    @Override
    public Land land() {
        return land;
    }

    @Override
    public LandPolicies landPolicies() {
        return landPolicies;
    }

    @Override
    public boolean saveLandPolicies() {
        try {
            landPolicyStore.save(landPolicies);
            return true;
        } catch (IOException couldNotWrite) {
            // The change is already live, so this is not worth refusing over — but an admin who is told
            // nothing will find their decision gone after the next restart and have no idea when it went.
            log.error("Could not write land-flags.yml ({}). Your flag change is in force now but will be "
                    + "lost when the server restarts.", couldNotWrite.getMessage());
            return false;
        }
    }

    @Override
    public Safety safety() {
        return safety;
    }

    @Override
    public ChunkHolds chunks() {
        return chunks;
    }

    @Override
    public Checklist health() {
        return CoreHealth.checklist(new CoreHealth.Facts(
                databases.core().isUsable(), databases.audit().isUsable(),
                settings.problems(), messages.problems(),
                scoreboards.isAvailable(), packServer != null && packServer.isRunning(),
                packServer == null || packServer.givesClientsAReachableAddress(),
                grants.usesLuckPerms(), Scheduling.isFolia()));
    }

    /**
     * What an owner is told when they join: a welcome the first time Core runs here, and a line with
     * a button whenever something about Core needs attention. Once per owner per start — a reminder
     * on every join is a reminder people learn to ignore.
     */
    private void offerOwnerHint(Player player) {
        if (!player.hasPermission(OWNER_HINT_PERMISSION) || !hinted.add(player.getUniqueId())) {
            return;
        }
        Scheduling.entityLater(this, player, OWNER_HINT_DELAY_TICKS, () -> {
            if (!player.isOnline()) {
                return;
            }
            Checklist health = health();
            int problems = health.problems().size();
            if (!firstRun && problems == 0) {
                return;
            }
            if (firstRun) {
                chat.tell(player, "<gray>Rain's Core is running here for the first time. Everything every "
                        + "plugin on this server can be told to do is in <white>/settings</white>.");
            }
            if (problems > 0) {
                chat.warn(player, "<count> thing<s> about Rain's Core need<v> attention.",
                        Chat.arg("count", problems), Chat.arg("s", problems == 1 ? "" : "s"),
                        Chat.arg("v", problems == 1 ? "s" : ""));
            }
            if (!buttons.isClickable()) {
                if (problems > 0) {
                    ChecklistChat.tell(player, health, null);
                }
                return;
            }
            UUID id = player.getUniqueId();
            player.sendMessage(buttons.row(
                    buttons.label("<green>[Show me]").tooltip("<gray>What is fine and what is not, with fixes")
                            .forOnly(id).does(clicker -> Scheduling.entity(this, player,
                                    () -> ChecklistChat.tell(player, health(), buttons))),
                    buttons.label("<aqua>[Open settings]").tooltip("<gray>Everything there is to set")
                            .forOnly(id).does(clicker -> Scheduling.entity(this, player,
                                    () -> SettingsMenu.root(player, chat.brand(), chat, navigation).open()))));
        });
    }

    private static final String OWNER_HINT_PERMISSION = "rainscore.settings";
    private static final long OWNER_HINT_DELAY_TICKS = 40L;

    @Override
    public StructureLocator structures() {
        return structures;
    }

    @Override
    public RunHistory runHistory(String game) {
        String key = game == null || game.isBlank() ? "default" : game.trim().toLowerCase(Locale.ROOT);
        return histories.computeIfAbsent(key, name -> {
            RunHistory history = new RunHistory(databases.core(), name);
            history.connect(bus);
            history.writeSoon(write -> Scheduling.async(this, write));
            Scheduling.async(this, history::load);
            return history;
        });
    }

    @Override
    public ChunkPregen pregeneration(
            List<ChunkAt> region) {
        return new ChunkPregen(new BukkitChunkLoader(this), region);
    }

    @Override
    public Effects effects() {
        return effects;
    }

    @Override
    public de.raindancer.core.world.teleport.TravelShow travelShow() {
        return travelShow;
    }

    @Override
    public Votes votes() {
        return votes;
    }

    @Override
    public de.raindancer.core.moderation.chatlog.ChatLog chatLog() {
        return chatLog;
    }

    @Override
    public de.raindancer.core.moderation.punishment.BlockedAttempts blockedAttempts() {
        return blockedAttempts;
    }

    /**
     * The key blocked joins' addresses are hashed with, made once and kept in the data folder. Lost, old hashes
     * simply stop matching new ones; nothing else depends on it.
     */
    private byte[] addressKey() {
        // The network's one key, so a blocked join hashed on one server is recognised on all of them; a 1.x
        // server's address-hash.key becomes it when it is the first.
        return secrets().bytes("address-hash", 32, getDataFolder().toPath().resolve("address-hash.key"))
                .orElseGet(() -> {
                    log.warn("The address key could not be read from the database; blocked joins are recorded "
                            + "without an address until it can.");
                    return new byte[0];
                });
    }

    private volatile de.raindancer.core.data.store.Secrets secrets;

    /** Secrets the servers of a network share — made once, kept in the database. */
    @Override
    public de.raindancer.core.data.store.Secrets secrets() {
        de.raindancer.core.data.store.Secrets here = secrets;
        if (here == null) {
            here = new de.raindancer.core.data.store.Secrets(databases.core(), System::currentTimeMillis);
            secrets = here;
        }
        return here;
    }

    @Override
    public de.raindancer.core.social.presence.Playtime playtime() {
        return playtime;
    }

    @Override
    public de.raindancer.core.ui.changelog.Changelog changelog() {
        return changelog;
    }

    @Override
    public de.raindancer.core.moderation.maintenance.Maintenance maintenance() {
        return maintenance;
    }

    @Override
    public Vanish vanish() {
        return vanish;
    }

    @Override
    public de.raindancer.core.ui.identity.Nametags nametags() {
        return nametags;
    }

    @Override
    public PlayerAdmin players() {
        return players;
    }

    @Override
    public de.raindancer.core.moderation.players.PlayerBody bodies() {
        return bodies;
    }

    @Override
    public PlayerPowers powers() {
        return powers;
    }

    @Override
    public InventoryViews inventoryViews() {
        return inventoryViews;
    }

    @Override
    public Messages messages() {
        return messages;
    }

    /** Held here rather than built lazily: modules report into it during their own enable. */
    private final CommandDirectory commandDirectory =
            new CommandDirectory();

    @Override
    public CommandDirectory commands() {
        return commandDirectory;
    }

    private final de.raindancer.core.ui.hub.Hub hub = new de.raindancer.core.ui.hub.Hub();

    @Override
    public de.raindancer.core.ui.hub.Hub hub() {
        return hub;
    }

    @Override
    public Audit audit() {
        return audit;
    }

    @Override
    public Combat combat() {
        return combat;
    }

    @Override
    public Databases databases() {
        return databases;
    }

    @Override
    public Inventories inventories() {
        return inventories;
    }

    @Override
    public ChatPrompts prompts() {
        return prompts;
    }

    @Override
    public Tablists tablists() {
        return tablists;
    }

    @Override
    public SettingsNavigation settingsNavigation() {
        return navigation;
    }

    @Override
    public ChatButtons buttons() {
        return buttons;
    }

    @Override
    public ClickActions clickActions() {
        return clickActions;
    }

    @Override
    public <T> SettingsStore<T> settingsFor(Plugin plugin, Class<T> type, T defaults) {
        return settingsFor(SettingsSchema.of(type, defaults),
                plugin.getDataFolder().toPath().resolve("config.yml"));
    }

    @Override
    public <T> SettingsStore<T> settingsFor(SettingsSchema<T> schema, Path file) {
        // Several records may share a file (a module with quests and orders); their keys must not collide there.
        Path where = file.toAbsolutePath().normalize();
        java.util.List<String> others = new java.util.ArrayList<>();
        for (SettingsStore<?> each : stores.values()) {
            if (each.file().toAbsolutePath().normalize().equals(where) && !each.schema().id().equals(schema.id())) {
                each.schema().settings().forEach(setting -> others.add(setting.key()));
            }
        }
        java.util.List<String> keys = schema.settings().stream().map(de.raindancer.core.data.settings.Setting::key).toList();
        for (String clash : SettingsSchema.clashes(keys, others)) {
            getSLF4JLogger().error("Settings {} in {}: {}. Rename one of them.", schema.id(), file.getFileName(), clash);
        }
        SettingsStore<T> store = new SettingsStore<>(schema, file);
        store.load();
        // Written back at once, so a new version's settings appear in the file the first time it
        // runs rather than the first time somebody changes something.
        store.save();
        stores.put(schema.id(), store);
        registry.add(store);
        return store;
    }

    @Override
    public void forgetSettings(SettingsStore<?> store) {
        if (store == null) {
            return;
        }
        // The conditional remove, not a plain one: a module that registers twice under one id — two
        // copies shaded into one host, or a second attempt after a first failure — must not have the
        // second's own unwind delete the first's still-live store from under it.
        stores.remove(store.schema().id(), store);
        registry.remove(store);
    }

    /** Every plugin's settings, for the combined GUI. */
    public Map<String, SettingsStore<?>> stores() {
        return Map.copyOf(stores);
    }

    /** Every plugin's settings merged into one tree — what the menu and the command walk. */
    public SettingsRegistry settingsRegistry() {
        return registry;
    }

    // ------------------------------------------------------------------------ housekeeping

    /**
     * The one basic, cross-cutting fact worth an audit line before any moderator has done anything:
     * that this account was seen at all. Feature {@code "player"} rather than a plugin's own tag,
     * because every plugin here shares the one audit journal and joining is not any one of theirs.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        offerOwnerHint(event.getPlayer());
        if (presence != null) {
            java.util.UUID id = event.getPlayer().getUniqueId();
            if (arrivingVanished.remove(id) && !vanish.isVanished(id)) {
                if (mayVanishHere(event.getPlayer())) {
                    // Before anybody here has had a tick to see them: vanished on the last server, vanished here.
                    vanish.vanish(id);
                } else {
                    // Vanished elsewhere is not a right to it here — demoted since, or a node only some servers
                    // grant. Forgotten, so the next server does not try either.
                    Scheduling.async(this, () -> presence.vanished(id, false));
                }
            }
            boolean hidden = vanish.isVanished(id);
            String name = event.getPlayer().getName();
            Scheduling.async(this, () -> presence.joined(id, name, hidden));
        }
        de.raindancer.core.platform.link.LinkProfiles following = profiles;
        if (following != null && linkFollows) {
            following.fetch(event.getPlayer().getUniqueId());
        }
        if (mentionCompletions != null) {
            mentionCompletions.refresh();
        }
        if (!settings.current().auditEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        audit.record(AuditEntry.of("player", "joined")
                .by(player.getUniqueId(), player.getName())
                .in(player.getWorld().getName()));
    }

    /**
     * Every gamemode change, wherever it came from — a vanilla {@code /gamemode}, another plugin, or
     * Core's own {@link de.raindancer.core.moderation.players.PlayerAdmin#gamemode}. The event fires
     * after the change, not before, so there is nothing here to refuse — only to write down.
     */
    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (!settings.current().auditEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        audit.record(AuditEntry.of("player", "changed gamemode")
                .by(player.getUniqueId(), player.getName())
                .in(player.getWorld().getName())
                .with("gamemode", event.getNewGameMode().name()));
    }

    /**
     * A plugin (or a module hosted in one) is going away: whatever callbacks it handed to Core and did
     * not take back are dropped here. Left in place they answer from a dead class loader — a land
     * provider whose database is closed, a combat rule twice over after a reload — and the land
     * provider would refuse the same plugin's fresh copy when it starts again.
     */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin gone = event.getPlugin();
        if (gone == this) {
            return;
        }
        ClassLoader loader = gone.getClass().getClassLoader();
        int dropped = worldEntryRules.forgetFrom(loader)
                + registry.forgetFrom(loader)
                + ProfileExtensions.forgetFrom(loader)
                + ChatChannels.forgetFrom(loader)
                + de.raindancer.core.ui.identity.NameBadges.forgetFrom(loader)
                + de.raindancer.core.ui.scoreboard.SidebarSections.forgetFrom(loader)
                + de.raindancer.core.platform.command.PlayerGroups.forgetFrom(loader)
                + de.raindancer.core.social.team.Groups.forgetFrom(loader)
                + de.raindancer.core.social.economy.Economies.forgetFrom(loader)
                + de.raindancer.core.social.economy.ItemValues.forgetFrom(loader)
                + de.raindancer.core.social.economy.PriceModifiers.forgetFrom(loader)
                + de.raindancer.core.social.economy.SaleStops.forgetFrom(loader)
                + de.raindancer.core.social.roles.PlayerRoles.forgetFrom(loader)
                + de.raindancer.core.data.loadout.SideData.forgetFrom(loader)
                + de.raindancer.core.world.blocks.PlacedBlocks.forgetFrom(loader)
                + de.raindancer.core.social.economy.EconomyLevers.forgetFrom(loader)
                + de.raindancer.core.social.economy.PriceIndex.forgetFrom(loader)
                + de.raindancer.core.social.economy.Debts.forgetFrom(loader)
                + de.raindancer.core.content.items.InsuredItems.forgetFrom(loader)
                + de.raindancer.core.social.desk.ServiceDesks.forgetFrom(loader)
                + de.raindancer.core.social.presence.Away.forgetFrom(loader)
                + de.raindancer.core.social.presence.PresenceLines.forgetFrom(loader)
                + de.raindancer.core.moderation.rules.ServerRules.forgetFrom(loader)
                + de.raindancer.core.moderation.rules.RuleBreaches.forgetFrom(loader)
                + de.raindancer.core.moderation.reports.AutoReports.forgetFrom(loader)
                + de.raindancer.core.moderation.players.AdminModes.forgetFrom(loader)
                + de.raindancer.core.world.protection.LockedWorlds.forgetFrom(loader)
                + de.raindancer.core.moderation.players.CheatRecords.forgetFrom(loader)
                + de.raindancer.core.moderation.chatlog.ChatLog.forgetFrom(loader)
                + de.raindancer.core.moderation.punishment.BlockedAttempts.forgetFrom(loader);
        if (combat != null) {
            dropped += combat.forgetFrom(loader);
        }
        if (grants != null) {
            dropped += grants.forgetFrom(loader);
        }
        if (achievements != null) {
            dropped += achievements.forgetFrom(loader);
        }
        if (itemAbilities != null) {
            dropped += itemAbilities.forgetFrom(loader);
        }
        if (land != null && land.forgetFrom(loader)) {
            dropped++;
        }
        if (dropped > 0) {
            log.info("{} stopped with {} thing(s) still registered with Rain's Core; they have been "
                    + "let go.", gone.getName(), dropped);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (presence != null) {
            java.util.UUID leaving = event.getPlayer().getUniqueId();
            Scheduling.async(this, () -> presence.left(leaving));
        }
        if (mentionCompletions != null) {
            mentionCompletions.forget(event.getPlayer().getUniqueId());
        }
        Player player = event.getPlayer();
        if (settings.current().auditEnabled()) {
            audit.record(AuditEntry.of("player", "quit")
                    .by(player.getUniqueId(), player.getName())
                    .in(player.getWorld().getName()));
        }
        actionBars.forget(player.getUniqueId());
        prompts.forget(player.getUniqueId());
        clickActions.forget(player.getUniqueId());
        scoreboards.forget(player.getUniqueId());
        itemAbilities.forget(player.getUniqueId());
        bossBars.forget(player.getUniqueId());
        tablists.forget(player);
        effects.forget(player.getUniqueId());
        land.forget(player.getUniqueId());
        movementProtection.forget(player.getUniqueId());
        AnvilInput.forget(player.getUniqueId());
        interactionProtection.forget(player.getUniqueId());
        seclusion.forget(player.getUniqueId());
        worldEntryPoints.forget(player.getUniqueId());
        if (combatListener != null) {
            combatListener.forget(player.getUniqueId());
        }
    }

    /**
     * Runs the action behind a chat button.
     *
     * <p>Called by the {@code /rcore:click <token>} command. Every answer is a sentence, because a
     * button that does nothing and says nothing is a button a player clicks four more times.
     */
    public void handleClick(Player clicker, String token, Chat chat) {
        ClickResult result = clickActions.run(clicker.getUniqueId(), token);
        switch (result) {
            case RAN -> {
                // The action said whatever needed saying.
            }
            case NOT_YOURS -> chat.raw(clicker, messages.prefixed("button.not-yours"));
            case SPENT -> chat.raw(clicker, messages.prefixed("button.already-answered"));
            case EXPIRED, UNKNOWN -> chat.raw(clicker,
                    messages.prefixed("button.no-longer-offered"));
            case FAILED -> chat.raw(clicker, messages.prefixed("button.failed"));
        }
    }
    /**
     * Stops one timer, if it was ever started.
     *
     * <p>Guarded because {@code onDisable} also runs when {@code onEnable} failed halfway, and a
     * plugin that throws on the way down while shutting down hides whatever went wrong on the way up.
     */
    private static void stopTimer(
            ScheduledTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }


    /** TPS over the last minute, the average tick, the heap. Empty on Folia, which has no global tick. */
    private java.util.Optional<de.raindancer.core.ui.tablist.ServerLoad> serverLoad() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            return java.util.Optional.of(new de.raindancer.core.ui.tablist.ServerLoad(getServer().getTPS()[0],
                    getServer().getAverageTickTime(), used, runtime.maxMemory() / (1024 * 1024)));
        } catch (UnsupportedOperationException folia) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public String hubCommand() {
        CoreConfig config = settings == null ? null : settings.current();
        String name = config == null || config.hubCommand() == null ? "" : config.hubCommand().strip();
        return name.isEmpty() ? "hub" : name;
    }

    /** server-name in link.yml, read once at start; empty without one. */
    private volatile String linkServerName = "";

    @Override
    public String serverName() {
        return de.raindancer.core.ui.text.ServerName.resolve(serverNameSetting(), "", getServer().motd());
    }

    /**
     * What the other servers of the network are told this one is called: the setting, else link.yml's own
     * server-name — not the MOTD, which is a sentence for the server list rather than a name.
     */
    private String linkName() {
        return de.raindancer.core.ui.text.ServerName.forNetwork(serverNameSetting(), linkServerName);
    }

    private String serverNameSetting() {
        CoreConfig config = settings == null ? null : settings.current();
        return config == null || config.serverName() == null ? "" : config.serverName();
    }
}
