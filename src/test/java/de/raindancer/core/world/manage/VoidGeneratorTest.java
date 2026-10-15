package de.raindancer.core.world.manage;

import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.generator.WorldInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VoidGeneratorTest {

    @Test
    @DisplayName("nothing is generated: no terrain, caves, decorations, mobs or structures")
    void generatesNothing() {
        VoidGenerator generator = VoidGenerator.empty();
        Random random = new Random(1);
        WorldInfo info = mock(WorldInfo.class);

        assertThat(generator.shouldGenerateNoise(info, random, 0, 0)).isFalse();
        assertThat(generator.shouldGenerateSurface(info, random, 0, 0)).isFalse();
        assertThat(generator.shouldGenerateCaves(info, random, 0, 0)).isFalse();
        assertThat(generator.shouldGenerateDecorations(info, random, 0, 0)).isFalse();
        assertThat(generator.shouldGenerateMobs(info, random, 0, 0)).isFalse();
        assertThat(generator.shouldGenerateStructures(info, random, 0, 0)).isFalse();
    }

    @Test
    @DisplayName("without a biome the world's own biome source decides")
    void noBiomeLeavesTheDefault() {
        assertThat(VoidGenerator.empty().getDefaultBiomeProvider(mock(WorldInfo.class))).isNull();
    }

    @Test
    @DisplayName("with a biome every spot of the world has it, resolved from the key once the world asks")
    void oneBiomeEverywhere() {
        List<NamespacedKey> asked = new ArrayList<>();
        NamespacedKey nether = NamespacedKey.minecraft("nether_wastes");
        var provider = new VoidGenerator(nether, key -> {
            asked.add(key);
            return null;
        }).getDefaultBiomeProvider(mock(WorldInfo.class));

        assertThat(provider).isNotNull();
        provider.getBiome(mock(WorldInfo.class), 120, -40, 9000);
        provider.getBiomes(mock(WorldInfo.class));
        assertThat(asked).containsOnly(nether);
    }

    @Test
    @DisplayName("players arriving at the world's spawn land above the middle")
    void spawnsAboveTheMiddle() {
        World world = mock(World.class);
        var spawn = VoidGenerator.empty().getFixedSpawnLocation(world, new Random(1));

        assertThat(spawn.getX()).isEqualTo(0.5);
        assertThat(spawn.getZ()).isEqualTo(0.5);
        assertThat(spawn.getY()).isEqualTo(80);
    }
}
