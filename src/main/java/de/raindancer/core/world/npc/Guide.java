package de.raindancer.core.world.npc;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.companion.Pathing;
import de.raindancer.core.world.companion.Terrains;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.TeleportFlag;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * A guide for one player: a person (a mannequin in a skin) only they can see, who walks ahead of them to where they
 * should go — around walls and up steps by {@link Pathing}, waiting when they fall behind — and talks to them in a
 * speech bubble above its head. A tutorial's host, a quest giver showing the way.
 *
 * <p>Walked a step a tick (the client animates the legs from the steps), a leg of the way at a time: a way is
 * searched only some 30 blocks ahead, so a longer one is walked leg by leg. Truly stuck, it steps to the goal.
 * Not saved with the chunk: {@link #remove} when done, and gone with a restart anyway.
 */
public final class Guide {

    private static final double SPEED = 0.2;
    private static final double WAIT_BEYOND = 7;
    private static final int MAX_LEGS = 24;
    private static final int BUDGET = 4000;

    private final Plugin plugin;
    private final Player viewer;
    private final Mannequin body;
    private final TextDisplay bubble;
    private final ScheduledTask task;
    private GuideWalk walk;
    private Location goal;
    private Runnable arrived;
    private int legs;
    private int bubbleTicks;
    private int ticks;

    private Guide(Plugin plugin, Player viewer, Mannequin body, TextDisplay bubble) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.body = body;
        this.bubble = bubble;
        this.task = Scheduling.entityTimer(plugin, body, 1, 1, ignored -> tick(), () -> {
        });
    }

    /** A guide standing at {@code at}, seen by {@code viewer} alone. */
    public static Guide spawn(Plugin plugin, Player viewer, Location at, ResolvableProfile look, Component name) {
        World world = at.getWorld();
        Mannequin body = world.spawn(at, Mannequin.class, m -> {
            m.setPersistent(false);
            m.setVisibleByDefault(false);
            m.setDescription(null);
            m.customName(name);
            m.setCustomNameVisible(true);
            m.setImmovable(true);
            m.setGravity(false);
            m.setInvulnerable(true);
            m.setCollidable(false);
        });
        // After the spawn: a stand-in's skin is fetched and swapped in by the body's id.
        Skins.wear(body, look);
        TextDisplay bubble = world.spawn(at, TextDisplay.class, t -> {
            t.setPersistent(false);
            t.setVisibleByDefault(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setLineWidth(220);
            t.setBackgroundColor(Color.fromARGB(150, 15, 30, 61));
            t.setTransformation(new Transformation(new Vector3f(0, 0.55f, 0), new AxisAngle4f(), new Vector3f(1, 1, 1),
                    new AxisAngle4f()));
            t.text(Component.empty());
        });
        body.addPassenger(bubble);
        viewer.showEntity(plugin, body);
        viewer.showEntity(plugin, bubble);
        return new Guide(plugin, viewer, body, bubble);
    }

    public Mannequin body() {
        return body;
    }

    public boolean walking() {
        return walk != null;
    }

    /** Walks there, leading the viewer; {@code arrived} runs on the guide's thread once it stands at the goal. */
    public void walkTo(Location target, Runnable arrived) {
        this.goal = target.clone();
        this.arrived = arrived;
        this.legs = 0;
        plan();
    }

    /** Says it in the bubble over its head for {@code seconds}. */
    public void say(Component text, int seconds) {
        bubble.text(text);
        bubbleTicks = seconds * 20;
    }

    public void remove() {
        task.cancel();
        Scheduling.entity(plugin, bubble, bubble::remove);
        Scheduling.entity(plugin, body, body::remove);
    }

    private void plan() {
        Location from = body.getLocation();
        if (goal == null || goal.getWorld() != from.getWorld()) {
            walk = null;
            return;
        }
        Pathing.Route route = Pathing.walk(Terrains.of(from.getWorld()), cell(from), cell(goal), 2, 1.2, BUDGET);
        List<GuideWalk.Point> way = new ArrayList<>();
        way.add(new GuideWalk.Point(from.getX(), from.getY(), from.getZ()));
        for (Pathing.Cell cell : route.cells()) {
            way.add(new GuideWalk.Point(cell.x() + 0.5, cell.y(), cell.z() + 0.5));
        }
        if (route.complete()) {
            way.add(new GuideWalk.Point(goal.getX(), goal.getY(), goal.getZ()));
        }
        legs++;
        if (way.size() < 2 && !route.complete() || legs > MAX_LEGS) {
            // Nowhere to go from here: rather at the goal than standing stuck in front of a wall.
            way = List.of(way.getFirst(), new GuideWalk.Point(goal.getX(), goal.getY(), goal.getZ()));
            legs = MAX_LEGS + 1;
            move(goal);
        }
        walk = new GuideWalk(way, SPEED, WAIT_BEYOND);
    }

    private void tick() {
        ticks++;
        if (bubbleTicks > 0 && --bubbleTicks == 0) {
            bubble.text(Component.empty());
        }
        if (!viewer.isOnline()) {
            return;
        }
        if (walk == null) {
            if (ticks % 5 == 0) {
                face(viewer.getLocation());
            }
            return;
        }
        Location here = body.getLocation();
        double behind = viewer.getWorld() == here.getWorld() ? viewer.getLocation().distance(here) : Double.MAX_VALUE;
        GuideWalk.Point to = walk.step(behind);
        if (!walk.waiting()) {
            move(new Location(here.getWorld(), to.x(), to.y(), to.z(), walk.yaw(), 0));
        } else if (ticks % 5 == 0) {
            face(viewer.getLocation());
        }
        if (walk.done()) {
            if (here.distance(goal) <= 1.5 || legs > MAX_LEGS) {
                walk = null;
                Runnable then = arrived;
                arrived = null;
                face(viewer.getLocation());
                if (then != null) {
                    then.run();
                }
            } else {
                plan();
            }
        }
    }

    /** With its bubble: Bukkit refuses to move an entity with a passenger unless told to keep it. */
    private void move(Location to) {
        body.teleportAsync(to, PlayerTeleportEvent.TeleportCause.PLUGIN, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    private void face(Location target) {
        Location at = body.getLocation();
        float yaw = GuideWalk.yawTowards(new GuideWalk.Point(at.getX(), at.getY(), at.getZ()),
                new GuideWalk.Point(target.getX(), target.getY(), target.getZ()));
        body.setRotation(yaw, 0);
    }

    private static Pathing.Cell cell(Location at) {
        return new Pathing.Cell(at.getBlockX(), (int) Math.floor(at.getY() + 0.05), at.getBlockZ());
    }
}
