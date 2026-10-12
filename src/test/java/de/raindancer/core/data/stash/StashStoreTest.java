package de.raindancer.core.data.stash;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.testkit.TestItems;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A stash survives a restart — including the items this server cannot read today. */
class StashStoreTest {

    /** "MATERIAL:amount" as bytes, which is all these tests need an item to be. */
    private static final class PlainBytes implements ItemBytes {

        @Override
        public int dataVersion() {
            return 1;
        }

        @Override
        public byte[] toBytes(ItemStack item) {
            return (item.getType().name() + ":" + item.getAmount()).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public boolean isNothing(ItemStack item) {
            return item == null || item.isEmpty();
        }

        @Override
        public Optional<ItemStack> fromBytes(byte[] bytes) {
            String[] parts = new String[0];
            try {
                parts = new String(bytes, StandardCharsets.UTF_8).split(":");
                return Optional.of(TestItems.of(Material.valueOf(parts[0]), Integer.parseInt(parts[1])));
            } catch (RuntimeException notOurs) {
                return Optional.empty();
            }
        }
    }

    private final UUID owner = UUID.randomUUID();

    private StashStore storage(Path folder) {
        return new StashStore(folder.resolve("vaults"), new ItemText(new PlainBytes()));
    }

    @Test
    @DisplayName("items and armour come back as they went in")
    void roundTrip(@TempDir Path folder) {
        Stash stash = new Stash(10);
        stash.deposit(TestItems.of(Material.DIRT, 40));
        stash.deposit(TestItems.of(Material.MACE));
        stash.deposit(TestItems.of(Material.NETHERITE_LEGGINGS));

        assertThat(storage(folder).save(owner, stash.contents())).isTrue();
        Stash back = storage(folder).load(owner, 10);

        assertThat(back.items()).extracting(ItemStack::getType).containsExactly(Material.DIRT, Material.MACE);
        assertThat(back.items().getFirst().getAmount()).isEqualTo(40);
        assertThat(back.armour(ArmourPiece.LEGS)).get().extracting(ItemStack::getType)
                .isEqualTo(Material.NETHERITE_LEGGINGS);
    }

    @Test
    @DisplayName("nobody's stash yet is an empty one, not an error")
    void missingIsEmpty(@TempDir Path folder) {
        assertThat(storage(folder).load(owner, 10).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a stash file that is not YAML loads empty but says it was unreadable — so nobody takes it for empty")
    void unreadableFileIsReported(@TempDir Path folder) throws Exception {
        StashStore store = storage(folder);
        assertThat(store.isReadable(owner)).as("never loaded").isTrue();
        store.load(owner, 10);
        assertThat(store.isReadable(owner)).as("no file is an empty stash, not a broken one").isTrue();

        Path file = folder.resolve("vaults").resolve(owner + ".yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "items: [unclosed\n  : : :\n");
        StashStore again = storage(folder);
        assertThat(again.load(owner, 10).isEmpty()).isTrue();
        assertThat(again.isReadable(owner)).isFalse();

        Files.writeString(file, "items: []\n");
        again.load(owner, 10);
        assertThat(again.isReadable(owner)).as("readable again once fixed").isTrue();
    }

    @Test
    @DisplayName("an item this server cannot read is kept on disk rather than dropped at the next save")
    void unreadableSurvives(@TempDir Path folder) throws Exception {
        String unreadable = Base64.getEncoder().encodeToString("MODDED_THING:1".getBytes(StandardCharsets.UTF_8));
        Path file = folder.resolve("vaults").resolve(owner + ".yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "items:\n- " + unreadable + "\n- "
                + Base64.getEncoder().encodeToString("STONE:3".getBytes(StandardCharsets.UTF_8)) + "\n");

        Stash loaded = storage(folder).load(owner, 10);
        loaded.deposit(TestItems.of(Material.DIRT, 1));
        storage(folder).save(owner, loaded.contents());

        assertThat(loaded.items()).extracting(ItemStack::getType).containsExactly(Material.STONE, Material.DIRT);
        assertThat(Files.readString(file)).contains(unreadable);
    }

    @Test
    @DisplayName("a stash loaded again carries on from what was written, so its changes are not taken for old ones")
    void reloadedStashIsNotStale(@TempDir Path folder) {
        StashStore storage = storage(folder);
        Stash first = new Stash(10);
        for (int i = 0; i < 5; i++) {
            first.deposit(TestItems.of(Material.DIRT, 1));
        }
        storage.save(owner, first.contents());

        Stash again = storage.load(owner, 10);
        again.deposit(TestItems.of(Material.STONE, 1));
        storage.save(owner, again.contents());

        assertThat(storage.load(owner, 10).items()).extracting(ItemStack::getType)
                .containsExactly(Material.DIRT, Material.STONE);
    }

    @Test
    @DisplayName("an older picture landing after a newer one does not overwrite it")
    void staleWritesAreSkipped(@TempDir Path folder) {
        StashStore storage = storage(folder);
        Stash stash = new Stash(10);
        stash.deposit(TestItems.of(Material.DIRT, 1));
        Stash.Contents older = stash.contents();
        stash.deposit(TestItems.of(Material.STONE, 1));

        storage.save(owner, stash.contents());
        storage.save(owner, older);

        assertThat(storage.load(owner, 10).items()).extracting(ItemStack::getType)
                .containsExactly(Material.DIRT, Material.STONE);
    }
}
