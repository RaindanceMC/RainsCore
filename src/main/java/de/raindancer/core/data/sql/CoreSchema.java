package de.raindancer.core.data.sql;

/**
 * Every table this library keeps, and the order they came into being in.
 *
 * <h2>What is in here and what is deliberately not</h2>
 * A database earns its place for data the <em>server</em> writes: warps somebody set, a ban somebody
 * handed out, an achievement somebody earned, the state of a farm world. That data is written while
 * players are on, is never edited by hand, and losing half of it to a kill mid-save is a real
 * incident.
 *
 * <p>It does not earn its place for data a <em>person</em> writes. {@code config.yml}, the custom
 * item definitions and the loot tables are authored by whoever runs the server, read at startup, and
 * meant to be opened in an editor with comments in them. Putting those in a database would take away
 * the only interface they have and give nothing back: they are not written under load, they are not
 * queried, and there is nothing to lose halfway through. Those stay as files — see
 * {@code data.store.YamlStore}, which is still the right tool for exactly that job.
 *
 * <p>So the line is not "files are old and databases are new". It is: <b>what the server writes goes
 * in the database, what a human writes stays in a file.</b>
 *
 * <h2>The rule about editing this list</h2>
 * Steps never change once they have shipped, and are never reordered or removed — only appended to.
 * See {@link Schema} for why. A mistake in a shipped step is corrected by a new step, not by editing
 * the old one.
 */
public final class CoreSchema {

    private CoreSchema() {
    }

