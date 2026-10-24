package de.raindancer.core.ui.help;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/** How {@code /help} and the guide standing in the world look and sound. Core's own, in Core's config.yml. */
@Settings(id = "core-help", topics = {
        @Topic(path = "config/help", title = "Help & guide", icon = Material.BOOK,
                description = "The guide players ask with /help or by talking to them at spawn."),
})
public record HelpSettings(

        @In("config/help") @Title("Guide's name")
        @Describe("Who answers /help, shown above every answer and over the guide standing in the world.")
        String guideName,

        @In("config/help") @Title("Guide's skins")
        @Describe("For guides placed as people: a player name, a MineSkin link or mineskin:<id>; several, "
                + "comma-separated, are shared out. Empty: the black suit. Placed guides keep theirs until placed again.")
        String guideSkins,

        @In("config/help") @Title("Guide's voice")
        @Describe("The sound the guide makes when somebody starts talking, e.g. entity.witch.ambient for a witch. Empty: silent.")
        String guideVoice,

        @In("config/help") @Title("Greet new players")
        @Describe("A player's first join ends with a line telling them about /help and the guide.")
        boolean greetNewPlayers,

        @In("config/help") @Title("Wiki")
        @Describe("A web address the guide offers as 'read more' on every page. Empty: none.")
        String guideWiki,

        @In("config/help") @Title("Answers in chat for Bedrock players")
        @Describe("Bedrock players get the guide's answers in chat instead of a dialog window. Geyser shows "
                + "dialogs as forms, which works; turn this on if yours does not.")
        boolean bedrockInChat

) {

    public static final HelpSettings DEFAULTS = new HelpSettings("Guide", "", "block.amethyst_block.chime", true,
            "", false);
}
