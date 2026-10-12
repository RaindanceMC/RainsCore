package de.raindancer.core;

import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.content.achievement.Achievements;
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

    static RainsCorePlugin instance() {
        return instance;
    }

    /** Present only when Vault is installed; see Economies. */
    private de.raindancer.core.social.economy.VaultBridge vaultBridge;

    @Override
    public void onEnable() {
        long startedAt = System.nanoTime();
        instance = this;
        firstRun = !Files.exists(getDataFolder().toPath().resolve("config.yml"));

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
        databases = new Databases(getDataFolder().toPath(),
                () -> watchingThreads && getServer().isPrimaryThread());
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
        prefixes = new de.raindancer.core.ui.chat.PrefixService(
                new de.raindancer.core.ui.chat.PrefixFile(getDataFolder().toPath().resolve("prefix.yml")));
        prefixes.reload();
        nicknames = new de.raindancer.core.ui.identity.Nicknames(databases.core());
        nicknames.load();
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

        tablists = new Tablists(new TablistModel(identities), serverName());
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
        land = new Land(landPolicies, messages, System::currentTimeMillis);
        getServer().getPluginManager().registerEvents(new BlockProtectionListener(land), this);
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
        playtime = new de.raindancer.core.social.presence.Playtime(getDataFolder().toPath().resolve("playtime.yml"));
        playtime.load();
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
        link = de.raindancer.core.platform.link.NetworkLink.start(getDataFolder().toPath().resolve("link.yml"),
                de.raindancer.core.platform.log.Log.of("link")).orElse(null);

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
        java.nio.file.Path file = getDataFolder().toPath().resolve("address-hash.key");
        try {
            if (java.nio.file.Files.isRegularFile(file)) {
                byte[] kept = java.nio.file.Files.readAllBytes(file);
                if (kept.length >= 16) {
                    return kept;
                }
            }
            byte[] fresh = new byte[32];
            new java.security.SecureRandom().nextBytes(fresh);
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, fresh);
            return fresh;
        } catch (java.io.IOException unwritable) {
            getLogger().warning("Could not keep address-hash.key (" + unwritable.getMessage()
                    + "); blocked joins are recorded without an address.");
            return new byte[0];
        }
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


    /** The MOTD as plain text — what the tablist logo is drawn from. */
    private String serverName() {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(getServer().motd());
    }
}
