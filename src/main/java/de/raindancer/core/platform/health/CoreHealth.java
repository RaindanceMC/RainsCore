package de.raindancer.core.platform.health;

import de.raindancer.core.ui.checklist.Checklist;
import de.raindancer.core.ui.checklist.Checklist.Check;

import java.util.List;

/**
 * Whether Rain's Core itself is in order on this server — what an owner is shown when something is
 * not, with what to do about it in each line.
 *
 * <p>Built from {@link Facts} rather than read from the server, so every sentence an owner can be shown
 * is checked by a test.
 */
public final class CoreHealth {

    private CoreHealth() {
    }

    /** What is true about this server right now. */
    public record Facts(boolean coreDatabase, boolean auditDatabase, List<String> configProblems,
                        List<String> messageProblems, boolean sidebars, boolean packServerRunning,
                        boolean packAddressReachable, boolean luckPerms, boolean folia) {

        public Facts {
            configProblems = configProblems == null ? List.of() : List.copyOf(configProblems);
            messageProblems = messageProblems == null ? List.of() : List.copyOf(messageProblems);
        }
    }

    public static Checklist checklist(Facts facts) {
        return Checklist.titled("Rain's Core")
                .check(Check.of("database", "Homes, warps, bans and items are being saved", facts.coreDatabase())
                        .in("Saving")
                        .because(facts.coreDatabase() ? "core.db is open."
                                : "core.db could not be opened. See the server log. Changes are kept until "
                                        + "restart but not saved; nothing on disk is touched."))
                .check(Check.of("audit", "What moderators do is being recorded", facts.auditDatabase())
                        .in("Saving")
                        .because(facts.auditDatabase() ? "audit.db is open."
                                : "audit.db could not be opened. See the server log."))
                .check(Check.of("config", "config.yml reads cleanly", facts.configProblems().isEmpty())
                        .in("Files")
                        .because(facts.configProblems().isEmpty() ? "Every setting is as written."
                                : first(facts.configProblems()) + ". Fix it in plugins/RainsCore/config.yml, "
                                        + "or change it with /settings."))
                .check(Check.warning("messages", "messages.yml reads cleanly", facts.messageProblems().isEmpty())
                        .in("Files")
                        .because(facts.messageProblems().isEmpty() ? "Every message is defined."
                                : first(facts.messageProblems()) + ". The built-in wording is used meanwhile."))
                .check(Check.warning("sidebars", "Sidebars can be drawn", facts.sidebars())
                        .in("Display")
                        .because(facts.sidebars() ? "The scoreboard code recognises this server."
                                : "This server's internals are newer than Core's scoreboard code; sidebars "
                                        + "are off until Core is updated."))
                .check(Check.warning("packs", "Players can download the resource pack",
                                !facts.packServerRunning() || facts.packAddressReachable())
                        .in("Display")
                        .because(!facts.packServerRunning() ? "The built-in pack server is off."
                                : facts.packAddressReachable() ? "Players are sent an address they can reach."
                                : "Players would be told to download from an address only this machine can "
                                        + "reach. Set packs-public-address in /settings."))
                .check(Check.warning("permissions", "Permissions are kept somewhere that lasts", true)
                        .in("Server")
                        .because(facts.luckPerms() ? "Through LuckPerms." : "In Core's own grants.yml. "
                                + "install LuckPerms for groups and inheritance."))
                .check(Check.warning("scheduler", "Scheduling matches the server", true)
                        .in("Server")
                        .because(facts.folia() ? "Folia: work runs on the region that owns it." : "Paper."));
    }

    private static String first(List<String> problems) {
        String one = problems.getFirst();
        return problems.size() == 1 ? one : one + " (and " + (problems.size() - 1) + " more)";
    }
}
