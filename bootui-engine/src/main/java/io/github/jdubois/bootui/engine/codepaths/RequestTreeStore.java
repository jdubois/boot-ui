package io.github.jdubois.bootui.engine.codepaths;

import io.github.jdubois.bootui.engine.correlation.HandoffWindow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The request trees of one run ({@code docs/PLAN-v2.md} §5.14, M5-4a), never in the journal: fragments merge into an
 * open tree per request until it settles, when the calls its request recorded with a code-paths stamp are attached under
 * the nodes that issued them (M5-4c), {@value #SETTLE_NANOS} ns after its last fragment or, when more than
 * {@value #MAX_OPEN} are open, with the eldest quarter in one journal read (M5-13); a settled tree is kept among the
 * {@value #RECENT} most recent, and as an exemplar of its route when it is among the {@value #EXEMPLARS} slowest or the
 * {@value #EXEMPLARS} latest failed, for at most
 * {@value #MAX_ROUTES} routes. A fragment arriving after its request settled, as a late handoff's, merges into the kept
 * recent tree, and into its exemplar too, or into the tree still waiting for its request's exchange, even one no longer
 * among the recent ones; one arriving after its tree left the recent ones but is still an exemplar is dropped and
 * counted, never opening a partial duplicate. A settled tree keeps no builder, only its tree and the spans a late
 * fragment needs ({@link RequestTreeBuilder.Spans}), so every tree kept counts toward {@value #MAX_KEPT_NODES} nodes for
 * what it holds, past which the oldest recent trees, then the oldest routes' exemplars, are forgotten. A tree waiting for
 * its exchange also keeps, until its calls are attached, the nodes of every fragment its builder remembered, at most
 * {@value RequestTreeBuilder#MAX_HOLDERS} fragments as an open tree's, so its executors' calls and its own past the
 * {@value RequestTreeBuilder#MAX_KEPT_HOLDERS} fragments a settled tree keeps still find their node; waiting, it is
 * looked up only in the journal events recorded since the last look ({@link RequestOutcomeReader#exchangesAfter}).
 *
 * <p>Both bounds shrink with a configured agent evidence bound (M5-11, {@code AgentEvidence#scale}). <b>Clear
 * recording</b> ({@link #clear}) drops every tree, open, kept, waiting, or exemplar, and any fragment flushed before it,
 * still queued or not; a request that lost a fragment to the clear is left out whole, by a tombstone of its key (at
 * most {@value #MAX_TOMBSTONES}), never kept partial. Not thread-safe.
 */
public final class RequestTreeStore {

    public static final long SETTLE_NANOS = 2_000_000_000L;
    public static final int MAX_OPEN = 512;

    /**
     * The eldest open trees settled together, in one journal read, when more than {@link #MAX_OPEN} are open: a
     * quarter of the most open trees, so {@code MAX_OPEN - OVERFLOW_BATCH} younger ones stay open.
     */
    public static final int OVERFLOW_BATCH = MAX_OPEN / 4;

    public static final int RECENT = 256;
    public static final int EXEMPLARS = 3;
    public static final int MAX_ROUTES = 100;
    public static final int MAX_KEPT_NODES = 131_072;

    /** The most cleared request keys remembered, the oldest forgotten first. */
    public static final int MAX_TOMBSTONES = 4_096;

    /** The estimated bytes of one tree node, kept or open: its seven arrays' entries. */
    public static final int NODE_BYTES = 48;

    /** The estimated bytes of one tree beyond its nodes: the record, its arrays' headers, and its spans. */
    public static final int TREE_BYTES = 512;

    /** The estimated bytes of one tombstone. */
    public static final int TOMBSTONE_BYTES = 96;

    /**
     * How long a settled tree waits for its request's exchange, as a slow reactive response's, before it is completed
     * with an unknown route: the executor handoff window's default.
     */
    public static final long RESOLVE_NANOS = HandoffWindow.DEFAULT_MAX_HANDOFF.toNanos();

    /** How often, at first, the waiting trees are looked up again in the journal. */
    public static final long RESOLVE_EVERY_NANOS = 500_000_000L;

    /** How often, at most, as the eldest waiting tree has waited four times as long. */
    public static final long MAX_RESOLVE_EVERY_NANOS = 10_000_000_000L;

    /** The most settled trees waiting for their exchange; past it, the eldest is completed with an unknown route. */
    public static final int MAX_UNRESOLVED = 256;

    private final RequestOutcomeReader outcomes;
    private final int maxOpen;
    private final int overflowBatch;
    private final int maxKeptNodes;
    private final LinkedHashMap<String, Boolean> tombstones = new LinkedHashMap<>();
    private boolean cleared;
    private long clearedBeforeNanos;
    private long clearedTrees;
    private long clearedFragments;
    private long openNodes;
    private BiConsumer<RequestTree, RequestOutcome> settledListener = (tree, outcome) -> {};
    private final LinkedHashMap<String, Open> open = new LinkedHashMap<>();
    private final LinkedHashMap<String, Kept> recent = new LinkedHashMap<>();
    private final LinkedHashMap<String, Exemplars> routes = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, Pending> unresolved = new LinkedHashMap<>();
    private long nextResolve = Long.MIN_VALUE;
    /** The journal position the waiting trees were last looked up to: what is newer is read next time. */
    private long watermark = RequestOutcomeReader.NONE;

    private long lateNamed;
    private long neverNamed;
    private long settled;
    private long late;
    private long lateDropped;
    private long forgotten;
    private int keptNodes;

    /** @param outcomes names the route and outcome of settling requests by request id, such as {@link JournalRequestOutcomes#of} */
    public RequestTreeStore(Function<Set<String>, Map<String, RequestOutcome>> outcomes) {
        this(outcomes, MAX_OPEN, MAX_KEPT_NODES);
    }

    /**
     * @param outcomes as {@link #RequestTreeStore(Function)}
     * @param maxOpen the most open trees, at most {@value #MAX_OPEN}
     * @param maxKeptNodes the most nodes kept, at most {@value #MAX_KEPT_NODES}
     */
    public RequestTreeStore(
            Function<Set<String>, Map<String, RequestOutcome>> outcomes, int maxOpen, int maxKeptNodes) {
        this.outcomes = RequestOutcomeReader.of(outcomes);
        this.maxOpen = Math.max(1, Math.min(MAX_OPEN, maxOpen));
        this.overflowBatch = Math.max(1, this.maxOpen / 4);
        this.maxKeptNodes = Math.max(RequestTreeBuilder.MAX_NODES, Math.min(MAX_KEPT_NODES, maxKeptNodes));
    }

    /**
     * Installs what each tree is handed to once it settles, with its outcome, such as the run's route trees
     * ({@code M5-4b}). A fragment merged after its tree settled is not handed over again.
     */
    public void onSettled(BiConsumer<RequestTree, RequestOutcome> listener) {
        this.settledListener = listener == null ? (tree, outcome) -> {} : listener;
    }

    /** Merges {@code fragment} into its request's tree. */
    public void add(CodePathFragment fragment, long nowNanos) {
        String key = RequestTreeBuilder.keyOf(fragment);
        if (cleared && (fragment.endNanos() - clearedBeforeNanos < 0 || tombstones.containsKey(key))) {
            // Flushed before the clear, though drained after it, or of a request that lost such a fragment: the
            // request is left out whole, never kept partial, though a fragment of it flushed after the clear was
            // drained first. A tree already merged into its route tree stays there.
            tombstone(key);
            forget(key);
            clearedFragments++;
            return;
        }
        Pending pending = unresolved.get(key);
        Kept kept = pending != null ? pending.kept : recent.get(key);
        if (kept != null && !open.containsKey(key)) {
            // Settled: merges into its tree, even one waiting for its exchange that left the recent ones.
            late++;
            RequestTreeBuilder builder = pending != null
                    ? RequestTreeBuilder.resume(kept.tree, kept.spans, pending.holders)
                    : RequestTreeBuilder.resume(kept.tree, kept.spans);
            builder.add(fragment);
            RequestTree merged = builder.finish();
            if (recent.get(key) == kept) {
                keptNodes += merged.nodeCount() - kept.tree.nodeCount();
            }
            kept.tree = merged;
            kept.spans = builder.spans();
            if (pending != null) {
                pending.holders = builder.allHolders();
            }
            Exemplars exemplars = routes.get(kept.outcome.route());
            if (exemplars != null) {
                keptNodes += exemplars.replace(merged);
            }
            trim();
            return;
        }
        Open entry = open.get(key);
        if (entry == null && exemplar(key)) {
            // Settled and no longer recent: merging only the late fragment would open a partial duplicate.
            lateDropped++;
            return;
        }
        if (entry == null) {
            String requestId = fragment.request() == 0L ? null : CodePathFragment.hex(fragment.request());
            entry = new Open(new RequestTreeBuilder(key, requestId));
            open.put(key, entry);
        }
        entry.builder.add(fragment);
        entry.lastNanos = nowNanos;
        // An upper bound on the builder's nodes, which merges fragments by method.
        int nodes = Math.min(RequestTreeBuilder.MAX_NODES, entry.nodes + fragment.parent().length);
        openNodes += nodes - entry.nodes;
        entry.nodes = nodes;
        if (open.size() > maxOpen) {
            // One journal read for the eldest batch, not one per request (M5-13): above maxOpen requests a settle
            // period, settling one tree per fragment read the whole journal for every request.
            List<String> eldest = new ArrayList<>(overflowBatch);
            Iterator<String> keys = open.keySet().iterator();
            while (keys.hasNext() && eldest.size() < overflowBatch) {
                eldest.add(keys.next());
            }
            settle(eldest, nowNanos, false);
        }
    }

    /**
     * Settles the trees quiet for {@link #SETTLE_NANOS}, and names the settled trees still waiting for their request's
     * exchange, at most every {@value #RESOLVE_EVERY_NANOS} ns, backing off to {@value #MAX_RESOLVE_EVERY_NANOS} ns as
     * they wait; returns how many trees settled.
     */
    public int settle(long nowNanos) {
        List<String> quiet = new ArrayList<>();
        for (Map.Entry<String, Open> entry : open.entrySet()) {
            if (nowNanos - entry.getValue().lastNanos >= SETTLE_NANOS) {
                quiet.add(entry.getKey());
            }
        }
        settle(quiet, nowNanos, false);
        resolve(nowNanos, false);
        return quiet.size();
    }

    /**
     * Settles every open tree, as when the run ends or a read needs them now: a tree whose request's exchange the journal
     * has not recorded is kept with an unknown route, as is every tree still waiting for it.
     */
    public void settleAll() {
        settle(new ArrayList<>(open.keySet()), Long.MAX_VALUE, true);
        resolve(Long.MAX_VALUE, true);
    }

    /**
     * Settles {@code keys}' trees. A request's tree whose exchange the journal has not recorded yet, as a slow reactive
     * response's, settles without its route, calls, or exemplar, and waits until the exchange arrives
     * ({@link #resolve}), at most {@link #RESOLVE_NANOS}; with {@code finalOutcome}, it is named now, unknown or not.
     */
    private void settle(List<String> keys, long nowNanos, boolean finalOutcome) {
        if (keys.isEmpty()) {
            return;
        }
        Set<String> requestIds = new HashSet<>();
        for (String key : keys) {
            String requestId = open.get(key).builder.requestId();
            if (requestId != null) {
                requestIds.add(requestId);
            }
        }
        long mark = RequestOutcomeReader.NONE;
        Map<String, RequestOutcome> named;
        try {
            // Taken before the read: a tree that waits is looked up next in what the journal records after it.
            mark = requestIds.isEmpty() ? RequestOutcomeReader.NONE : outcomes.watermark();
            named = requestIds.isEmpty() ? Map.of() : outcomes.apply(requestIds);
        } catch (RuntimeException ex) {
            named = Map.of();
        }
        for (String key : keys) {
            Open entry = removeOpen(key);
            String requestId = entry.builder.requestId();
            RequestOutcome outcome = requestId == null ? null : named.get(requestId);
            RequestOutcome resolved = outcome == null ? RequestOutcome.UNKNOWN : outcome;
            boolean waits = !finalOutcome && requestId != null && !named(resolved);
            if (!waits) {
                // The calls the request recorded with a stamp go under the node that issued them (M5-4c).
                entry.builder.attach(resolved.calls(), resolved.unplaced());
            }
            RequestTree tree = entry.builder.finish();
            // The builder is released here: only the tree and its spans are kept.
            Kept kept = new Kept(tree, entry.builder.spans(), resolved.withoutCalls());
            keep(kept, !waits);
            settled++;
            if (waits) {
                if (unresolved.isEmpty()) {
                    watermark = mark;
                }
                // Every fragment's nodes stay known until its calls are attached, as its executors' (M52-05).
                unresolved.put(key, new Pending(kept, nowNanos, entry.builder.allHolders(), resolved));
                while (unresolved.size() > MAX_UNRESOLVED) {
                    Map.Entry<String, Pending> eldest =
                            unresolved.entrySet().iterator().next();
                    unresolved.remove(eldest.getKey());
                    // With the calls read when it settled, without another read.
                    complete(eldest.getValue(), eldest.getValue().settledOutcome);
                    neverNamed++;
                }
            } else {
                announce(kept);
            }
        }
        trim();
    }

    /** Whether the journal named the request's route: its exchange was recorded. */
    private static boolean named(RequestOutcome outcome) {
        return !RequestOutcome.UNKNOWN_ROUTE.equals(outcome.route());
    }

    /**
     * Names the settled trees waiting for their request's exchange, once the journal recorded it: each gets its route,
     * its calls, and its exemplar, and joins its route tree exactly once. One still unnamed after {@link #RESOLVE_NANOS},
     * or with {@code giveUp}, is completed with an unknown route and the calls the journal holds. Waiting, the trees are
     * looked up only in the events recorded since the last look, when the reader can ({@link
     * RequestOutcomeReader#exchangesAfter}); the journal is read in full only for the trees it names or gives up on.
     */
    private void resolve(long nowNanos, boolean giveUp) {
        if (unresolved.isEmpty() || (!giveUp && nowNanos < nextResolve)) {
            return;
        }
        // Backs off as the eldest waits: a slow response is named within a second, while a request whose exchange never
        // comes costs at most a journal read every few seconds.
        if (!giveUp) {
            long waited = nowNanos - unresolved.values().iterator().next().sinceNanos;
            nextResolve = nowNanos + Math.max(RESOLVE_EVERY_NANOS, Math.min(MAX_RESOLVE_EVERY_NANOS, waited / 4));
        }
        Set<String> requestIds = new HashSet<>();
        for (Pending pending : unresolved.values()) {
            requestIds.add(pending.kept.tree.requestId());
        }
        RequestOutcomeReader.Exchanges seen = null;
        if (!giveUp) {
            try {
                seen = outcomes.exchangesAfter(requestIds, watermark);
            } catch (RuntimeException ex) {
                seen = null;
            }
        }
        Set<String> reading;
        if (seen == null) {
            reading = requestIds;
        } else {
            watermark = seen.watermark();
            reading = new HashSet<>(seen.named());
            reading.retainAll(requestIds);
            for (Pending pending : unresolved.values()) {
                if (nowNanos - pending.sinceNanos >= RESOLVE_NANOS) {
                    reading.add(pending.kept.tree.requestId());
                }
            }
            if (reading.isEmpty()) {
                return;
            }
        }
        Map<String, RequestOutcome> named;
        try {
            named = outcomes.apply(reading);
        } catch (RuntimeException ex) {
            named = Map.of();
        }
        Iterator<Map.Entry<String, Pending>> entries = unresolved.entrySet().iterator();
        while (entries.hasNext()) {
            Pending pending = entries.next().getValue();
            if (!reading.contains(pending.kept.tree.requestId())) {
                continue;
            }
            RequestOutcome outcome = named.get(pending.kept.tree.requestId());
            if (outcome != null && named(outcome)) {
                entries.remove();
                lateNamed++;
                complete(pending, outcome);
            } else if (giveUp || nowNanos - pending.sinceNanos >= RESOLVE_NANOS) {
                entries.remove();
                neverNamed++;
                complete(pending, outcome == null ? pending.settledOutcome : outcome);
            }
        }
        trim();
    }

    /** Attaches the request's calls to a waiting tree, names its route, keeps its exemplar, and hands it over once. */
    private void complete(Pending pending, RequestOutcome outcome) {
        Kept kept = pending.kept;
        RequestTreeBuilder builder = RequestTreeBuilder.resume(kept.tree, kept.spans, pending.holders);
        builder.attach(outcome.calls(), outcome.unplaced());
        pending.holders = Map.of();
        kept.tree = builder.finish();
        kept.spans = builder.spans();
        kept.outcome = outcome.withoutCalls();
        if (recent.get(kept.tree.key()) == kept) {
            offerExemplar(kept);
        }
        announce(kept);
    }

    private void announce(Kept kept) {
        try {
            settledListener.accept(kept.tree, kept.outcome);
        } catch (RuntimeException ex) {
            // A listener never loses the tree the store keeps.
        }
    }

    private void keep(Kept kept, boolean exemplar) {
        Kept previous = recent.put(kept.tree.key(), kept);
        if (previous != null) {
            keptNodes -= previous.tree.nodeCount();
        }
        keptNodes += kept.tree.nodeCount();
        while (recent.size() > RECENT) {
            forgetEldestRecent();
        }
        if (exemplar) {
            offerExemplar(kept);
        }
    }

    private void offerExemplar(Kept kept) {
        Exemplars exemplars = routes.get(kept.outcome.route());
        if (exemplars == null) {
            exemplars = new Exemplars();
            routes.put(kept.outcome.route(), exemplars);
            while (routes.size() > MAX_ROUTES) {
                forgetEldestRoute();
            }
        }
        keptNodes += exemplars.offer(kept);
    }

    private void trim() {
        while (keptNodes > maxKeptNodes && !recent.isEmpty()) {
            forgetEldestRecent();
        }
        while (keptNodes > maxKeptNodes && !routes.isEmpty()) {
            forgetEldestRoute();
        }
    }

    private void forgetEldestRecent() {
        Iterator<Kept> eldest = recent.values().iterator();
        keptNodes -= eldest.next().tree.nodeCount();
        eldest.remove();
        forgotten++;
    }

    private void forgetEldestRoute() {
        Iterator<Exemplars> eldest = routes.values().iterator();
        keptNodes -= eldest.next().nodes();
        eldest.remove();
        forgotten++;
    }

    private boolean exemplar(String key) {
        for (Exemplars exemplars : routes.values()) {
            if (exemplars.find(key) != null) {
                return true;
            }
        }
        return false;
    }

    /** The tree of the request with key {@code key}, open, kept, or waiting for its exchange, or {@code null}. */
    public RequestTree tree(String key) {
        Open entry = open.get(key);
        if (entry != null) {
            return entry.builder.build();
        }
        Kept kept = recent.get(key);
        if (kept == null && unresolved.containsKey(key)) {
            // Waiting for its exchange, though no longer among the recent ones.
            kept = unresolved.get(key).kept;
        }
        if (kept != null) {
            return kept.tree;
        }
        for (Exemplars exemplars : routes.values()) {
            RequestTree tree = exemplars.find(key);
            if (tree != null) {
                return tree;
            }
        }
        return null;
    }

    /** What the journal said about the settled request with key {@code key}, or {@code null} when none is kept. */
    public RequestOutcome outcome(String key) {
        Kept kept = recent.get(key);
        if (kept == null && unresolved.containsKey(key)) {
            kept = unresolved.get(key).kept;
        }
        if (kept != null) {
            return kept.outcome;
        }
        for (Map.Entry<String, Exemplars> route : routes.entrySet()) {
            if (route.getValue().find(key) != null) {
                return new RequestOutcome(route.getKey(), 0, false);
            }
        }
        return null;
    }

    /** The settled trees kept as recent, newest first. */
    public List<RequestTree> recent() {
        List<RequestTree> trees = new ArrayList<>();
        for (Kept kept : recent.values()) {
            trees.add(0, kept.tree);
        }
        return trees;
    }

    /** A route's exemplars: its slowest trees, slowest first, then its latest failed ones, newest first. */
    public List<RequestTree> exemplars(String route) {
        Exemplars exemplars = routes.get(route);
        return exemplars == null ? List.of() : exemplars.trees();
    }

    /** The routes with exemplars. */
    public List<String> routes() {
        return List.copyOf(routes.keySet());
    }

    /** How many trees are open, not settled yet. */
    public int openCount() {
        return open.size();
    }

    /** How many trees settled. */
    public long settled() {
        return settled;
    }

    /** How many settled trees wait for their request's exchange. */
    public int unresolvedCount() {
        return unresolved.size();
    }

    /** How many settled trees were named once their request's exchange arrived later. */
    public long lateNamed() {
        return lateNamed;
    }

    /** How many settled trees were completed without their request's exchange. */
    public long neverNamed() {
        return neverNamed;
    }

    /** How many fragments arrived after their request settled. */
    public long late() {
        return late;
    }

    /** How many fragments arrived after their tree left the recent ones but was still an exemplar, and were dropped. */
    public long lateDropped() {
        return lateDropped;
    }

    /** How many kept trees or routes were forgotten to stay within bounds. */
    public long forgotten() {
        return forgotten;
    }

    /** The nodes of every tree kept. */
    public int keptNodes() {
        return keptNodes;
    }

    /** Removes the open tree of {@code key}, if any, and its nodes from the count; {@code ignored} is unused. */
    private Open removeOpen(String key) {
        Open entry = open.remove(key);
        if (entry != null) {
            openNodes -= entry.nodes;
        }
        return entry;
    }

    /** Remembers {@code key} as cleared, as the most recent, so a long request's tombstone is the last forgotten. */
    private void tombstone(String key) {
        tombstones.remove(key);
        tombstones.put(key, Boolean.TRUE);
        while (tombstones.size() > MAX_TOMBSTONES) {
            tombstones.remove(tombstones.keySet().iterator().next());
        }
    }

    /** Drops every tree of {@code key}: open, recent, waiting for its exchange, or exemplar. */
    private void forget(String key) {
        removeOpen(key);
        Kept kept = recent.remove(key);
        if (kept != null) {
            keptNodes -= kept.tree.nodeCount();
        }
        unresolved.remove(key);
        for (Exemplars exemplars : routes.values()) {
            keptNodes += exemplars.remove(key);
        }
    }

    /**
     * <b>Clear recording</b> (M5-11): drops every tree, open, recent, waiting for its exchange, or exemplar, and
     * remembers their keys, so a later fragment of one of them is dropped too; from now on, a fragment flushed before
     * {@code nowNanos}, as one still queued in the agent's ring, is dropped with its request. Counts since the claim are
     * kept.
     *
     * @return how many trees were dropped
     */
    public int clear(long nowNanos) {
        Set<String> keys = new java.util.LinkedHashSet<>(open.keySet());
        keys.addAll(recent.keySet());
        keys.addAll(unresolved.keySet());
        for (Exemplars exemplars : routes.values()) {
            for (RequestTree tree : exemplars.trees()) {
                keys.add(tree.key());
            }
        }
        for (String key : keys) {
            tombstone(key);
        }
        open.clear();
        recent.clear();
        routes.clear();
        unresolved.clear();
        openNodes = 0;
        keptNodes = 0;
        nextResolve = Long.MIN_VALUE;
        cleared = true;
        clearedBeforeNanos = nowNanos;
        clearedTrees += keys.size();
        return keys.size();
    }

    /** How many trees were dropped by clears. */
    public long clearedTrees() {
        return clearedTrees;
    }

    /** How many fragments were dropped because their request was cleared. */
    public long clearedFragments() {
        return clearedFragments;
    }

    /** How many trees are kept: recent, waiting for their exchange, or open. */
    public int trees() {
        return recent.size() + unresolved.size() + open.size();
    }

    /** The most nodes kept. */
    public int maxKeptNodes() {
        return maxKeptNodes;
    }

    /** The estimated bytes of every tree kept, open, and remembered as cleared. */
    public long estimatedBytes() {
        return (keptNodes + openNodes) * (long) NODE_BYTES
                + (long) (recent.size() + unresolved.size() + open.size()) * TREE_BYTES
                + (long) tombstones.size() * TOMBSTONE_BYTES;
    }

    /** The estimated bytes of the most this store keeps: its kept and open nodes, and its tombstones. */
    public long maxEstimatedBytes() {
        return ((long) maxKeptNodes + (long) maxOpen * RequestTreeBuilder.MAX_NODES) * NODE_BYTES
                + (long) (RECENT + MAX_UNRESOLVED + maxOpen) * TREE_BYTES
                + (long) MAX_TOMBSTONES * TOMBSTONE_BYTES;
    }

    private static final class Open {

        final RequestTreeBuilder builder;
        long lastNanos;
        int nodes;

        Open(RequestTreeBuilder builder) {
            this.builder = builder;
        }
    }

    /**
     * A settled tree waiting for its request's exchange, since when, with the nodes of every fragment its builder
     * remembered, for the stamps of its calls, and what the journal said when it settled, its calls included.
     */
    private static final class Pending {

        final Kept kept;
        final long sinceNanos;
        final RequestOutcome settledOutcome;
        Map<Long, int[]> holders;

        Pending(Kept kept, long sinceNanos, Map<Long, int[]> holders, RequestOutcome settledOutcome) {
            this.kept = kept;
            this.sinceNanos = sinceNanos;
            this.holders = holders;
            this.settledOutcome = settledOutcome == null ? RequestOutcome.UNKNOWN : settledOutcome;
        }
    }

    private static final class Kept {

        RequestTree tree;
        RequestTreeBuilder.Spans spans;
        RequestOutcome outcome;

        Kept(RequestTree tree, RequestTreeBuilder.Spans spans, RequestOutcome outcome) {
            this.tree = tree;
            this.spans = spans;
            this.outcome = outcome;
        }
    }

    /** A route's slowest and latest failed trees, as immutable snapshots. */
    private static final class Exemplars {

        private final List<RequestTree> slowest = new ArrayList<>();
        private final List<RequestTree> failed = new ArrayList<>();

        /** Keeps the tree if it qualifies; returns the change in nodes kept. */
        int offer(Kept kept) {
            int before = nodes();
            RequestTree tree = kept.tree;
            if (kept.outcome.failed()) {
                failed.add(0, tree);
                if (failed.size() > EXEMPLARS) {
                    failed.remove(failed.size() - 1);
                }
            }
            slowest.add(tree);
            slowest.sort(Comparator.comparingLong(RequestTree::durationNanos).reversed());
            if (slowest.size() > EXEMPLARS) {
                slowest.remove(slowest.size() - 1);
            }
            return nodes() - before;
        }

        int nodes() {
            int nodes = 0;
            for (RequestTree tree : slowest) {
                nodes += tree.nodeCount();
            }
            for (RequestTree tree : failed) {
                nodes += tree.nodeCount();
            }
            return nodes;
        }

        /** Replaces the exemplar with {@code tree}'s key by {@code tree}, if any; returns the change in nodes kept. */
        int replace(RequestTree tree) {
            int before = nodes();
            boolean replaced = false;
            for (int i = 0; i < slowest.size(); i++) {
                if (slowest.get(i).key().equals(tree.key())) {
                    slowest.set(i, tree);
                    replaced = true;
                }
            }
            for (int i = 0; i < failed.size(); i++) {
                if (failed.get(i).key().equals(tree.key())) {
                    failed.set(i, tree);
                }
            }
            if (replaced) {
                slowest.sort(
                        Comparator.comparingLong(RequestTree::durationNanos).reversed());
            }
            return nodes() - before;
        }

        /** Removes the exemplars with {@code key}; returns the change in nodes kept. */
        int remove(String key) {
            int before = nodes();
            slowest.removeIf(tree -> tree.key().equals(key));
            failed.removeIf(tree -> tree.key().equals(key));
            return nodes() - before;
        }

        RequestTree find(String key) {
            for (RequestTree tree : slowest) {
                if (tree.key().equals(key)) {
                    return tree;
                }
            }
            for (RequestTree tree : failed) {
                if (tree.key().equals(key)) {
                    return tree;
                }
            }
            return null;
        }

        List<RequestTree> trees() {
            List<RequestTree> trees = new ArrayList<>(slowest);
            trees.addAll(failed);
            return trees;
        }
    }
}
