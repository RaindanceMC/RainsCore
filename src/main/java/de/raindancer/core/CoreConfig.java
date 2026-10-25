package de.raindancer.core;

import de.raindancer.core.platform.log.LogLevel;
import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * Rain's Core's own settings — and the first real use of the settings model, which is deliberate:
 * if declaring these is awkward then it will be awkward for every other plugin too.
 *
 * <p>The palette lives here rather than in each plugin because a player cannot tell which of nine
 * jars drew the window in front of them and should not be able to. What stays per plugin is its tag.
 */
@Settings(id = "core", name = "Server basics", icon = Material.COMMAND_BLOCK, topics = {
        @Topic(path = "appearance", title = "Appearance", icon = Material.PAINTING,
                description = "What every window and message from every one of Rain's plugins is "
                        + "drawn in."),
        @Topic(path = "appearance/colours", title = "Colours", icon = Material.CYAN_DYE,
                description = "Leave one empty to let the chosen theme decide it."),
        @Topic(path = "config/logging", title = "Logging", icon = Material.WRITABLE_BOOK,
                description = "What is written down, and for how long."),
        @Topic(path = "config/chat", title = "Chat", icon = Material.NAME_TAG,
                description = "How long a clickable button in chat stays good for."),
        @Topic(path = "appearance/tablist", title = "Tablist", icon = Material.PLAYER_HEAD,
                description = "The player list: what it says, and how it is sorted."),
        @Topic(path = "moderation/vanish", title = "Vanish", icon = Material.GLASS,
                description = "Being properly not here, and who is allowed to notice."),
        @Topic(path = "moderation/invsee", title = "Looking Inside", icon = Material.CHEST,
                description = "Watching somebody's inventory, and what a watcher may touch."),
        @Topic(path = "moderation/audit", title = "Audit Journal", icon = Material.WRITABLE_BOOK,
                description = "The record of what moderators did, and how long it is kept."),
        @Topic(path = "world/combat", title = "Who May Hurt Whom", icon = Material.IRON_SWORD,
                description = "Whether players may fight each other, and whether they fight "
                        + "creatures."),
        @Topic(path = "appearance/effects", title = "Sounds & Particles", icon = Material.NOTE_BLOCK,
                description = "The cues every plugin plays, and how loud they are."),
        @Topic(path = "config/safety", title = "Safe Teleports", icon = Material.FEATHER,
                description = "What counts as somewhere safe to put a player."),
        @Topic(path = "config/packs", title = "Resource packs", icon = Material.PAINTING,
                description = "The one pack every plugin's assets go into, and how it is served."),
        @Topic(path = "moderation/chatlog", title = "Chat log", icon = Material.BOOK,
                description = "What everybody said in chat, kept for staff for a while."),
        @Topic(path = "world/desks", title = "Attendants", icon = Material.ARMOR_STAND,
                description = "People standing in the world who serve players: insurance agents and bankers."),
        @Topic(path = "moderation", title = "Moderation", icon = Material.IRON_AXE,
                description = "Whether punishments are acted on, and what a punished player is "
                        + "told. The record is kept either way."),
})
public record CoreConfig(

        @In("appearance") @Title("Theme")
        @Describe("The whole look, chosen by name. A colour set below wins over it.")
        Theme theme,

        @In("appearance/colours") @Title("Window title")
        @Describe("The fixed part of a window title: 'Claim' in 'Claim > base'.")
        String titleLabel,

        @In("appearance/colours") @Title("Window subject")
        @Describe("The part that changes: 'base' in 'Claim > base'.")
        String titleValue,

        @In("appearance/colours") @Title("Item name")
        String itemName,

        @In("appearance/colours") @Title("Item lore")
        String itemLore,

        @In("appearance/colours") @Title("Yes")
        String ok,

        @In("appearance/colours") @Title("Careful")
        String warn,

        @In("appearance/colours") @Title("No")
        String bad,

        @In("appearance/colours") @Title("About to destroy something")
        String danger,

        @In("appearance/colours") @Title("Tag gradient, near end")
        String brandFrom,

        @In("appearance/colours") @Title("Tag gradient, far end")
        String brandTo,

        @In("appearance") @Title("Title separator")
        @Describe("What goes between the parts of a window title.")
        String titleSeparator,

        @In("appearance") @Title("Message tone")
        @Describe("PLAYFUL adds a dry remark to refusals and errors; SERIOUS says only what happened. "
                + "Lines you reworded in messages.yml stay yours either way.")
        de.raindancer.core.ui.messages.Messages.Tone messageTone,

        @In("config/logging") @Title("Console level")
        @Describe("The least important thing the console shows.")
        LogLevel consoleLevel,

        @In("config/logging") @Title("Logfile level")
        @Describe("The least important thing written to plugins/RainsCore/logs/. Failures that stop "
                + "a plugin working are always written, whatever this says.")
        LogLevel fileLevel,

        @In("config/logging") @Title("Days of logs to keep") @Range(min = 1, max = 365)
        int logRetentionDays,

        @In("config/chat") @Title("Button lifetime in minutes") @Range(min = 1, max = 1440)
        @Describe("How long a clickable button in chat stays good for, when the plugin offering it "
                + "does not say.")
        int buttonMinutes,

        @In("appearance/tablist") @Title("Custom tablist")
        @Describe("Whether the player list is ours at all. Off leaves it exactly as vanilla.")
        boolean tablistEnabled,

        @In("appearance/tablist") @Title("Group by world")
        @Describe("Sorts players so everybody in the same world is together. This is what makes "
                + "the list say who is where.")
        boolean tablistGroupByWorld,

        @In("appearance/tablist") @Title("World on every line")
        @Describe("Also writes the world beside each name. Off by default: the grouping already "
                + "says it, and saying it twice is noise.")
        boolean tablistWorldOnEachLine,

        @In("appearance/tablist") @Title("Header")
        @Describe("What sits above the list. Empty uses the server's name and how many are on.")
        String tablistHeader,

        @In("appearance/tablist") @Title("Footer")
        @Describe("What sits below it. Empty lists how many players are in each world.")
        String tablistFooter,

        @In("appearance/tablist") @Title("Title")
        @Describe("The name at the very top, in your own markup. The header is the largest text a "
                + "server ever puts in front of a player; empty uses the MOTD in a gradient.")
        String tablistTitle,

        @In("appearance/tablist") @Title("Logo")
        @Describe("Lines of glyphs above the title, separated by | — block characters, or glyphs "
                + "from a resource pack you contribute. The word 'auto' draws one from the server "
                + "name using the same block letters as the startup banner.")
        String tablistLogo,

        @In("appearance/tablist") @Title("Show the ping as a number")
        @Describe("Writes the latency on each line. The five bars at the right-hand end are drawn "
                + "by the client and no server can remove them — but 30ms and 130ms look identical "
                + "in them, which is the half worth having.")
        boolean tablistShowPing,

        @In("appearance/tablist") @Title("Sort by rank")
        @Describe("Whether people with a rank are put above people without one. A plugin has to say "
                + "what a rank is; with nothing set this changes nothing.")
        boolean tablistSortByRank,

        @In("appearance/tablist") @Title("Animated header frames")
        @Describe("Extra header lines to cycle through, separated by | — the rules, an event, a "
                + "vote that is running. Empty leaves the header still.")
        String tablistHeaderFrames,

        @In("appearance/tablist") @Title("Animated footer frames")
        @Describe("The same for the footer, separated by |.")
        String tablistFooterFrames,

        @In("appearance/tablist") @Title("Refreshes per animation frame") @Range(min = 1, max = 40)
        @Describe("How many refreshes each frame lasts. The list redraws about twice a second, so "
                + "4 is roughly two seconds a frame; 1 is a strobe.")
        int tablistFrameTicks,

        @In("appearance/tablist") @Title("Refresh every (ticks)") @Range(min = 10, max = 200)
        @Describe("How often the list is rebuilt. A player's ping changes continuously, so an "
                + "event-only list would show a stale one for ever.")
        int tablistRefreshTicks,

        @In("moderation") @Title("Enforce punishments")
        @Describe("Whether bans, mutes and freezes actually stop anybody. Off still records them, "
                + "for a server that acts on them from somewhere else.")
        boolean enforcePunishments,

        @In("moderation") @Title("Enforce mutes")
        @Describe("Whether a muted player is stopped from talking.")
        boolean enforceMutes,

        @In("moderation") @Title("Enforce freezes")
        @Describe("Whether a frozen player is stopped from building and breaking.")
        boolean enforceFreezes,

        @In("moderation") @Title("Mirror bans to the server's ban list")
        @Describe("Writes every ban to banned-players.json as well, so vanilla tooling agrees and "
                + "the ban keeps working if this plugin is ever removed.")
        boolean mirrorBans,

        @In("moderation") @Title("Appeal message")
        @Describe("The line under a ban telling somebody how to appeal. Empty leaves it out.")
        String appealMessage,

        @In("config/packs") @Title("Combine plugin resource packs")
        @Describe("Whether the assets plugins contribute are built into one pack and sent. Off "
                + "means nothing is built or served, and a plugin's icons simply are not there.")
        boolean packsEnabled,

        @In("config/packs") @Title("Combine into a single pack")
        @Describe("Off sends each plugin's pack separately, stacked in order — the client has "
                + "supported that since 1.20.3, and it means adding a plugin only costs players "
                + "that plugin's download. On merges everything into one zip: one download and one "
                + "entry in the client's list, but any change rebuilds all of it.")
        boolean packsCombine,

        @In("config/packs") @Title("Send it on join")
        @Describe("Whether a player is offered the pack as they join, rather than only when "
                + "something asks for it.")
        boolean packsOnJoin,

        @In("config/packs") @Title("Required")
        @Describe("Whether refusing the pack disconnects the player. Leave this off unless the "
                + "server genuinely does not work without it: it turns every download failure, "
                + "including ones on the player's side, into somebody who cannot join.")
        boolean packsRequired,

        @In("config/packs") @Title("Pack description")
        @Describe("What the client shows in its list of packs.")
        String packsDescription,

        @In("config/packs") @Title("Serve it over HTTP")
        @Describe("Whether Core runs its own small web server for the pack. Turn this off if you "
                + "already have a web server or a CDN, and set the public address instead.")
        boolean packsServe,

        @In("config/packs") @Title("Listen on")
        @Describe("The address the pack server binds to. 0.0.0.0 is every interface.")
        String packsBind,

        @In("config/packs") @Title("Port") @Range(min = 0, max = 65535)
        @Describe("The port the pack server listens on. 0 lets the system pick a free one, which "
                + "is only useful with a public address that does not name a port.")
        int packsPort,

        @In("config/packs") @Title("Public address")
        @Describe("What clients are told to download from, when that is not what the server binds "
                + "to — behind a proxy, or on 0.0.0.0. Empty uses the bind address. Example: "
                + "https://packs.example.com")
        String packsPublicAddress,

        @In("moderation/vanish") @Title("Vanish")
        @Describe("Whether players can be hidden at all. Off leaves everything visible however "
                + "many plugins ask.")
        boolean vanishEnabled,

        @In("moderation/vanish") @Title("Staff see each other while vanished")
        @Describe("Whether somebody allowed to see hidden players sees other hidden staff. Off "
                + "means two vanished moderators are invisible to one another and spend the "
                + "evening walking into each other.")
        boolean vanishStaffSeeStaff,

        @In("moderation/vanish") @Title("Flight while vanished")
        @Describe("Whether vanishing also grants flight. Somebody invisible and walking gives "
                + "themselves away with footsteps and doors.")
        boolean vanishFlight,

        @In("moderation/vanish") @Title("Silent joining and leaving")
        @Describe("Whether a hidden player's arrival and departure are announced.")
        boolean vanishSilentJoin,

        @In("moderation/invsee") @Title("Looking inside inventories")
        @Describe("Whether inventories can be watched at all.")
        boolean invseeEnabled,

        @In("moderation/invsee") @Title("Allow editing")
        @Describe("Whether a watcher may change what somebody is carrying, or only look. Only one "
                + "person may edit an inventory at a time either way: two at once duplicates items.")
        boolean invseeAllowEditing,

        @In("moderation/invsee") @Title("Allow editing worn armour")
        @Describe("Whether a watcher may change armour and the off-hand. Off protects them, so a "
                + "click one slot too far cannot unequip somebody mid-fight.")
        boolean invseeAllowEquipment,

        @In("moderation/invsee") @Title("Show the ender chest")
        @Describe("Whether the ender chest is shown beside the inventory.")
        boolean invseeShowEnderChest,

        @In("moderation/audit") @Title("Keep a record of what moderators do")
        @Describe("Every moderation action is written down with who did it, to whom, and when — "
                + "searchable by moderator and by feature. Switching this off does not make the "
                + "actions stop, only the record of them.")
        boolean auditEnabled,

        @In("world/combat") @Title("Players may hurt each other")
        @Describe("PvP. Off means one player cannot damage another anywhere — a plugin may still "
                + "allow it in one world, or in an arena.")
        boolean combatPvp,

        @In("world/combat") @Title("Players may hurt creatures")
        @Describe("Off makes a building server: nothing a player does harms an animal or a monster.")
        boolean combatPlayersMayHurtMobs,

        @In("world/combat") @Title("Creatures may hurt players")
        @Describe("Off makes monsters harmless. Separate from the setting above because turning "
                + "only one off is usually a mistake — a server where zombies are dangerous and "
                + "cannot be fought back is a worse game, not a gentler one.")
        boolean combatMobsMayHurtPlayers,

        @In("moderation/audit") @Title("Keep the record for this many days")
        @Range(min = 1, max = 3650)
        @Describe("Older entries are deleted. These rows name real people and say what they did, "
                + "which is personal data: under the GDPR it may be kept while there is a reason "
                + "and no longer, so this is a real decision rather than a performance setting.")
        int auditRetentionDays,

        @In("appearance/effects") @Title("Sounds and particles")
        @Describe("Whether the shared cues every plugin plays are heard at all.")
        boolean effectsEnabled,

        @In("appearance/effects") @Title("Repeat gap in milliseconds") @Range(min = 0, max = 5000)
        @Describe("How close together the same cue counts as a repeat. A plugin playing one every "
                + "tick would otherwise deafen somebody. 0 switches the suppression off.")
        int effectsRepeatGapMillis,

        @In("appearance/effects") @Title("Teleport: particles while waiting") @Range(min = 1, max = 20)
        @Describe("How many are drawn at a time around somebody standing still for a teleport — the "
                + "server's portal swirl and a player's own choice alike.")
        int teleportWaitParticles,

        @In("appearance/effects") @Title("Teleport: particles where somebody lands") @Range(min = 0, max = 300)
        @Describe("The burst at the spot a teleport arrives. 0 for none; the sound stays either way.")
        int teleportArrivalParticles,

        @In("config/safety") @Title("Refuse to arrive underwater")
        @Describe("Whether a teleport to a spot underwater is treated as unsafe. Off for a server "
                + "with warps deliberately placed in an ocean.")
        boolean safetyRefuseWater,

        @In("config/safety") @Title("Check the blocks around it") @Range(min = 0, max = 5)
        @Describe("How far around a spot to look for lava or fire before calling it safe. 0 checks "
                + "only the spot itself.")
        int safetySurroundingRadius,

        @In("config/safety") @Title("How far to search for somewhere safe") @Range(min = 0, max = 64)
        @Describe("How far a teleport will look sideways for a safe spot before giving up. Larger "
                + "means more of the world is loaded to answer.")
        int safetySearchRadius,

        @In("moderation/chatlog") @Title("Keep a log of chat")
        @Describe("Every public and channel line, per player, for staff to read back and search (/chatlog). "
                + "Private messages are not kept. Players are told on their first join.")
        boolean chatLogEnabled,

        @In("moderation/chatlog") @Title("Keep the chat log for this many days")
        @Range(min = 1, max = 730)
        @Describe("Older lines are deleted. What people said, beside their names, is personal data: under the "
                + "GDPR it may be kept while there is a reason — moderating — and no longer, so there is no "
                + "'for ever'. Say how long in your privacy notice.")
        int chatLogRetentionDays,

        @In("world/desks") @Title("Insurance only at an insurance agent")
        @Describe("Players take out and change every insurance — bets, death, items — by talking to an "
                + "insurance agent standing in the world, not by command. Staff place agents with "
                + "/eco agent place or /insurance agent place.")
        boolean insuranceInPersonOnly,

        @In("world/desks") @Title("The bank only at a banker")
        @Describe("Players open the bank by talking to a banker standing in the world, not with /bank. Staff "
                + "place bankers with /eco banker place.")
        boolean bankInPersonOnly,

        @In("world/desks") @Title("Insurance agents' skins")
        @Describe("A player name, a MineSkin link (mineskin.org) or mineskin:<id>; several, comma-separated, are "
                + "shared out between the agents. Empty: the black suit. Agents already standing keep theirs "
                + "until they are placed again.")
        String insuranceAgentSkins,

        @In("world/desks") @Title("Bankers' skins")
        @Describe("Like the insurance agents' skins.")
        String bankerSkins,

        @In("appearance") @Title("Server name")
        @Describe("What this server is called wherever a plugin names it: the tablist header and logo, "
                + "the hub, and the other servers of a network. Plain text. Empty: the MOTD's first line, "
                + "and server-name in link.yml towards the network.")
        String serverName,

        @In("appearance/tablist") @Title("Server performance")
        @Describe("A line under the list with the ticks per second, how long a tick takes and the memory in "
                + "use — green while all is well, yellow when it strains, red when it lags.")
        boolean tablistShowPerformance,

        @In("config/chat") @Title("Command for the panel")
        @Describe("What players type to open the features panel, as hints name it. The panel answers to /hub and /smp; "
                + "on a network whose proxy takes /hub to send people to a hub server, set smp.")
        String hubCommand,

        @In("appearance") @Title("Painted menus")
        @Describe("Menus drawn as wood, brass and parchment for players wearing the server pack, instead of glass "
                + "panes. Off: every menu is the classic window for everybody. Players can also switch them off for "
                + "themselves on their profile.")
        boolean menusPainted

) {

    /** The named looks, kept as an enum so the setting completes and cycles on its own. */
    public enum Theme { DEFAULT, MIDNIGHT, EMBER, FOREST, FROST, MONO }

    public int chatLogRetention() {
        return Math.max(1, Math.min(730, chatLogRetentionDays));
    }

    /**
     * What Rain's Core ships with.
     *
     * <p>Every colour is empty on purpose: empty means "whatever the theme says", which is what
     * makes both halves useful at once. A server owner picks a theme and is done; the one who wants
     * that theme with a different lore colour fills in one line and leaves the other ten alone.
     */
    public static final CoreConfig DEFAULTS = new CoreConfig(
            Theme.DEFAULT,
            "", "", "", "", "", "", "", "", "", "",
            "▸",
            de.raindancer.core.ui.messages.Messages.Tone.PLAYFUL,
            LogLevel.INFO, LogLevel.INFO, 14,
            15,
            true, true, false, "", "", "", "", false, true, "", "", 4, 40,
            true, true, true, true, "",
            true, false, true, false, "Server pack", true, "0.0.0.0", 8123, "",
            true, true, true, true,
            true, true, false, true,
            // Recording on, kept for ninety days: long enough to investigate something reported
            // weeks late, short enough not to be a standing archive of everybody's behaviour.
            true,
            // Nothing about combat switched off: a library that changes how the game plays the
            // moment it is installed has broken somebody's server, and they will not know which
            // plugin did it.
            true, true, true,
            90,
            true, 120,
            de.raindancer.core.world.teleport.TravelShow.DEFAULT_WAIT_DENSITY,
            de.raindancer.core.world.teleport.TravelShow.DEFAULT_ARRIVAL_DENSITY,
            true, 1, 8,
            // A year: long enough to look into something reported months late, and a limit — never for ever.
            true, 365,
            false,
            false, "", "",
            "",
            true,
            "hub",
            true);
}
