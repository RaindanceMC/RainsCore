package de.raindancer.core.content.pack;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A pack that already exists somewhere, rather than one built here.
 *
 * <h2>Why this is not a {@link PackContribution}</h2>
 * A contribution is a folder of assets a plugin hands over for Core to merge and serve. This is the
 * other thing entirely: one finished pack, made by hand, hosted somewhere else, that the whole server
 * wears. Core never sees its contents and has nothing to merge — all it does is pass the client a URL
 * and a hash.
 *
 * <p>It still goes through {@link ResourcePacks} rather than round it, and that is the whole point. A
 * player has <em>one</em> resource pack slot. A plugin that called {@code setResourcePack} itself would
 * work perfectly until anything contributed assets, at which point the two fight over that slot and
 * whoever sends last wins silently — exactly the collision this package exists to arbitrate.
 *
 * @param id   what the server calls it, so it can be replaced or withdrawn by name
 * @param url  where the client downloads it from; absolute, and used exactly as given
 * @param sha1 what it must hash to. Not optional: this is what a client caches by, and a pack sent
 *             without one is re-downloaded on every single join
 */
public record HostedPack(String id, String url, String sha1) {

    /** What a sha1 looks like written down. A client rejects anything else, saying nothing useful. */
    private static final Pattern SHA1 =
            Pattern.compile("[0-9a-f]{40}");

    /**
     * One, with everything tidied.
     *
     * <p>Never throws: a pack described wrongly in somebody's config is a line in the log and a pack
     * that is not sent, not a plugin that fails to start. {@link #isUsable} is how a caller finds out.
     */
    public static HostedPack at(String id, String url, String sha1) {
        return new HostedPack(
                id == null ? "" : id.trim(),
                url == null ? "" : url.trim(),
                // Lower-cased, because it is pasted from a file, a website or a shell and the case it
                // arrives in is whichever tool last printed it.
                sha1 == null ? "" : sha1.trim().toLowerCase(Locale.ROOT));
    }

    /** Whether there is enough here to send. */
    public boolean isUsable() {
        return !id.isEmpty() && !url.isEmpty() && SHA1.matcher(sha1).matches();
    }

    /** What is wrong with it, for the line that says why it was refused. */
    public String problem() {
        if (id.isEmpty()) {
            return "a hosted pack needs a name";
        }
        if (url.isEmpty()) {
            return "'" + id + "' has nowhere to be downloaded from";
        }
        if (!SHA1.matcher(sha1).matches()) {
            return "'" + id + "' has no usable sha1. Without one a client re-downloads it on every "
                    + "join, so it is refused rather than sent";
        }
        return "";
    }

    /**
     * The id the client recognises it by.
     *
     * <p>Derived from the name and the hash rather than random, so the same pack is the same pack
     * across a restart — a fresh id every boot is a fresh download every boot — and a pack whose
     * contents changed is correctly a different one.
     */
    public UUID offerId() {
        return UUID.nameUUIDFromBytes((id + ":" + sha1).getBytes(StandardCharsets.UTF_8));
    }
}
