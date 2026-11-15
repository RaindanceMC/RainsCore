package de.raindancer.core.social.team;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A roster of named, coloured teams, held to one {@link TeamPolicy} at a time.
 *
 * <h2>The single source of truth for who is on which team</h2>
 * Every route in — a plugin's own API, an admin command, a player's menu click, random assignment — goes
 * through one instance of this class, and all of them are held to the same policy. Two paths that could
 * each independently decide "is this colour free" are two paths that can disagree about it, and a colour
 * clash is exactly the failure this class exists to make impossible: see {@link #create} and
 * {@link #setColour}, both of which refuse a taken colour outright rather than picking a different one on
 * the caller's behalf.
 *
 * <h2>Why this moved out of one Hunger Games module and into Core</h2>
 * A tournament's teams, a bedwars match's teams, a clan and a party had each been written as their own
 * copy of the same hard parts — membership, exclusive colours, captains, random assignment, the outcome
 * vocabulary for a refusal — with a handful of numbers and booleans as the only real difference. Written
 * separately, each copy got one of the hard parts subtly wrong: the copy where a colour clash silently
 * reassigns, the copy where two clicks in one tick both pass the size check, the copy where a rename loses
 * the members. This class owns the hard parts once; {@link TeamPolicy} carries what differs.
 *
 * <h2>The three things this class is handed, and why each is what it is</h2>
 * <ul>
 * <li>{@code policy} is a supplier rather than a value, because a reload can swap it out from under a live
 * roster — an owner raising the team-size cap because fewer people showed up than expected — and every
 * team operation has to see the current rules, not whichever ones were current when the roster was
 * built.</li>
 * <li>{@code frozen} is a fact about the moment, not a rule, and is asked for separately from the policy on
 * purpose. A tournament's teams stop being editable when a round starts and a bedwars match's the moment
 * the game begins, and neither is a number or a boolean that belongs in {@link TeamPolicy} — it is a
 * decision the owning plugin already has to make for its own reasons, and repeating it here as a second
 * boolean would be a second place for the two to disagree.</li>
 * <li>{@code isEligible} is the host's business and this class never knows why: a whitelisted tribute, a
 * player in the match, somebody not already banned from clans. This class only asks the question and acts
 * on the answer.</li>
 * </ul>
 */
public final class Teams {
    // Thread safety: every public method is synchronized on this. Commands, joins and sweeps reach the
    // roster from different region threads on Folia, and it is plain collections underneath.

    private static final class MutableTeam {
        String name;
        TeamColour colour;
        /** The character shown before the name. NONE until a caller sets one — see TeamEmblem. */
        TeamEmblem emblem = TeamEmblem.NONE;
        /** The item the team is drawn as, once its members have chosen one out of the item chooser. */
        Material badge;
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        UUID captain;

        MutableTeam(String name, TeamColour colour) {
            this.name = name;
            this.colour = colour;
        }
    }

    /** The result of creating a team; {@code team} is present on success. */
    public record CreationResult(TeamOutcome status, Optional<Team> team) {

        static CreationResult failure(TeamOutcome status) {
            return new CreationResult(status, Optional.empty());
        }
    }

    /** The result of a membership change, together with the team it moved out of — for events. */
    public record MembershipChange(TeamOutcome status, Optional<TeamId> oldTeam) {

        static MembershipChange failure(TeamOutcome status) {
            return new MembershipChange(status, Optional.empty());
        }
    }

    private final Map<TeamId, MutableTeam> teams = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final Supplier<TeamPolicy> policy;
    private final BooleanSupplier frozen;
    private final Predicate<UUID> isEligible;

    /**
     * @param policy     the rules currently in force, read fresh on every access — see the class note
     * @param frozen     whether teams may be changed at all right now — a fact about the moment, not a rule
     * @param isEligible whether a player may be on a team at all here; the host's own business
     */
    public Teams(Supplier<TeamPolicy> policy, BooleanSupplier frozen, Predicate<UUID> isEligible) {
        this.policy = policy;
        this.frozen = frozen;
        this.isEligible = isEligible;
    }

    // ==================== CRUD ====================

    /**
     * Creates a team. {@code colour == null} auto-picks the first free colour.
     *
     * <p>Does not consult {@link TeamPolicy#playersMayCreate()}. That answers "is creating a team a thing
     * players do here at all", which is a question about who is asking, and this method is never told who
     * is asking — only that a team should be made. Checking it here would be the one place a permission
     * check silently applied to staff-driven creation too, which is exactly the caller's job to avoid: the
     * command or menu that reads {@code playersMayCreate} decides whether to call this at all.
     */
    public synchronized CreationResult create(String name, TeamColour colour) {
        if (locked()) {
            return CreationResult.failure(TeamOutcome.FROZEN);
        }
        TeamId id = TeamId.fromName(name);
        if (teams.containsKey(id) || nameTaken(name, null)) {
            return CreationResult.failure(TeamOutcome.NAME_TAKEN);
        }
        TeamPolicy p = policy.get();
        if (!p.allowsAnotherTeam(teams.size())) {
            return CreationResult.failure(TeamOutcome.TOO_MANY_TEAMS);
        }

        TeamColour chosen = colour;
        if (chosen == null) {
            Set<TeamColour> free = availableColours();
            if (free.isEmpty()) {
                // Only a hard refusal where colours are exclusive: two teams sharing a colour is the
                // ordinary case everywhere else, so running out of unused ones is not a problem there.
                if (p.exclusiveColours()) {
                    return CreationResult.failure(TeamOutcome.NO_COLOUR_FREE);
                }
                chosen = TeamColour.values()[0];
            } else {
                chosen = free.iterator().next();
            }
        } else if (p.exclusiveColours() && identityTaken(chosen, TeamEmblem.NONE, null)) {
            // Checked against the pair, not the colour alone: a new team always starts plain (no emblem
            // has been chosen for it yet), so what would actually clash is another team also standing on
            // this colour with no emblem either. A team that got here after claiming an emblem is not what
            // this refuses — see setColour and setEmblem, which check the same pair for the same reason.
            return CreationResult.failure(TeamOutcome.COLOUR_TAKEN);
        }

        teams.put(id, new MutableTeam(name.trim(), chosen));
        return new CreationResult(TeamOutcome.SUCCESS, Optional.of(snapshot(id)));
    }

    /**
     * Deletes a team.
     *
     * @return a snapshot of the deleted team (for an event), or empty if there was no such team
     */
    public synchronized Optional<Team> delete(TeamId id) {
        if (locked() || !teams.containsKey(id)) {
            return Optional.empty();
        }
        Team snapshot = snapshot(id);
        teams.remove(id);
        return Optional.of(snapshot);
    }

    public synchronized TeamOutcome rename(TeamId id, String newName) {
        MutableTeam team = teams.get(id);
        if (team == null) {
            return TeamOutcome.NO_SUCH_TEAM;
        }
        if (locked()) {
            return TeamOutcome.FROZEN;
        }
        if (nameTaken(newName, id)) {
            return TeamOutcome.NAME_TAKEN;
        }
        team.name = newName.trim();
        return TeamOutcome.SUCCESS;
    }

    // ==================== colours ====================

    /**
     * Assigns a colour to a team. A colour already claimed by another team wearing the same emblem is
     * refused outright — never silently reassigned, which is the exclusivity invariant this class exists
     * to keep — but only when {@link TeamPolicy#exclusiveColours()} is on; where it is off, colours are
     * never checked at all.
     *
     * <p>Checked against the {@link Team.Identity} pair, not the colour alone: once a team has claimed an
     * emblem, another team standing on its colour is not the clash this refuses — telling them apart is
     * exactly what the emblem is for. See {@link TeamEmblem} for why that raises the ceiling past sixteen
     * teams rather than only ever refusing the seventeenth.
     */
    public synchronized TeamOutcome setColour(TeamId id, TeamColour colour) {
        MutableTeam team = teams.get(id);
        if (team == null) {
            return TeamOutcome.NO_SUCH_TEAM;
        }
        if (team.colour == colour) {
            return TeamOutcome.SUCCESS;
        }
        if (locked()) {
            return TeamOutcome.FROZEN;
        }
        if (policy.get().exclusiveColours() && identityTaken(colour, team.emblem, id)) {
            return TeamOutcome.COLOUR_TAKEN;
        }
        team.colour = colour;
        return TeamOutcome.SUCCESS;
    }

    /**
     * Assigns an emblem to a team.
     *
     * <h2>Why this reuses {@link TeamOutcome#COLOUR_TAKEN}</h2>
     * A clash here is the same clash {@link #setColour} refuses: two teams that could not be told apart.
     * Once emblems are in play, "told apart" means the colour-and-emblem pair rather than the colour alone
     * — see {@link Team.Identity} — so a taken pair is refused with the same outcome a taken colour would
     * be, rather than a second refusal vocabulary standing for the same idea.
     */
    public synchronized TeamOutcome setEmblem(TeamId id, TeamEmblem emblem) {
        MutableTeam team = teams.get(id);
        if (team == null) {
            return TeamOutcome.NO_SUCH_TEAM;
        }
        TeamEmblem wanted = emblem == null ? TeamEmblem.NONE : emblem;
        if (team.emblem == wanted) {
            return TeamOutcome.SUCCESS;
        }
        if (locked()) {
            return TeamOutcome.FROZEN;
        }
        if (policy.get().exclusiveColours() && identityTaken(team.colour, wanted, id)) {
            return TeamOutcome.COLOUR_TAKEN;
        }
        team.emblem = wanted;
        return TeamOutcome.SUCCESS;
    }

    /**
     * Sets the item a team is drawn as.
     *
     * <h2>Why this never refuses a duplicate</h2>
     * A badge is decoration a team's own members chose out of the item chooser, not part of its identity —
     * {@link Team.Identity} is colour and emblem only, deliberately, see {@link Team#badge()}. Two teams
     * both fancying a diamond block is not the collision this class exists to prevent, and refusing it here
     * would only stop members holding the item they actually picked.
     */
    public synchronized TeamOutcome setBadge(TeamId id, Material badge) {
        MutableTeam team = teams.get(id);
        if (team == null) {
            return TeamOutcome.NO_SUCH_TEAM;
        }
        if (locked()) {
            return TeamOutcome.FROZEN;
        }
        team.badge = badge;
        return TeamOutcome.SUCCESS;
    }

    /**
     * The colours no team currently holds.
     *
     * <p>Computed the same way regardless of {@link TeamPolicy#exclusiveColours()} — it does not stop
     * shrinking as teams are founded just because colours are shared. Where colours are exclusive this is
     * the constraint {@link #create} and {@link #setColour} enforce, and {@link TeamOutcome#NO_COLOUR_FREE}
     * is a real refusal once it is empty. Where colours are shared it is only advice: {@link #create} still
     * reads it to spread new teams across the palette rather than founding every clan white, but nothing
     * refuses a colour this method calls taken, and an empty answer there falls back to the first colour
     * rather than a refusal.
     */
    public synchronized Set<TeamColour> availableColours() {
        EnumSet<TeamColour> free = EnumSet.allOf(TeamColour.class);
        for (MutableTeam team : teams.values()) {
            free.remove(team.colour);
        }
        return free;
    }

    // ==================== membership ====================

    /** Puts a player on a team, moving them out of their old one first if they had one. */
    public synchronized MembershipChange join(UUID player, TeamId id) {
        if (!isEligible.test(player)) {
            return MembershipChange.failure(TeamOutcome.NOT_ELIGIBLE);
        }
        MutableTeam team = teams.get(id);
        if (team == null) {
            return MembershipChange.failure(TeamOutcome.NO_SUCH_TEAM);
        }
        if (team.members.contains(player)) {
            return MembershipChange.failure(TeamOutcome.ALREADY_IN_THAT_TEAM);
        }
        if (locked()) {
            return MembershipChange.failure(TeamOutcome.FROZEN);
        }

        Optional<TeamId> oldTeam = teamIdOf(player);
        TeamPolicy p = policy.get();
        if (oldTeam.isPresent() && !p.allowSwitching()) {
            return MembershipChange.failure(TeamOutcome.MUST_LEAVE_FIRST);
        }
        if (!p.hasRoomFor(team.members.size())) {
            return MembershipChange.failure(TeamOutcome.TEAM_FULL);
        }

        oldTeam.ifPresent(old -> removeMember(old, player));
        team.members.add(player);
        return new MembershipChange(TeamOutcome.SUCCESS, oldTeam);
    }

    /**
     * Puts a player on a team as a host decides it — refused only while teams are frozen, or for a
     * team that does not exist. Switching, size and eligibility rules are the player's limits, not the
     * host's. Moves them out of any old team first, so they are still on exactly one.
     */
    public synchronized MembershipChange place(UUID player, TeamId id) {
        MutableTeam team = teams.get(id);
        if (player == null || team == null) {
            return MembershipChange.failure(TeamOutcome.NO_SUCH_TEAM);
        }
        if (locked()) {
            return MembershipChange.failure(TeamOutcome.FROZEN);
        }
        if (team.members.contains(player)) {
            return MembershipChange.failure(TeamOutcome.ALREADY_IN_THAT_TEAM);
        }
        Optional<TeamId> oldTeam = teamIdOf(player);
        oldTeam.ifPresent(old -> removeMember(old, player));
        team.members.add(player);
        return new MembershipChange(TeamOutcome.SUCCESS, oldTeam);
    }

    /** Takes a player off their team. */
    public synchronized MembershipChange leave(UUID player) {
        Optional<TeamId> oldTeam = teamIdOf(player);
        if (oldTeam.isEmpty()) {
            return MembershipChange.failure(TeamOutcome.NOT_IN_A_TEAM);
        }
        if (locked()) {
            return MembershipChange.failure(TeamOutcome.FROZEN);
        }
        removeMember(oldTeam.get(), player);
        return new MembershipChange(TeamOutcome.SUCCESS, oldTeam);
    }

    /**
     * Takes a player off their team regardless of {@code frozen} or the policy.
     *
     * <p>For a host withdrawing somebody's eligibility outright — a whitelist entry pulled, a ban applied —
     * which has to work even mid-round, when every other mutation is refused. The one route intentionally
     * left open through a locked roster.
     */
    public synchronized Optional<TeamId> forceRemove(UUID player) {
        Optional<TeamId> oldTeam = teamIdOf(player);
        oldTeam.ifPresent(old -> removeMember(old, player));
        return oldTeam;
    }

    /**
     * Swaps one UUID for another inside whichever team {@code from} is on, regardless of {@code frozen}.
     *
     * <p>For the one case that is not a membership decision at all: a roster host who whitelisted somebody
     * by name before they had ever joined, keyed on a placeholder UUID derived from that name, and now
     * knows their real one. That is not "leave and rejoin" — a normal join would be refused the moment
     * teams are frozen, which for a tournament is most of the evening, and the person did not change
     * teams, only which UUID means them. Captaincy moves with them, because losing it by an accident of
     * timing (whitelisted before the real UUID was known) is not a decision anybody made.
     *
     * @return the team it moved within, or empty if {@code from} was not on one
     */
    public synchronized Optional<TeamId> reassign(UUID from, UUID to) {
        Optional<TeamId> current = teamIdOf(from);
        if (current.isPresent() && !from.equals(to)) {
            // Somebody is on one team: a stale membership of the real UUID elsewhere goes.
            teamIdOf(to).filter(other -> !other.equals(current.get()))
                    .ifPresent(other -> removeMember(other, to));
        }
        current.ifPresent(id -> {
            MutableTeam team = teams.get(id);
            team.members.remove(from);
            team.members.add(to);
            if (from.equals(team.captain)) {
                team.captain = to;
            }
        });
        return current;
    }

    public synchronized TeamOutcome setCaptain(TeamId id, UUID player) {
        MutableTeam team = teams.get(id);
        if (team == null) {
            return TeamOutcome.NO_SUCH_TEAM;
        }
        if (!policy.get().captains()) {
            return TeamOutcome.NO_CAPTAINS_HERE;
        }
        if (!team.members.contains(player)) {
            return TeamOutcome.CAPTAIN_NOT_A_MEMBER;
        }
        team.captain = player;
        return TeamOutcome.SUCCESS;
    }

    public synchronized TeamOutcome clearCaptain(TeamId id) {
        MutableTeam team = teams.get(id);
        if (team == null) {
            return TeamOutcome.NO_SUCH_TEAM;
        }
        if (!policy.get().captains()) {
            return TeamOutcome.NO_CAPTAINS_HERE;
        }
        team.captain = null;
        return TeamOutcome.SUCCESS;
    }

    /**
     * Randomly distributes teamless candidates: fills existing teams first (smallest ones first), creates
     * new teams as the policy allows, for as long as there is room and a free colour to give a new one.
     *
     * @return the players assigned, with their new team
     */
    public synchronized Map<UUID, TeamId> assignRandomly(Collection<UUID> candidates, Random random) {
        Map<UUID, TeamId> assigned = new LinkedHashMap<>();
        if (locked()) {
            return assigned;
        }

        List<UUID> unassigned = new ArrayList<>();
        for (UUID candidate : candidates) {
            if (isEligible.test(candidate) && teamIdOf(candidate).isEmpty()) {
                unassigned.add(candidate);
            }
        }
        Collections.shuffle(unassigned, random);

        TeamPolicy p = policy.get();
        int autoTeamCounter = teams.size();

        for (UUID player : unassigned) {
            TeamId target = smallestOpenTeam(p);
            if (target == null) {
                if (!p.allowsAnotherTeam(teams.size())) {
                    break;
                }
                CreationResult created = create("Team " + (++autoTeamCounter), null);
                while (created.status() == TeamOutcome.NAME_TAKEN) {
                    created = create("Team " + (++autoTeamCounter), null);
                }
                if (!created.status().isSuccess()) {
                    break; // no colours or teams left
                }
                target = created.team().orElseThrow().id();
            }
            teams.get(target).members.add(player);
            assigned.put(player, target);
        }
        return assigned;
    }

    // ==================== queries ====================

    public synchronized Optional<TeamId> teamIdOf(UUID player) {
        for (Map.Entry<TeamId, MutableTeam> entry : teams.entrySet()) {
            if (entry.getValue().members.contains(player)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Team> team(TeamId id) {
        return teams.containsKey(id) ? Optional.of(snapshot(id)) : Optional.empty();
    }

    public synchronized Optional<Team> teamOf(UUID player) {
        return teamIdOf(player).map(this::snapshot);
    }

    /** Snapshots of every team, in creation order. */
    public synchronized List<Team> all() {
        return teams.keySet().stream().map(this::snapshot).toList();
    }

    public synchronized int count() {
        return teams.size();
    }

    public synchronized void clear() {
        teams.clear();
    }

    /**
     * Every team, as it is right now — for the caller to persist however it likes.
     *
     * <p>This class does not choose storage. A {@code YamlStore}, a database row, an in-memory session
     * snapshot — all of them are just "a list of {@link Team}", and {@link #restore} takes exactly that
     * back.
     */
    public synchronized List<Team> snapshot() {
        return all();
    }

    /**
     * Replaces the whole roster with the given teams (a session or file restore).
     *
     * <p>Bypasses every rule in this class deliberately — a saved roster made under yesterday's policy has
     * to load under today's without being refused for a team size or a colour that used to be fine — but
     * two invariants matter enough that this is not a plain copy either. Every writing method in this class
     * refuses a colour clash and a captain who is not a member outright, which makes {@code restore} the
     * only door either could ever get in by: a hand-edited file, an older save format, or a snapshot taken
     * while {@link TeamPolicy#exclusiveColours()} was off and then switched on.
     *
     * <ul>
     * <li>Where colours are exclusive and an incoming team's colour and emblem are already held by one loaded
     * earlier from the same collection, it is given a free colour instead of the one it asked for. Where none is
     * free, the clash is left in place rather than the team being dropped — two teams that are hard to tell
     * apart is a smaller failure than a team, and its members, disappearing.</li>
     * <li>Where a captain is not among the team's own members, the captaincy is dropped and the team is
     * kept.</li>
     * </ul>
     *
     * <p>Never throws over a bad row — refusing to load would take a whole session down for one stale
     * field — and never silently corrects one either: every correction is appended to {@link #problems()}
     * for whatever owns the file to log once at startup.
     */
    public synchronized void restore(Collection<Team> saved) {
        teams.clear();
        problems.clear();
        TeamPolicy p = policy.get();
        for (Team data : saved) {
            TeamColour colour = data.colour();
            // The pair, as create and setColour judge it: red with an emblem is not plain red.
            if (p.exclusiveColours() && identityTaken(colour, data.emblem(), null)) {
                Set<TeamColour> free = availableColours();
                if (free.isEmpty()) {
                    problems.add("team '" + data.id() + "' loaded with colour " + colour
                            + ", already held by another loaded team, and no colour was free to give it "
                            + "instead. Kept the clash");
                } else {
                    TeamColour reassigned = free.iterator().next();
                    problems.add("team '" + data.id() + "' loaded with colour " + colour
                            + ", already held by another loaded team. Reassigned to " + reassigned);
                    colour = reassigned;
                }
            }

            MutableTeam team = new MutableTeam(data.name(), colour);
            team.emblem = data.emblem();
            // Only a badge somebody chose: Team fills in a fallback for one that was never chosen, and
            // keeping that as a choice would stop it following a later emblem or colour change.
            Material fallback = new Team(data.id(), data.name(), data.colour(), data.emblem(), null,
                    Set.of(), Optional.empty()).badge();
            team.badge = data.badge() == fallback ? null : data.badge();
            team.members.addAll(data.members());
            UUID captain = data.captain().orElse(null);
            if (captain != null && !team.members.contains(captain)) {
                problems.add("team '" + data.id() + "' loaded with a captain who is not one of its "
                        + "members. Captaincy dropped");
                captain = null;
            }
            team.captain = captain;
            teams.put(data.id(), team);
        }
    }

    /**
     * What {@link #restore} had to correct in the roster it was handed, in the order found.
     *
     * <p>Empty on an ordinary restore. Anything here is evidence the file did not agree with this class's
     * own invariants — a colour clash, a captain who was not a member — and was fixed rather than rejected.
     * The caller that owns the file is the one place that knows how to tell somebody, so this only reports;
     * it never logs on its own.
     */
    public synchronized List<String> problems() {
        return List.copyOf(problems);
    }

    // ==================== internal ====================

    private Team snapshot(TeamId id) {
        MutableTeam team = teams.get(id);
        return new Team(id, team.name, team.colour, team.emblem, team.badge, Set.copyOf(team.members),
                Optional.ofNullable(team.captain));
    }

    /**
     * Whether teams may be changed at all right now.
     *
     * <p>Public for two reasons, both of which came from a real defect rather than from tidiness.
     *
     * <p>A screen needs it to grey a button. Without it a menu has to either offer a button that will refuse
     * the click, or ask the host the same question a second way — and a second answer to "are teams editable"
     * is one that disagrees with this one on exactly the tick a round starts.
     *
     * <p>And a caller needs it to tell two refusals apart. {@link #delete} answers with an
     * {@code Optional<Team>} rather than an outcome, so "there is no team by that id" and "teams are frozen"
     * arrive as the same empty answer. A gamemaster told "there is no team called red" about a team they are
     * looking at goes hunting for a bug in the roster; told "teams are locked now", they know they have to end
     * the round first. The caller checks this first and says which it was.
     */
    public synchronized boolean isFrozen() {
        return frozen.getAsBoolean();
    }

    private boolean locked() {
        return isFrozen();
    }

    private boolean nameTaken(String name, TeamId exclude) {
        String normalised = name.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<TeamId, MutableTeam> entry : teams.entrySet()) {
            if (!entry.getKey().equals(exclude)
                    && entry.getValue().name.toLowerCase(Locale.ROOT).equals(normalised)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether some other team already wears this exact colour <em>and</em> emblem.
     *
     * <p>The pair, not the colour alone, and that is the whole reason emblems exist. Sixteen colours means a
     * seventeenth team cannot be founded where colours are exclusive; sixteen colours and fifteen emblems
     * means two hundred and forty identities that are still told apart at a glance, because a red diamond and
     * a red star are not the same badge. Checking the colour on its own would throw that away and leave the
     * emblem as decoration.
     */
    private boolean identityTaken(TeamColour colour, TeamEmblem emblem, TeamId exclude) {
        for (Map.Entry<TeamId, MutableTeam> entry : teams.entrySet()) {
            if (entry.getKey().equals(exclude)) {
                continue;
            }
            MutableTeam other = entry.getValue();
            if (other.colour == colour && other.emblem == emblem) {
                return true;
            }
        }
        return false;
    }

    private void removeMember(TeamId id, UUID player) {
        MutableTeam team = teams.get(id);
        if (team != null) {
            team.members.remove(player);
            if (player.equals(team.captain)) {
                team.captain = null;
            }
        }
    }

    private TeamId smallestOpenTeam(TeamPolicy p) {
        TeamId best = null;
        int bestSize = Integer.MAX_VALUE;
        for (Map.Entry<TeamId, MutableTeam> entry : teams.entrySet()) {
            int size = entry.getValue().members.size();
            boolean open = p.hasRoomFor(size);
            if (open && size < bestSize) {
                best = entry.getKey();
                bestSize = size;
            }
        }
        return best;
    }
}
