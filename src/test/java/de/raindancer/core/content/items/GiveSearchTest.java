package de.raindancer.core.content.items;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GiveSearchTest {

    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("minecraft:diamond_sword", "Diamond Sword");
        NAMES.put("minecraft:diamond", "Diamond");
        NAMES.put("minecraft:diamond_block", "Diamond Block");
        NAMES.put("minecraft:deepslate_diamond_ore", "Deepslate Diamond Ore");
        NAMES.put("minecraft:stone", "Stone");
        NAMES.put("fishing:rod-void", "Void Rod");
        NAMES.put("enchants:shard-diamond_skin", "Diamond Skin Shard");
    }

    @Test
    @DisplayName("every item on the server whose name answers, vanilla's and the plugins', closest first")
    void ranked() {
        assertThat(GiveSearch.rank("diamond", NAMES)).containsExactly("minecraft:diamond", "minecraft:diamond_block",
                "enchants:shard-diamond_skin", "minecraft:diamond_sword", "minecraft:deepslate_diamond_ore");
    }

    @Test
    @DisplayName("typos are forgiven, after every correct match; nothing typed or nothing found is an empty list")
    void typos() {
        assertThat(GiveSearch.rank("diamnd blok", NAMES)).containsExactly("minecraft:diamond_block");
        assertThat(GiveSearch.rank("void", NAMES)).containsExactly("fishing:rod-void");
        assertThat(GiveSearch.rank("", NAMES)).isEmpty();
        assertThat(GiveSearch.rank("xyzzy", NAMES)).isEmpty();
    }
}
