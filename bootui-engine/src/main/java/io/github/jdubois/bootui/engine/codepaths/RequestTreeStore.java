package io.github.jdubois.bootui.engine.codepaths;

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
 * open tree per request until it settles, {@value #SETTLE_NANOS} ns after its last fragment or when more than
 * {@value #MAX_OPEN} are open; a settled tree is kept among the {@value #RECENT} most recent, and as an exemplar of its
 * route when it is among the {@value #EXEMPLARS} slowest or the {@value #EXEMPLARS} latest failed, for at most
 * {@value #MAX_ROUTES} routes. A fragment arriving after its request settled, as a late handoff's, merges into the kept
 * recent tree, and into its exemplar too; one arriving after its tree left the recent ones but is still an exemplar is
 * dropped and counted, never opening a partial duplicate. A settled tree keeps no builder, only its tree and the spans
 * a late fragment needs ({@link RequestTreeBuilder.Spans}), so every tree kept counts toward {@value #MAX_KEPT_NODES}
 * nodes for what it holds, past which the oldest recent trees, then the oldest routes' exemplars, are forgotten. Not
 * thread-safe.
 */
public final class RequestTreeStore {

    public static final long SETTLE_NANOS = 2_000_000_000L;
    public static final int MAX_OPEN = 512;
    public static final int RECENT = 256;
    public static final int EXEMPLARS = 3;
    public static final int MAX_ROUTES = 100;
    public static final int MAX_KEPT_NODES = 131_072;

    private final Function<Set<String>, Map<String, RequestOutcome>> outcomes;
    private BiConsumer<RequestTree, RequestOutcome> settledListener = (tree, outcome) -> {};
    private final LinkedHashMap<String, Open> open = new LinkedHashMap<>();
    private final LinkedHashMap<String, Kept> recent = new LinkedHashMap<>();
    private final LinkedHashMap<String, Exemplars> routes = new LinkedHashMap<>(16, 0.75f, true);
    private long settled;
    private long late;
    private long lateDropped;
    private long forgotten;
    private int keptNodes;

    /** @param outcomes names the route and outcome of settling requests by request id, such as {@link JournalRequestOutcomes#of} */
    public RequestTreeStore(Function<Set<String>, Map<String, RequestOutcome>> outcomes) {
        this.outcomes = outcomes == null ? ids -> Map.of() : outcomes;
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
        Kept kept = recent.get(key);
        if (kept != null && !open.containsKey(key)) {
            late++;
            RequestTreeBuilder builder = RequestTreeBuilder.resume(kept.tree, kept.spans);
            builder.add(fragment);
            RequestTree merged = builder.build();
            keptNodes += merged.nodeCount() - kept.tree.nodeCount();
            kept.tree = merged;
            kept.spans = builder.spans();
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
        if (open.size() > MAX_OPEN) {
            settle(List.of(open.keySet().iterator().next()));
        }
    }

    /** Settles the trees quiet for {@link #SETTLE_NANOS}; returns how many. */
    public int settle(long nowNanos) {
        List<String> quiet = new ArrayList<>();
        for (Map.Entry<String, Open> entry : open.entrySet()) {
            if (nowNanos - entry.getValue().lastNanos >= SETTLE_NANOS) {
                quiet.add(entry.getKey());
            }
        }
        settle(quiet);
        return quiet.size();
    }

    /** Settles every open tree, as when the run ends or a read needs them now. */
    public void settleAll() {
        settle(new ArrayList<>(open.keySet()));
    }

    private void settle(List<String> keys) {
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
        Map<String, RequestOutcome> named;
        try {
            named = requestIds.isEmpty() ? Map.of() : outcomes.apply(requestIds);
        } catch (RuntimeException ex) {
            named = Map.of();
        }
        for (String key : keys) {
            Open entry = open.remove(key);
            RequestTree tree = entry.builder.build();
            RequestOutcome outcome = tree.requestId() == null ? null : named.get(tree.requestId());
            // The builder is released here: only the tree and its spans are kept.
            RequestOutcome resolved = outcome == null ? RequestOutcome.UNKNOWN : outcome;
            keep(new Kept(tree, entry.builder.spans(), resolved));
            settled++;
            try {
                settledListener.accept(tree, resolved);
            } catch (RuntimeException ex) {
                // A listener never loses the tree the store keeps.
            }
        }
        trim();
    }

    private void keep(Kept kept) {
        Kept previous = recent.put(kept.tree.key(), kept);
        if (previous != null) {
            keptNodes -= previous.tree.nodeCount();
        }
        keptNodes += kept.tree.nodeCount();
        while (recent.size() > RECENT) {
            forgetEldestRecent();
        }
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
        while (keptNodes > MAX_KEPT_NODES && !recent.isEmpty()) {
            forgetEldestRecent();
        }
        while (keptNodes > MAX_KEPT_NODES && !routes.isEmpty()) {
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

    /** The tree of the request with key {@code key}, open or kept, or {@code null}. */
    public RequestTree tree(String key) {
        Open entry = open.get(key);
        if (entry != null) {
            return entry.builder.build();
        }
        Kept kept = recent.get(key);
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

    private static final class Open {

        final RequestTreeBuilder builder;
        long lastNanos;

        Open(RequestTreeBuilder builder) {
            this.builder = builder;
        }
    }

    private static final class Kept {

        RequestTree tree;
        RequestTreeBuilder.Spans spans;
        final RequestOutcome outcome;

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
