package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.RuntimeAgentEvidenceDto;
import io.github.jdubois.bootui.core.dto.RuntimeAgentEvidenceStoreDto;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The agent evidence contract ({@code docs/PLAN-v2.md} §5.17, M5-11): one projection for the evidence the BootUI agent
 * keeps outside the runtime journal, such as Code Paths' request and route trees and Code Inventory's first calls, which
 * applies in one place what the journal applies to its own events.
 *
 * <ul>
 *   <li><b>Source-panel visibility.</b> A {@link Read} resolves, once, whether the store's own panel and HTTP Exchanges,
 *       which owns requests and routes, are visible; a store serves a read, its cache key, and the reason it gives from
 *       that one value, so a disabled panel's evidence is neither shown nor counted. A predicate that fails reads as
 *       hidden.</li>
 *   <li><b>Clear recording.</b> Added to the journal as a {@link JournalListener}, it clears every store when the journal
 *       clears, by <b>Clear recording</b> or <b>Free BootUI memory</b>, under the journal's lock, so the clear count a
 *       cached projection reads covers both. Each store drops what it recorded before the clear, including what was
 *       still queued, and keeps its counts since the claim.</li>
 *   <li><b>Export rules.</b> The panels, Runtime Insights' <b>Export JSON</b> and <b>Copy for AI</b>, MCP tools, and the
 *       CLI only carry what a store's reads return under a {@link Read}; no surface serializes a store, and nothing here
 *       is written to disk.</li>
 *   <li><b>Memory accounting.</b> Each store reports its estimated bytes beside the journal's, and sizes its bounds from
 *       {@link #scale()}: a configured byte bound below the {@linkplain Part parts' ceilings} shrinks the scalable parts
 *       proportionally.</li>
 * </ul>
 *
 * <p>One instance per application context, never shared through a static, so it pins no class loader across a DevTools
 * restart or a Quarkus live reload.
 */
public final class AgentEvidence implements JournalListener {

    private static final Logger log = Logger.getLogger(AgentEvidence.class.getName());

    /** The smallest share of their ceilings the scalable parts keep, whatever the configured bound. */
    static final double MIN_SCALE = 0.05;

    /**
     * What the stores hold, with each part's estimated ceiling in bytes: today's caps, so the default bound changes
     * nothing. A scalable part shrinks with a configured bound below the sum; a fixed part is bounded elsewhere, such as
     * by the agent's method limit, and is only counted. A new store declares its parts here.
     */
    public enum Part {
        /**
         * Code Paths' request trees: 131,072 kept nodes at 48 bytes, 512 open trees of up to 512 nodes, 1,024 trees'
         * own 512 bytes (recent, waiting, and open), 4,096 tombstones at 96 bytes, and the routes of the 16,384 trees
         * last handed over, at 96 bytes, which late fragments amend (M5-7a).
         */
        CODE_PATHS_REQUEST_TREES(131_072L * 48 + 512L * 512 * 48 + 1_024L * 512 + 4_096L * 96 + 16_384L * 96, true),
        /**
         * Code Paths' route trees: 100,000 nodes at about 264 bytes, 500 routes at about 2.5 KB, and 200,000 executed
         * methods at 32 bytes, counted per route (M5-7a).
         */
        CODE_PATHS_ROUTE_TREES(100_000L * 264 + 500L * 2_560 + 200_000L * 32, true),
        /**
         * Code Inventory's first calls and loads: 2^18 method slots at about 21 bytes, the agent's method limit, 16,384
         * first loads at 160 bytes, 8,192 pending lookups and 4,096 request routes at 64 bytes, and 4,096 route names at
         * about 192 bytes.
         */
        CODE_INVENTORY_RECORDS((1L << 18) * 21 + 16_384L * 160 + 8_192L * 64 + 4_096L * 64 + 4_096L * 192, false),
        /**
         * Method probes (M5-8): 25 probes kept per run at about 512 bytes, each with at most 20 recorded invocations at
         * about 320 bytes, bounded by the probes' own caps.
         */
        METHOD_PROBES(25L * 512 + 25L * 20 * 320, false),
        /**
         * Side Effects' rows (M5-5a): 2,000 rows and an Other row per sensor at about 640 bytes, 10,000 records waiting
         * for their request's route or execution's label at about 320 bytes, 4,096 such names at about 96 bytes, and,
         * kept through a clear, 8,192 resolved strings at about 64 bytes and 2,000 method labels at about 96 bytes.
         */
        SIDE_EFFECTS_ROWS((2_000L + 9) * 640 + 10_000L * 320 + 4_096L * 96 + 8_192L * 64 + 2_000L * 96, true);

        private final long ceilingBytes;
        private final boolean scalable;

        Part(long ceilingBytes, boolean scalable) {
            this.ceilingBytes = ceilingBytes;
            this.scalable = scalable;
        }

        public long ceilingBytes() {
            return ceilingBytes;
        }

        public boolean scalable() {
            return scalable;
        }
    }

    /** The default bound: the sum of every part's ceiling. */
    public static long defaultMaxBytes() {
        long sum = 0;
        for (Part part : Part.values()) {
            sum += part.ceilingBytes();
        }
        return sum;
    }

    /** One store of agent evidence kept outside the journal, owned by one panel. */
    public interface Store {

        /** A stable id, such as {@code code-paths}. */
        String id();

        /** The panel that owns the store's evidence. */
        String panel();

        /** The panel's title, for the reason a read gives while it is hidden. */
        String title();

        /** Why the store records nothing for this application, as without the agent, else {@code null}. */
        String unavailableReason();

        /** What the store holds now, read from published counters, never waiting on a read in progress. */
        Usage usage();

        /**
         * Drops the evidence recorded before the clear, including what is still queued, keeping counts since the claim.
         *
         * @param epochMillis the clear's time, on the clock records carry in epoch milliseconds
         * @return what was dropped, as a short phrase such as {@code 12 request trees}, or {@code null} for nothing
         */
        String clear(long epochMillis);
    }

    /**
     * Every name a store may report a count under, each reviewed as metadata for the journal status, which exports it:
     * a new one fails {@link Usage} until it is listed here and reviewed.
     */
    public static final java.util.Set<String> COUNTS = java.util.Set.of(
            "requestTrees",
            "routes",
            "routeNodes",
            "indexBytes",
            "firstCalls",
            "firstCallsWithRequest",
            "firstLoads",
            "probes",
            "probeHits",
            "sideEffectRows",
            "sideEffectsWaiting");

    /**
     * What a store holds.
     *
     * @param retainedBytes the estimated bytes of the evidence it retains
     * @param maxBytes the most it holds under the configured bound
     * @param counts what it holds, by name, such as {@code requestTrees}, and the bytes of what it keeps beside the
     *     evidence, never cleared, such as {@code indexBytes} for the method keys
     */
    public record Usage(long retainedBytes, long maxBytes, Map<String, Long> counts) {

        public Usage {
            counts = counts == null ? Map.of() : Map.copyOf(counts);
            for (String name : counts.keySet()) {
                if (!COUNTS.contains(name)) {
                    throw new IllegalArgumentException("Unreviewed agent evidence count: " + name);
                }
            }
        }
    }

    /**
     * One read of the panels owning a store's evidence, resolved once and passed to every part of a projection.
     *
     * @param panel the store's own panel
     * @param shown whether that panel is visible
     * @param requests whether HTTP Exchanges, which owns requests and routes, is visible too
     * @param hiddenReason why nothing is shown while the store's panel is hidden, else {@code null}
     */
    public record Read(String panel, boolean shown, boolean requests, String hiddenReason) {

        /** Everything visible: for tests and a stack that gates nothing. */
        public static Read open(String panel) {
            return new Read(panel, true, true, null);
        }

        /**
         * The read of {@code panel}, titled {@code title}, under {@code visible}, which is asked once for each panel; a
         * predicate that throws reads as hidden.
         */
        public static Read of(String panel, String title, Predicate<String> visible) {
            boolean shown = test(visible, panel);
            boolean requests = shown && test(visible, BootUiPanels.HTTP_EXCHANGES);
            return new Read(panel, shown, requests, shown ? null : "The " + title + " panel is disabled.");
        }

        private static boolean test(Predicate<String> visible, String panel) {
            if (visible == null) {
                return true;
            }
            try {
                return visible.test(panel);
            } catch (RuntimeException ex) {
                return false;
            }
        }

        /** A number that differs between reads that would show something else, for cache keys and fingerprints. */
        public long key() {
            return (shown ? 2 : 0) + (requests ? 1 : 0);
        }
    }

    private final Predicate<String> visible;
    private final long maxBytes;
    private final LongSupplier clock;
    private final Map<String, Store> stores = new LinkedHashMap<>();
    private volatile String lastCleared;
    private volatile long clears;

    /**
     * @param visible whether a panel, by its id, is visible: enabled and available on this stack; {@code null} shows
     *     every panel
     * @param maxBytes the configured bound, or {@code null} or a non-positive value for {@link #defaultMaxBytes()}
     */
    public AgentEvidence(Predicate<String> visible, Long maxBytes) {
        this(visible, maxBytes, System::currentTimeMillis);
    }

    AgentEvidence(Predicate<String> visible, Long maxBytes, LongSupplier clock) {
        this.visible = visible;
        this.maxBytes = maxBytes == null || maxBytes <= 0 ? defaultMaxBytes() : maxBytes;
        this.clock = clock;
    }

    /** A new projection showing every panel under the default bound, for tests; never a shared instance. */
    public static AgentEvidence open() {
        return new AgentEvidence(null, null);
    }

    /**
     * Adds this projection to {@code journal}'s listeners, once however often it is called, so every clear of the journal
     * clears the stores too.
     */
    public void listenTo(RuntimeJournal journal) {
        if (journal != null && !journal.notifies(this)) {
            journal.addListener(this);
        }
    }

    /** Registers {@code store}, replacing a store with the same id. */
    public synchronized void register(Store store) {
        if (store != null) {
            stores.put(store.id(), store);
        }
    }

    /** The read of {@code store}'s panels now. */
    public Read read(Store store) {
        return Read.of(store.panel(), store.title(), visible);
    }

    /** The configured bound, or the default. */
    public long maxBytes() {
        return maxBytes;
    }

    /**
     * The share of their ceilings the scalable parts may hold: 1 under the default bound, less under a smaller one, never
     * below {@value #MIN_SCALE}.
     */
    public double scale() {
        long fixed = 0;
        long scalable = 0;
        for (Part part : Part.values()) {
            if (part.scalable()) {
                scalable += part.ceilingBytes();
            } else {
                fixed += part.ceilingBytes();
            }
        }
        double share = (double) (maxBytes - fixed) / scalable;
        return Math.max(MIN_SCALE, Math.min(1.0, share));
    }

    /** {@code value} scaled by {@link #scale()}, at least {@code floor}. */
    public int scaled(int value, int floor) {
        return (int) Math.max(floor, Math.min(value, Math.round(value * scale())));
    }

    /** How many times the stores were cleared. */
    public long clears() {
        return clears;
    }

    /** What the latest clear dropped, as a sentence fragment, or {@code null}. */
    public String lastCleared() {
        return lastCleared;
    }

    /** Clears every store, each on its own: a store that fails is logged and skipped. */
    @Override
    public void onClear() {
        clear();
    }

    /** {@link #onClear()}, returning what was dropped; {@code null} when nothing was. */
    public String clear() {
        List<Store> current;
        synchronized (this) {
            current = new ArrayList<>(stores.values());
        }
        long now = clock.getAsLong();
        List<String> dropped = new ArrayList<>();
        for (Store store : current) {
            try {
                String phrase = store.clear(now);
                if (phrase != null && !phrase.isBlank() && read(store).shown()) {
                    dropped.add(phrase);
                }
            } catch (RuntimeException ex) {
                log.log(Level.WARNING, "BootUI could not clear the agent evidence of " + store.id(), ex);
            }
        }
        String summary = dropped.isEmpty() ? null : String.join(", ", dropped);
        synchronized (this) {
            clears++;
            lastCleared = summary;
        }
        return summary;
    }

    /** The journal's {@link JournalListener} callback for batches: the stores read the journal themselves. */
    @Override
    public void onEntries(List<JournalEntry> entries) {}

    /**
     * What the stores hold, for the journal's status: the total includes hidden stores, whose own rows say only that
     * they are hidden, and the bound is the sum of what each store holds at most under the configured one, which a
     * store's floor may exceed; {@code null} when no store records for this application, as without the agent.
     */
    public RuntimeAgentEvidenceDto status() {
        List<Store> current;
        synchronized (this) {
            current = new ArrayList<>(stores.values());
        }
        long total = 0;
        long bound = 0;
        List<RuntimeAgentEvidenceStoreDto> rows = new ArrayList<>();
        for (Store store : current) {
            String unavailable;
            try {
                unavailable = store.unavailableReason();
            } catch (RuntimeException ex) {
                unavailable = null;
            }
            if (unavailable != null) {
                continue;
            }
            Usage usage;
            try {
                usage = store.usage();
            } catch (RuntimeException ex) {
                usage = null;
            }
            if (usage != null) {
                total += usage.retainedBytes();
                bound += usage.maxBytes();
            }
            Read read = read(store);
            if (!read.shown()) {
                rows.add(new RuntimeAgentEvidenceStoreDto(
                        store.id(), store.panel(), false, read.hiddenReason(), null, null, Map.of()));
            } else if (usage == null) {
                rows.add(new RuntimeAgentEvidenceStoreDto(
                        store.id(), store.panel(), true, "Its usage could not be read.", null, null, Map.of()));
            } else {
                rows.add(new RuntimeAgentEvidenceStoreDto(
                        store.id(),
                        store.panel(),
                        true,
                        null,
                        usage.retainedBytes(),
                        usage.maxBytes(),
                        usage.counts()));
            }
        }
        return rows.isEmpty() ? null : new RuntimeAgentEvidenceDto(total, bound, rows);
    }
}
