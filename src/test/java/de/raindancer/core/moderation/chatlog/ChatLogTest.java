package de.raindancer.core.moderation.chatlog;

import de.raindancer.core.data.sql.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ChatLogTest {

    @TempDir
    java.nio.file.Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final UUID bo = UUID.randomUUID();
    private final UUID cy = UUID.randomUUID();
    private Database database;
    private ChatLog log;

    @BeforeEach
    void open() {
        database = Database.open(folder.resolve("chatlog.db"), ChatLog.SCHEMA, () -> false);
        log = new ChatLog(database, clock::get);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("everything somebody said is kept, newest first, and only theirs")
    void perPlayer() {
        log.record(bo, "Bo", ChatLog.PUBLIC, "hello there");
        clock.addAndGet(1000);
        log.record(cy, "Cy", ChatLog.PUBLIC, "hi bo");
        clock.addAndGet(1000);
        log.record(bo, "Bo", "staff", "second line");

        List<ChatLog.Line> said = log.of(bo, "", 50, 0);
        assertThat(said).extracting(ChatLog.Line::text).containsExactly("second line", "hello there");
        assertThat(said.getFirst().channel()).isEqualTo("staff");
        assertThat(log.of(cy, "", 50, 0)).hasSize(1);
    }

    @Test
    @DisplayName("searching finds lines holding every word, in any case, and takes % and _ literally")
    void search() {
        log.record(bo, "Bo", ChatLog.PUBLIC, "I found DIAMONDS at the base");
        log.record(bo, "Bo", ChatLog.PUBLIC, "diamonds everywhere");
        log.record(bo, "Bo", ChatLog.PUBLIC, "100% sure");
        assertThat(log.of(bo, "diamonds base", 50, 0)).extracting(ChatLog.Line::text)
                .containsExactly("I found DIAMONDS at the base");
        assertThat(log.of(bo, "100%", 50, 0)).hasSize(1);
        assertThat(log.of(bo, "_", 50, 0)).isEmpty();
        assertThat(log.count(bo)).isEqualTo(3);
    }

    @Test
    @DisplayName("pages go back through the history")
    void paging() {
        for (int i = 0; i < 30; i++) {
            log.record(bo, "Bo", ChatLog.PUBLIC, "line " + i);
            clock.incrementAndGet();
        }
        assertThat(log.of(bo, "", 10, 0).getFirst().text()).isEqualTo("line 29");
        assertThat(log.of(bo, "", 10, 20).getLast().text()).isEqualTo("line 0");
    }

    @Test
    @DisplayName("lines older than the retention are deleted")
    void retention() {
        log.record(bo, "Bo", ChatLog.PUBLIC, "old");
        clock.addAndGet(Duration.ofDays(400).toMillis());
        log.record(bo, "Bo", ChatLog.PUBLIC, "new");
        assertThat(log.forgetOlderThan(Duration.ofDays(365))).isEqualTo(1);
        assertThat(log.of(bo, "", 50, 0)).extracting(ChatLog.Line::text).containsExactly("new");
    }

    @Test
    @DisplayName("everything recorded is handed to whoever watches, as it is said")
    void watchers() {
        List<String> seen = new ArrayList<>();
        java.util.function.Consumer<ChatLog.Line> watcher = line -> seen.add(line.name() + ": " + line.text());
        ChatLog.watch(watcher);
        try {
            log.record(bo, "Bo", ChatLog.PUBLIC, "watched");
        } finally {
            ChatLog.unwatch(watcher);
        }
        log.record(bo, "Bo", ChatLog.PUBLIC, "not watched");
        assertThat(seen).containsExactly("Bo: watched");
    }

    @Test
    @DisplayName("switched off, nothing is kept and nothing is watched")
    void switchedOff() {
        log.enabled(false);
        log.record(bo, "Bo", ChatLog.PUBLIC, "nothing");
        assertThat(log.of(bo, "", 50, 0)).isEmpty();
    }

    @Test
    @DisplayName("a search narrows by players, a time range and terms together, newest first")
    void searchCombines() {
        log.record(bo, "Bo", ChatLog.PUBLIC, "read the rules please");      // 1_000_000
        clock.addAndGet(1000);
        log.record(cy, "Cy", ChatLog.PUBLIC, "the RULES are long");          // 1_001_000
        clock.addAndGet(1000);
        log.record(bo, "Bo", ChatLog.PUBLIC, "rules again");                 // 1_002_000
        clock.addAndGet(1000);
        log.record(bo, "Bo", ChatLog.PUBLIC, "something else");              // 1_003_000

        ChatLog.Search rules = ChatLog.Search.everything().containing("rules");
        assertThat(log.search(rules, 50, 0)).extracting(ChatLog.Line::text)
                .containsExactly("rules again", "the RULES are long", "read the rules please");
        assertThat(log.count(rules)).isEqualTo(3);

        assertThat(log.search(rules.by(java.util.Set.of(bo)), 50, 0)).extracting(ChatLog.Line::text)
                .containsExactly("rules again", "read the rules please");
        assertThat(log.search(rules.by(java.util.Set.of(bo, cy)), 50, 0)).hasSize(3);

        ChatLog.Search window = rules.between(1_001_000L, 1_002_000L);   // from inclusive, to exclusive
        assertThat(log.search(window, 50, 0)).extracting(ChatLog.Line::text).containsExactly("the RULES are long");
        assertThat(log.search(ChatLog.Search.everything().between(1_002_000L, Long.MAX_VALUE), 50, 0))
                .extracting(ChatLog.Line::text).containsExactly("something else", "rules again");
        assertThat(log.count(ChatLog.Search.everything())).isEqualTo(4);
        assertThat(log.search(ChatLog.Search.everything(), 2, 2)).extracting(ChatLog.Line::text)
                .containsExactly("the RULES are long", "read the rules please");
    }

    @Test
    @DisplayName("several terms must all be there; a phrase must be there as it stands, spaces included")
    void termsAndPhrases() {
        log.record(bo, "Bo", ChatLog.PUBLIC, "my base is near spawn");
        log.record(bo, "Bo", ChatLog.PUBLIC, "spawn is near my base");
        assertThat(log.search(ChatLog.Search.everything().containing("base", "spawn"), 50, 0)).hasSize(2);
        assertThat(log.search(ChatLog.Search.everything().containing("near spawn"), 50, 0))
                .extracting(ChatLog.Line::text).containsExactly("my base is near spawn");
        assertThat(log.search(ChatLog.Search.everything().containing("Near SPAWN"), 50, 0)).hasSize(1);
    }

    @Test
    @DisplayName("letters past ASCII are matched whichever case either side is written in")
    void umlauts() {
        log.record(bo, "Bo", ChatLog.PUBLIC, "Über den Berg");
        log.record(bo, "Bo", ChatLog.PUBLIC, "ich bin übermüdet");
        log.record(bo, "Bo", ChatLog.PUBLIC, "ÄRGER");
        assertThat(log.search(ChatLog.Search.everything().containing("über"), 50, 0)).hasSize(2);
        assertThat(log.search(ChatLog.Search.everything().containing("ÜBER"), 50, 0)).hasSize(2);
        assertThat(log.search(ChatLog.Search.everything().containing("ärger"), 50, 0)).hasSize(1);
    }

    @Test
    @DisplayName("an old name still finds who used it, in any case; the name a line was said under is kept")
    void oldNames() {
        log.record(bo, "OldBo", ChatLog.PUBLIC, "before the rename");
        log.record(bo, "Bo", ChatLog.PUBLIC, "after the rename");
        log.record(cy, "Cy", ChatLog.PUBLIC, "hi");
        assertThat(log.playersNamed("oldbo")).containsExactly(bo);
        assertThat(log.playersNamed("BO")).containsExactly(bo);
        assertThat(log.playersNamed("nobody")).isEmpty();
        assertThat(log.search(ChatLog.Search.everything().by(java.util.Set.of(bo)), 50, 0))
                .extracting(ChatLog.Line::name).containsExactly("Bo", "OldBo");
    }

    @Test
    @DisplayName("a database from before the search keeps its lines and gains the name index")
    void upgradesAnOldDatabase() {
        database.close();
        java.nio.file.Path old = folder.resolve("old.db");
        de.raindancer.core.data.sql.Schema before = new de.raindancer.core.data.sql.Schema(
                ChatLog.SCHEMA.steps().subList(0, 3));
        Database first = Database.open(old, before, () -> false);
        ChatLog writer = new ChatLog(first, clock::get);
        writer.record(bo, "Bo", ChatLog.PUBLIC, "kept too");
        writer.flush();
        first.close();
        database = Database.open(old, ChatLog.SCHEMA, () -> false);
        log = new ChatLog(database, clock::get);
        assertThat(database.version()).isEqualTo(ChatLog.SCHEMA.size());
        assertThat(log.playersNamed("bo")).containsExactly(bo);
    }

    @Test
    @DisplayName("lines said but not yet written are found too")
    void pendingIsSearched() {
        log.record(bo, "Bo", ChatLog.PUBLIC, "just now");
        assertThat(log.count(ChatLog.Search.everything().containing("just"))).isEqualTo(1);
    }

    @Test
    @DisplayName("every name somebody chatted under, the most recent first, each once")
    void namesOf() {
        log.record(bo, "OldBo", ChatLog.PUBLIC, "one");
        clock.addAndGet(1000);
        log.record(bo, "MiddleBo", ChatLog.PUBLIC, "two");
        clock.addAndGet(1000);
        log.record(bo, "Bo", ChatLog.PUBLIC, "three");
        clock.addAndGet(1000);
        log.record(bo, "OldBo", ChatLog.PUBLIC, "back to the old one");
        log.record(cy, "Cy", ChatLog.PUBLIC, "hi");
        assertThat(log.namesOf(bo)).containsExactly("OldBo", "Bo", "MiddleBo");
        assertThat(log.namesOf(UUID.randomUUID())).isEmpty();
    }
}
