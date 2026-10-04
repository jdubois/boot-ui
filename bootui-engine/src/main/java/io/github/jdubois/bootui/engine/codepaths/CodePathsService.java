package io.github.jdubois.bootui.engine.codepaths;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.javaagent.AgentRecordDrainer;
import io.github.jdubois.bootui.engine.javaagent.JavaAgentService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Code Paths for one application ({@code docs/PLAN-v2.md} §5.14, M5-4a), shared by every adapter: for this run's claim
 * on the BootUI agent, routes the {@code code-paths} sensor's fragments from the claim's {@link AgentRecordDrainer} into
 * request trees ({@link RequestTreeStore}) and decides the sensor's adaptive exclusion ({@link AdaptiveExclusion}),
 * writing each excluded method back to the bridge through the claim. Fragments of another claim generation, such as a
 * previous run's still queued, are dropped and counted. Route trees and the surfaces that read them come with M5-4b.
 */
public final class CodePathsService implements AutoCloseable {

    private static final Logger log = Logger.getLogger(CodePathsService.class.getName());

    private final AgentBridgeAccess access;
    private final Supplier<AgentClaim> claims;
    private final Supplier<String> unavailable;
    private final LongSupplier nanoTime;
    private volatile Function<Set<String>, Map<String, RequestOutcome>> outcomes = ids -> Map.of();

    private final Object lock = new Object();
    private Run run;
    private boolean closed;

    /**
     * @param access the bridge
     * @param claims this application's current claim, or a supplier of {@code null}
     * @param unavailable why the code-paths sensor does not record for this application, {@code null} when it does, such
     *     as {@link JavaAgentService#codePathsUnavailableReason()}
     */
    public CodePathsService(AgentBridgeAccess access, Supplier<AgentClaim> claims, Supplier<String> unavailable) {
        this(access, claims, unavailable, System::nanoTime);
    }

    CodePathsService(
            AgentBridgeAccess access,
            Supplier<AgentClaim> claims,
            Supplier<String> unavailable,
            LongSupplier nanoTime) {
        this.access = access == null ? AgentBridgeAccess.absent() : access;
        this.claims = claims == null ? () -> null : claims;
        this.unavailable = unavailable == null ? () -> null : unavailable;
        this.nanoTime = nanoTime;
    }

    /**
     * Installs how a settling request's route and outcome are named, for its exemplars, such as
     * {@link JournalRequestOutcomes#of}.
     */
    public void setRequestOutcomes(Function<Set<String>, Map<String, RequestOutcome>> requestOutcomes) {
        this.outcomes = requestOutcomes == null ? ids -> Map.of() : requestOutcomes;
    }

    /**
     * Routes this run's fragments, once the claim is armed and the adapter's engine is ready: the adapter calls it when
     * its context refreshed or Quarkus started, and a read calls it again. Idempotent per claim generation. Never
     * throws.
     */
    public void start() {
        try {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                AgentClaim claim = claims.get();
                if (claim == null
                        || !claim.armed()
                        || claim.generation() == null
                        || !claim.sensors().codePaths()
                        || !access.codePathsSupported()) {
                    return;
                }
                if (run != null && run.generation == claim.generation()) {
                    return;
                }
                if (run != null) {
                    run.close();
                }
                run = new Run(claim);
                run.start();
            }
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "BootUI could not start Code Paths", ex);
        }
    }

    /** Stops routing this run's fragments. Idempotent; the service starts nothing afterwards. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            if (run != null) {
                run.close();
                run = null;
            }
        }
    }

    /** Why the sensor does not record for this application, or {@code null} when it does. */
    public String unavailableReason() {
        try {
            return unavailable.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * The tree of the request {@code requestId} (16 hexadecimal digits), open or kept, after draining what is waiting;
     * {@code null} when this run has none.
     */
    public RequestTree tree(String requestId) {
        Run current = current();
        if (current == null || requestId == null) {
            return null;
        }
        current.drainNow();
        synchronized (lock) {
            current.store.settle(nanoTime.getAsLong());
            return current.store.tree(requestId);
        }
    }

    /** This run's settled trees kept as recent, newest first, after draining what is waiting. */
    public List<RequestTree> recent() {
        Run current = current();
        if (current == null) {
            return List.of();
        }
        current.drainNow();
        synchronized (lock) {
            current.store.settle(nanoTime.getAsLong());
            return current.store.recent();
        }
    }

    /** A route's exemplar trees in this run: its slowest, then its latest failed. */
    public List<RequestTree> exemplars(String route) {
        Run current = current();
        if (current == null) {
            return List.of();
        }
        current.drainNow();
        synchronized (lock) {
            current.store.settle(nanoTime.getAsLong());
            return current.store.exemplars(route);
        }
    }

    /** The methods adaptively excluded in this run, as {@code class#name+descriptor} keys, in exclusion order. */
    public List<String> excludedMethods() {
        Run current = current();
        if (current == null) {
            return List.of();
        }
        List<Integer> ids;
        synchronized (lock) {
            ids = current.exclusion.excluded();
        }
        List<String> keys = new ArrayList<>();
        for (int id : ids) {
            String[] key = access.methodKeys(id, 1);
            keys.add(key.length == 1 && key[0] != null ? key[0] : "#" + id);
        }
        return keys;
    }

    /** This run's counters, for status and tests: JDK types. */
    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        Run current = current();
        map.put("generation", current == null ? null : current.generation);
        if (current != null) {
            synchronized (lock) {
                map.put("fragments", current.fragments);
                map.put("staleFragments", current.stale);
                map.put("malformedFragments", current.malformed);
                map.put("openTrees", current.store.openCount());
                map.put("settledTrees", current.store.settled());
                map.put("lateFragments", current.store.late());
                map.put("lateFragmentsDropped", current.store.lateDropped());
                map.put("forgottenTrees", current.store.forgotten());
                map.put("keptNodes", current.store.keptNodes());
                map.put("excludedMethods", current.exclusion.excluded().size());
            }
        }
        return map;
    }

    private Run current() {
        start();
        synchronized (lock) {
            return run;
        }
    }

    /** One run: its claim, drainer route, store, and exclusion. */
    private final class Run implements Consumer<long[]> {

        final AgentClaim claim;
        final long generation;
        final AgentRecordDrainer drainer;
        final RequestTreeStore store;
        final AdaptiveExclusion exclusion = new AdaptiveExclusion();
        long fragments;
        long stale;
        long malformed;

        Run(AgentClaim claim) {
            this.claim = claim;
            this.generation = claim.generation();
            this.drainer = claim.drainer();
            this.store = new RequestTreeStore(ids -> outcomes.apply(ids));
        }

        void start() {
            if (drainer != null) {
                drainer.routeCodePaths(this);
            }
        }

        void drainNow() {
            if (drainer != null) {
                drainer.drainNow();
            }
        }

        void close() {
            if (drainer != null) {
                drainer.unrouteCodePaths(this);
            }
        }

        /** One fragment, on the drain thread. */
        @Override
        public void accept(long[] blob) {
            CodePathFragment fragment = CodePathFragment.decode(blob);
            List<Integer> newly;
            synchronized (lock) {
                if (fragment == null) {
                    malformed++;
                    return;
                }
                if (fragment.generation() != generation) {
                    stale++;
                    return;
                }
                fragments++;
                long now = nanoTime.getAsLong();
                exclusion.record(fragment, now);
                store.add(fragment, now);
                store.settle(now);
                newly = exclusion.evaluate(now);
            }
            for (int id : newly) {
                claim.excludeCodePathsMethod(id);
            }
        }
    }
}
