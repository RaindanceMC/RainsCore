package de.raindancer.core.ui.badge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import java.util.List;

/**
 * One rank's badge.
 *
 * @param characters the first is the Java glyph; all of them together are its Bedrock cells, left to right
 * @param tag        the tag it was made from, drawn as text — what somebody without the pack is shown
 */
public record Badge(String label, List<TextColor> colours, String characters, int cells, Component tag) {

    public Badge {
        colours = List.copyOf(colours);
    }
}
