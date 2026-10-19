package de.raindancer.core.data.stash;

import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stashes on disk, one file per owner ({@code <uuid>.yml}) in the folder it is given.
 *
 * <p>An item the server cannot read back is never dropped: it stays in the stash's {@code kept} list and
 * is written out again exactly as it was found, so it is still there once the mod or version it needs is.
 *
 * <p>Keep one live {@link Stash} per owner per store. A stash loaded again carries on counting from the last
 * save, but two live at once for the same owner would each overwrite the other.
 */
public final class StashStore {

    private static final LogChannel log = Log.of("store");

    private final Path folder;
    private final ItemText codec;
    private final Map<UUID, YamlStore> files = new ConcurrentHashMap<>();
    /** The newest version written per owner, so a save that was overtaken does not land last. */
    private final Map<UUID, Long> written = new ConcurrentHashMap<>();

    public StashStore(Path folder, ItemText codec) {
        this.folder = folder;
        this.codec = codec;
    }

    public Stash load(UUID owner, int capacity) {
        return load(owner, capacity, true);
    }

    /** {@link #load} for a stash kept without armour stands — see {@link Stash#withoutStands}. */
    public Stash loadWithoutStands(UUID owner, int capacity) {
        return load(owner, capacity, false);
    }

    private Stash load(UUID owner, int capacity, boolean stands) {
        YamlConfiguration yaml = file(owner).read();
        List<String> kept = new ArrayList<>(yaml.getStringList("kept"));

        List<ItemStack> items = new ArrayList<>();
        for (String line : yaml.getStringList("items")) {
            ItemStack item = codec.read(line);
            if (item == null) {
                kept.add(line);
            } else {
                items.add(item);
            }
        }
        Map<ArmourPiece, ItemStack> armour = new EnumMap<>(ArmourPiece.class);
        for (ArmourPiece piece : ArmourPiece.values()) {
            String line = yaml.getString("armour." + key(piece));
            if (line == null) {
                continue;
            }
            ItemStack item = codec.read(line);
            if (item == null) {
                kept.add(line);
            } else {
                armour.put(piece, item);
            }
        }
        if (kept.size() > yaml.getStringList("kept").size()) {
            log.warn("{} item(s) in the stash of {} could not be read by this server. They are kept in "
                    + "the file untouched and will come back once they can be read.",
                    kept.size() - yaml.getStringList("kept").size(), owner);
        }
        long version = written.getOrDefault(owner, 0L);
        return stands ? Stash.restored(capacity, items, armour, kept, version)
                : Stash.restoredWithoutStands(capacity, items, armour, kept, version);
    }

    /**
     * Writes this picture of the stash, unless a newer one has already been written.
     *
     * @return whether the disk now holds this picture or a newer one
     */
    public boolean save(UUID owner, Stash.Contents contents) {
        YamlStore store = file(owner);
        synchronized (store) {
            Long newest = written.get(owner);
            if (newest != null && contents.version() <= newest) {
                return true;
            }
            List<String> items = new ArrayList<>();
            for (ItemStack item : contents.items()) {
                String line = codec.write(item);
                if (line != null) {
                    items.add(line);
                }
            }
            boolean saved = store.write(yaml -> {
                yaml.set("items", items);
                contents.armour().forEach((piece, item) -> {
                    String line = codec.write(item);
                    if (line != null) {
                        yaml.set("armour." + key(piece), line);
                    }
                });
                if (!contents.kept().isEmpty()) {
                    yaml.set("kept", contents.kept());
                }
            });
            if (saved) {
                written.put(owner, contents.version());
            }
            return saved;
        }
    }

    /**
     * Whether the last {@link #load} of this owner's stash could read its file. A file that is there and cannot be
     * parsed loads as an <em>empty</em> stash (and is never written over); a caller about to act on "it is empty" —
     * a guild disbanding only when its vault is — must ask this first, or it acts on a stash that only looks empty.
     * True for a stash never loaded and for one with no file yet.
     */
    public boolean isReadable(UUID owner) {
        YamlStore store = files.get(owner);
        return store == null || store.problems().isEmpty();
    }

    private YamlStore file(UUID owner) {
        // This server's: a stash is a saved inventory, and inventories are each server's own. Shared, a stash
        // emptied on one server could be emptied again on the next before the first write landed.
        return files.computeIfAbsent(owner, id -> new YamlStore(folder.resolve(id + ".yml")));
    }

    private static String key(ArmourPiece piece) {
        return piece.name().toLowerCase(Locale.ROOT);
    }
}
