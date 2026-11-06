package de.raindancer.core.world.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A rig's item displays: made beside the carrier, never saved, moved with it every tick on its thread. */
final class RigDisplays {

    /** Ticks a display takes to glide to its next pose — smooth wings without a packet every frame. */
    private static final int GLIDE = 3;

    private RigDisplays() {
    }

    static List<UUID> spawn(Mob carrier, Rig rig) {
        List<UUID> made = new ArrayList<>(rig.parts().size());
        Location at = carrier.getLocation();
        at.setYaw(0);
        at.setPitch(0);
        for (Rig.Part part : rig.parts()) {
            if (!part.shown()) {
                made.add(null);
                continue;
            }
            ItemDisplay display = carrier.getWorld().spawn(at, ItemDisplay.class, shown -> {
                shown.setPersistent(false);
                shown.setItemStack(item(part.model()));
                shown.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                shown.setBillboard(Display.Billboard.FIXED);
                shown.setInterpolationDuration(GLIDE);
                shown.setTeleportDuration(2);
                shown.setViewRange(2);
            });
            made.add(display.getUniqueId());
        }
        return java.util.Collections.unmodifiableList(made);
    }

    private static ItemStack item(String model) {
        ItemStack stack = new ItemStack(Material.PAPER);
        NamespacedKey key = NamespacedKey.fromString(model);
        if (key != null) {
            stack.editMeta(meta -> meta.setItemModel(key));
        }
        return stack;
    }

    /** Every tick: the displays to the carrier, every other tick a new pose. */
    static void follow(Server server, Mob carrier, Companion companion, CompanionSpec spec, long tick) {
        Rig rig = spec.rig();
        List<UUID> parts = companion.rigParts;
        if (rig == null || parts.size() != rig.parts().size()) {
            return;
        }
        Location at = carrier.getLocation();
        at.setYaw(0);
        at.setPitch(0);
        boolean pose = tick % 2 == 0;
        List<Matrix4f> matrices = null;
        if (pose) {
            float scale = spec.rigScale();
            boolean biting = companion.ticks - companion.lastAttack < 10;
            // the dragon's head is towards −z, where a mob faces +z: turned half round
            matrices = RigPose.pose(rig, scale, carrier.getBodyYaw() + 180,
                    new RigPose.State(tick, spec.flies(), biting), false,
                    (float) (carrier.getHeight() / 2));
        }
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) == null || !(server.getEntity(parts.get(i)) instanceof ItemDisplay display)
                    || !display.isValid()) {
                continue;
            }
            if (display.getLocation().distanceSquared(at) > 0.0004) {
                display.teleportAsync(at, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            if (matrices != null) {
                display.setInterpolationDelay(0);
                display.setTransformationMatrix(matrices.get(i));
            }
        }
    }
}