    /**
     * What the server is.
     *
     * <p>One list for the whole file rather than one per subsystem, because the version is a single
     * number per database — see {@link Databases#core()}.
     */
    public static final Schema CORE = Schema.of(

            // ------------------------------------------------------------------ places
            //
            // Warps, homes, shops and death markers are all one thing wearing different hats: a named
            // point in a world that somebody owns. `kind` is what tells them apart, and it is a
            // column rather than four tables because every question anybody asks — what is near
            // here, what does this player own, what is shared — is the same question for all of them.
            """
            CREATE TABLE place (
                id        TEXT    PRIMARY KEY,
                name      TEXT    NOT NULL,
                kind      TEXT    NOT NULL,
                owner     TEXT,
                world     TEXT    NOT NULL,
                x         REAL    NOT NULL,
                y         REAL    NOT NULL,
                z         REAL    NOT NULL,
                yaw       REAL    NOT NULL DEFAULT 0,
                pitch     REAL    NOT NULL DEFAULT 0,
                icon      TEXT,
                label     TEXT,
                shared    INTEGER NOT NULL DEFAULT 0
            )""",

            // Whatever the owning plugin needs to remember alongside a place. A table rather than a
            // column of encoded text, so that ON DELETE CASCADE can be relied on to take them with
            // the place — which is the reason foreign keys are switched on.
            """
            CREATE TABLE place_tag (
                place TEXT NOT NULL REFERENCES place(id) ON DELETE CASCADE,
                name  TEXT NOT NULL,
                value TEXT NOT NULL,
                PRIMARY KEY (place, name)
            )""",

            // The two questions asked on every menu open: what does this player own, and what is in
            // this world. Without these, both are a scan of every place on the server.
            "CREATE INDEX place_by_owner ON place (owner, kind)",
            "CREATE INDEX place_by_world ON place (world)",

            // ------------------------------------------------------------------ punishments
            //
            // Kept for ever rather than deleted when they expire: "has this player been banned
            // before" is the question a moderator actually asks, and it cannot be answered by a
            // table that only holds what is in force right now.
            """
            CREATE TABLE punishment (
                id            TEXT    PRIMARY KEY,
                target        TEXT    NOT NULL,
                kind          TEXT    NOT NULL,
                moderator     TEXT,
                reason        TEXT    NOT NULL,
                given_at      INTEGER NOT NULL,
                ends_at       INTEGER,
                lifter        TEXT,
                lifter_reason TEXT,
                lifted_at     INTEGER
            )""",

            // Asked on every single login, for every player, before they are let in. This index is
            // the difference between that being free and being a scan of every punishment the server
            // has ever handed out.
            "CREATE INDEX punishment_by_target ON punishment (target, kind)",

            // ------------------------------------------------------------------ identities
            //
            // How a player is shown: prefix, suffix, colour, the line under their name. One row per
            // player, so a player with nothing set has no row rather than a row full of blanks.
            """
            CREATE TABLE identity (
                player         TEXT PRIMARY KEY,
                prefix         TEXT NOT NULL DEFAULT '',
                suffix         TEXT NOT NULL DEFAULT '',
                nametag_prefix TEXT NOT NULL DEFAULT '',
                colour         TEXT NOT NULL DEFAULT '',
                subtitle       TEXT NOT NULL DEFAULT ''
            )""",

            // ------------------------------------------------------------------ achievements
            //
            // Only what was earned and how far along somebody is. What the achievements *are* is a
            // definition somebody wrote and stays in a file.
            """
            CREATE TABLE achievement_earned (
                player      TEXT    NOT NULL,
                achievement TEXT    NOT NULL,
                earned_at   INTEGER NOT NULL,
                PRIMARY KEY (player, achievement)
            )""",
            """
            CREATE TABLE achievement_progress (
                player      TEXT    NOT NULL,
                achievement TEXT    NOT NULL,
                sofar       INTEGER NOT NULL,
                PRIMARY KEY (player, achievement)
            )""",

            // ------------------------------------------------------------------ farm worlds
            //
            // When each set of farm worlds was last made, and when it was last attempted. The second
            // is not the first: a reset that failed must not be retried in a loop every time
            // somebody walks into the portal.
            """
            CREATE TABLE farm_world (
                name     TEXT PRIMARY KEY,
                made_at  INTEGER,
                tried_at INTEGER
            )""",

            // ------------------------------------------------------------------ world seeds
            //
            // Every seed a world was created with, regenerated with, or had when it was thrown away —
            // see SeedHistory. Only ever appended to: a regenerated world's old seed exists nowhere
            // else once its folder is gone, so a row here is never rewritten.
            """
            CREATE TABLE world_seed (
                id    INTEGER PRIMARY KEY,
                world TEXT    NOT NULL,
                seed  INTEGER NOT NULL,
                at    INTEGER NOT NULL,
                cause TEXT    NOT NULL
            )""",
            "CREATE INDEX world_seed_by_world ON world_seed (world, at DESC)",

            // ------------------------------------------------------------------ run history
            //
            // Every finished run of every game — a speedrun, a hunt, a hunger games round — see
            // RunHistory. `game` is the plugin's own namespace and `category` its mode and variant, so
            // one plugin with several modes keeps them apart without a table each. `score` is a time
            // in milliseconds or a count; `lower_wins` says which way it ranks. Never deleted, only
            // struck from the rankings: "what was the record before" is a question people ask.
            """
            CREATE TABLE run (
                id         TEXT    PRIMARY KEY,
                game       TEXT    NOT NULL,
                category   TEXT    NOT NULL,
                started_at INTEGER NOT NULL,
                score      INTEGER NOT NULL,
                lower_wins INTEGER NOT NULL,
                ranked     INTEGER NOT NULL DEFAULT 1
            )""",
            """
            CREATE TABLE run_player (
                run    TEXT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
                player TEXT NOT NULL,
                name   TEXT,
                PRIMARY KEY (run, player)
            )""",
            // Splits and anything else a game wants to keep with a run, by kind and name.
            """
            CREATE TABLE run_value (
                run   TEXT NOT NULL REFERENCES run(id) ON DELETE CASCADE,
                kind  TEXT NOT NULL,
                name  TEXT NOT NULL,
                value TEXT NOT NULL,
                PRIMARY KEY (run, kind, name)
            )""",
            "CREATE INDEX run_by_category ON run (game, category, score)",
            "CREATE INDEX run_player_by_player ON run_player (player)",

            // ------------------------------------------------------------------ nicknames
            //
            // What a player goes by instead of their name, kept so a command can be pointed at
            // somebody by their nickname while they are offline too — see Nicknames. `typed` is the
            // nickname as a command argument would spell it (lower case, spaces as underscores), so
            // looking somebody up is an index hit rather than a scan that normalises every row.
            """
            CREATE TABLE nickname (
                player TEXT PRIMARY KEY,
                plain  TEXT NOT NULL,
                typed  TEXT NOT NULL
            )""",
            "CREATE INDEX nickname_by_typed ON nickname (typed)",
            // 2.0.0: which server of the network a place is on. Blank is "this server", which is what
            // every row a 1.x server wrote means; the importer stamps them when a server joins a network.
            "ALTER TABLE place ADD COLUMN server TEXT NOT NULL DEFAULT ''",
            "CREATE INDEX place_by_server ON place (server)",
            // 2.0.0: a world's seeds are the seeds of that server's world; another server's "world" is another world.
            "ALTER TABLE world_seed ADD COLUMN server TEXT NOT NULL DEFAULT ''",
            "CREATE INDEX world_seed_by_server ON world_seed (server, world)",
            // 2.0.0: playtime in the database, counted by whichever server the player is on, added up.
            """
            CREATE TABLE playtime (
                player  TEXT    PRIMARY KEY,
                minutes INTEGER NOT NULL,
                active  INTEGER NOT NULL
            )""",
            // 2.0.0: secrets every server of a network must share — the key blocked-join addresses are hashed
            // with, the seal on banknotes. Made once by whichever server needs one first.
            """
            CREATE TABLE secret (
                name       TEXT    PRIMARY KEY,
                value      TEXT    NOT NULL,
                created_at INTEGER NOT NULL
            )""",
            // 2.0.0: every server's copy of every player's inventory, so staff see it from anywhere. The vanilla
            // player file stays the live copy; this one is not applied on join (yet).
            """
            CREATE TABLE saved_inventory (
                server      TEXT    NOT NULL,
                player      TEXT    NOT NULL,
                saved_at    INTEGER NOT NULL,
                contents    BLOB    NOT NULL,
                ender_chest BLOB    NOT NULL,
                level       INTEGER NOT NULL,
                progress    REAL    NOT NULL,
                health      REAL    NOT NULL,
                food        INTEGER NOT NULL,
                saturation  REAL    NOT NULL,
                effects     TEXT    NOT NULL,
                PRIMARY KEY (server, player)
            )""",
            "CREATE INDEX saved_inventory_by_player ON saved_inventory (player, saved_at DESC)");

