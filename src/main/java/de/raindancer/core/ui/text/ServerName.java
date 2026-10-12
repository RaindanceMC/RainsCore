package de.raindancer.core.ui.text;

import net.kyori.adventure.text.Component;

/**
 * What the server is called wherever a plugin names it: the owner's {@code server-name} setting, else
 * {@code server-name} in link.yml, else the MOTD's first line. Plain text — shown, never parsed.
 */
public final class ServerName {

    public static final String UNNAMED = "This server";

    private ServerName() {
    }

    public static String resolve(String setting, String linkName, Component motd) {
        if (setting != null && !setting.isBlank()) {
            return setting.strip();
        }
        if (linkName != null && !linkName.isBlank()) {
            return linkName.strip();
        }
        String firstLine = Text.plain(motd).lines().findFirst().orElse("").strip();
        return firstLine.isEmpty() ? UNNAMED : firstLine;
    }
}
