package de.raindancer.core.ui.help;

import java.util.Optional;

/**
 * Everything a help page may ask about the server it is read on.
 *
 * <p>A page is written once and read on many servers, each configured differently. "Paying costs 5 % tax"
 * is wrong the day somebody sets the tax to zero, so a page asks — {@code {setting:economy:pay.tax-percent}},
 * {@code when: economy:features.pay} — and this answers. The live answer comes from Core's settings registry
 * ({@link LiveHelpContext}); a module's test answers from its own defaults ({@link HelpLint}), which is how a
 * typo in a key fails a build instead of printing a question mark to players.
 */
public interface HelpContext {

    /**
     * A setting's current value — {@code Boolean}, a number, a {@code String}, an enum, a {@code List} — by its
     * {@code plugin:key} (or bare key) name. Empty when nothing on this server declares it.
     */
    Optional<Object> setting(String key);

    /** The same, as a reader should see it: "on", "5", "world, nether". */
    default Optional<String> display(String key) {
        return setting(key).map(HelpValues::display);
    }

    /** Whether the reader holds this permission. */
    boolean has(String permission);

    /** Whether a plugin of that name is enabled here, case-blind. */
    boolean hasPlugin(String name);

    /** Whether a module of that id is running here — inside its own plugin or a bundle. */
    boolean hasModule(String id);

    /** Something a plugin knows at runtime and no setting holds — see {@link HelpFacts}. */
    Optional<String> fact(String name);

    /** An amount, in this server's currency: "⛃1,000". Empty when there is no economy or it cannot be read. */
    Optional<String> money(String amount);

    /** The title of another topic, for a link to it. Empty when the reader cannot see it. */
    Optional<String> topicTitle(String id);

    /** What this server calls itself. */
    String serverName();

    /** Who is reading. "you" for the console. */
    String readerName();
}