    /**
     * What was done.
     *
     * <p>Its own file because it is the opposite of everything above: append-only, written far more
     * often than all of it put together, and thrown away by age rather than kept.
     */
    public static final Schema AUDIT = Schema.of(

            // One row per thing a moderator did. `feature` and `actor` are columns rather than
            // something to be parsed back out of a message, because those are the two questions this
            // table exists to answer — see the indexes below.
            //
            // `INTEGER PRIMARY KEY` and deliberately not AUTOINCREMENT. The keyword sounds like what
            // is wanted and is not: it makes SQLite maintain a row in the sqlite_sequence table on
            // every single insert, purely to guarantee an id is never reused. This is the most
            // write-heavy table on the server and old rows are aged out, so reusing the id of a row
            // deleted a year ago costs nothing — while the extra write costs something on every
            // entry. A plain INTEGER PRIMARY KEY already counts upwards.
            """
            CREATE TABLE entry (
                id       INTEGER PRIMARY KEY,
                at       INTEGER NOT NULL,
                feature  TEXT    NOT NULL,
                action   TEXT    NOT NULL,
                actor    TEXT,
                actor_name TEXT,
                subject  TEXT,
                subject_name TEXT,
                detail   TEXT,
                world    TEXT
            )""",

            // Details that vary by action, so that adding one does not need a column: which slot,
            // which item, how long a ban was. Searchable, unlike a sentence.
            """
            CREATE TABLE entry_field (
                entry INTEGER NOT NULL REFERENCES entry(id) ON DELETE CASCADE,
                name  TEXT    NOT NULL,
                value TEXT,
                PRIMARY KEY (entry, name)
            )""",

            // The three ways this is read: what did this moderator do, what happened to this player,
            // and what happened in this feature — each of them newest first, which is why `at`
            // descends.
            "CREATE INDEX entry_by_actor ON entry (actor, at DESC)",
            "CREATE INDEX entry_by_subject ON entry (subject, at DESC)",
            "CREATE INDEX entry_by_feature ON entry (feature, at DESC)",

            // Kept, though "what happened recently" alone would not justify it: rows go in roughly
            // in time order, so the primary key already answers that.
            //
            // What it is really for is the two things that ask about `at` as a *range* — a report
            // between two dates, and the retention sweep deleting everything older than the
            // configured age. Without an index that DELETE is a scan of the largest table on the
            // server while holding the write lock, which is the one moment when a slow query is felt
            // by every player at once.
            "CREATE INDEX entry_by_time ON entry (at DESC)");
}
