package de.raindancer.core.world.manage;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.Random;

/**
 * A world of empty air: an arena map, a OneBlock island. Every generation step answers no, which is what makes a
 * chunk generator generate nothing at all.
 *
 * <p>With a biome, every spot has it — a void nether otherwise gets the overworld's biomes from the default source,
 * and the sky, the fog and which mobs spawn are all the biome's.
 */
public final class VoidGenerator extends ChunkGenerator {

    private final NamespacedKey biome;
    private final Function<NamespacedKey, Biome> resolve;

    VoidGenerator(NamespacedKey biome, Function<NamespacedKey, Biome> resolve) {
        this.biome = biome;
        this.resolve = resolve;
    }

    public static VoidGenerator empty() {
        return new VoidGenerator(null, key -> null);
    }

    /**
     * Every spot gets this biome. A key rather than the {@link Biome}: the biome registry is the running server's, and
     * a plugin often builds its generator before it should be touching that.
     */
    public static VoidGenerator withBiome(NamespacedKey biome) {
        return new VoidGenerator(biome, key -> RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(key));
    }

    @Override
    public boolean shouldGenerateNoise(@NotNull WorldInfo info, @NotNull Random random, int x, int z) {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface(@NotNull WorldInfo info, @NotNull Random random, int x, int z) {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves(@NotNull WorldInfo info, @NotNull Random random, int x, int z) {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations(@NotNull WorldInfo info, @NotNull Random random, int x, int z) {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs(@NotNull WorldInfo info, @NotNull Random random, int x, int z) {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures(@NotNull WorldInfo info, @NotNull Random random, int x, int z) {
        return false;
    }

    @Override
    public @Nullable BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo info) {
        if (biome == null) {
            return null;
        }
        Biome resolved = resolve.apply(biome);
        return new BiomeProvider() {
            @Override
            public @NotNull Biome getBiome(@NotNull WorldInfo world, int x, int y, int z) {
                return resolved;
            }

            @Override
            public @NotNull List<Biome> getBiomes(@NotNull WorldInfo world) {
                return Collections.singletonList(resolved);
            }
        };
    }

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0.5, 80, 0.5);
    }
}
