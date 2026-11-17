package de.raindancer.core.ui.statue;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Where every statue of this server stands, in Core's {@code statues.yml} (a row in Core's database once it runs):
 * {@code spots}, one line per statue, and {@code adopted}, the plugins whose statues were taken over already.
 * Held in memory for reading from any thread; every change reads the document, changes it and writes it as one
 * step, off the server thread.
 */
public final class StatueSpots {

    private static final String SPOTS = "spots";
    private static final String ADOPTED = "adopted";

    /**
     * What taking over another plugin's statues did.
     *
     * @param already    they had been taken over before; nothing changed
     * @param taken      statues added
     * @param unreadable lines that were neither a statue nor an old podium
     * @param saved      whether it was written; false changes nothing, and the next start tries again
     */
    public record Adoption(boolean already, int taken, int unreadable, boolean saved) {
    }

    private final YamlStore store;
    private final StatueRule rule = new StatueRule();
    private volatile List<StatueSpot> spots = List.of();
    private volatile Set<String> adopted = Set.of();

    public StatueSpots(YamlStore store) {
        this.store = store;
    }

    /** Reads the document. Blocks. False, keeping what is held, when it could not be read. */
    public synchronized boolean load() {
        Optional<YamlConfiguration> read = store.readIfReadable();
        read.ifPresent(this::hold);
        return read.isPresent();
    }

    /** Every statue, as last read or written. */
    public List<StatueSpot> all() {
        return spots;
    }

    public boolean adopted(String source) {
        return adopted.contains(source);
    }

    /**
     * Changes the statues and writes them. Blocks. False, and nothing changed, when the document could not be read
     * or written.
     */
    public synchronized boolean change(UnaryOperator<List<StatueSpot>> change) {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            return false;
        }
        List<StatueSpot> changed = List.copyOf(change.apply(new ArrayList<>(rule.read(read.get().getStringList(SPOTS)))));
        Set<String> sources = new LinkedHashSet<>(read.get().getStringList(ADOPTED));
        return write(changed, sources);
    }

    /**
     * Takes over the statues another plugin kept, once: {@code lines} as it wrote them, an old podium as its three
     * statues. A statue standing here already is not added twice. After this the source is remembered, so taking
     * over again changes nothing; a statue removed since does not come back. With nothing readable in {@code lines}
     * nothing is remembered. Blocks.
     */
    public synchronized Adoption adopt(String source, List<String> lines) {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            return new Adoption(false, 0, 0, false);
        }
        Set<String> sources = new LinkedHashSet<>(read.get().getStringList(ADOPTED));
        List<StatueSpot> now = new ArrayList<>(rule.read(read.get().getStringList(SPOTS)));
        if (sources.contains(source)) {
            hold(read.get());
            return new Adoption(true, 0, 0, true);
        }
        Set<String> keys = new LinkedHashSet<>();
        now.forEach(spot -> keys.add(spot.key()));
        int taken = 0;
        int unreadable = 0;
        boolean anyReadable = false;
        for (String line : lines == null ? List.<String>of() : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (!rule.readable(line)) {
                unreadable++;
                continue;
            }
            anyReadable = true;
            for (StatueSpot spot : rule.read(List.of(line))) {
                if (keys.add(spot.key())) {
                    now.add(spot);
                    taken++;
                }
            }
        }
        if (!anyReadable) {
            // Nothing readable to take over (settings that failed to load read as empty): not remembered, so the
            // statues are still taken over on a start that reads them.
            return new Adoption(false, 0, unreadable, true);
        }
        sources.add(source);
        boolean saved = write(now, sources);
        return new Adoption(false, saved ? taken : 0, unreadable, saved);
    }

    private boolean write(List<StatueSpot> changed, Set<String> sources) {
        List<String> lines = changed.stream().map(StatueSpot::written).toList();
        List<String> from = List.copyOf(sources);
        boolean written = store.update(yaml -> {
            yaml.set(SPOTS, lines);
            yaml.set(ADOPTED, from);
        });
        if (written) {
            spots = List.copyOf(changed);
            adopted = Set.copyOf(sources);
        }
        return written;
    }

    private void hold(YamlConfiguration yaml) {
        spots = List.copyOf(rule.read(yaml.getStringList(SPOTS)));
        adopted = Set.copyOf(yaml.getStringList(ADOPTED));
    }
}
