package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code proxy-bypass} ({@code docs/PLAN-v2.md} §5.12): an annotated method that ran without its proxy's effect in the
 * same request, from the application frames of the statements it ran. A {@code @Transactional} method whose statement
 * ran outside every recorded transaction, a {@code @Cacheable} method whose statement ran before any access to its
 * cache. Thread names and kinds do not establish whether {@code @Async} advice ran, so async-only methods remain
 * unassessed. The captured caller can suggest checks, but does not establish why a proxy effect was absent.
 */
public final class ProxyBypass implements Observation {

    public static final String KIND = "proxy-bypass";

    private volatile ProxyBoundaries boundaries;

    /** Installs the resolver of each frame's boundaries; without one, the check does not apply. */
    public void setBoundaries(ProxyBoundaries boundaries) {
        this.boundaries = boundaries;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Proxy bypass";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.SQL);
    }

    @Override
    public Set<JournalSource> optionalReads() {
        return Set.of(JournalSource.TRANSACTION, JournalSource.CACHE);
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        if (snapshot.stack() == InsightsStack.QUARKUS) {
            return "Quarkus: ArC intercepts a call from inside a bean, so a self-invocation keeps its interceptors.";
        }
        ProxyBoundaries resolver = boundaries;
        if (resolver == null) {
            return "No resolver of proxy boundaries is installed.";
        }
        return resolver.notApplicable();
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        ProxyBoundaries resolver = boundaries;
        boolean transactions = snapshot.available(JournalSource.TRANSACTION);
        boolean caches = snapshot.available(JournalSource.CACHE);
        Map<String, ProxyBoundaries.Boundary> resolved = new HashMap<>();
        List<Finding> findings = new ArrayList<>();
        Set<String> cacheUnassessed = new LinkedHashSet<>();
        Set<String> cacheReasons = new LinkedHashSet<>();
        Set<String> asyncUnassessed = new LinkedHashSet<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            long annotated = 0;
            Map<String, Bypass> bypasses = new LinkedHashMap<>();
            for (ProjectedRequest request : route.getValue()) {
                TransactionWindows windows = new TransactionWindows(request);
                Set<CacheAccess> cachesSeen = new LinkedHashSet<>();
                boolean ranAnnotated = false;
                for (RuntimeEvent child : request.children()) {
                    if (child.payload() instanceof CachePayload cache
                            && cache.managerName() != null
                            && cache.cacheName() != null) {
                        cachesSeen.add(new CacheAccess(cache.managerName(), cache.cacheName()));
                        continue;
                    }
                    if (!(child.payload() instanceof SqlPayload sql) || !sql.executed() || sql.frames() == null) {
                        continue;
                    }
                    List<String> frames = sql.frames().frames();
                    for (int i = 0; i < frames.size(); i++) {
                        String frame = frames.get(i);
                        ProxyBoundaries.Boundary boundary =
                                resolved.computeIfAbsent(frame, key -> resolve(resolver, key));
                        if (boundary == null || !boundary.any()) {
                            continue;
                        }
                        boolean frameAssessed = boundary.transactional() && transactions;
                        String caller = i + 1 < frames.size() ? frames.get(i + 1) : null;
                        if (boundary.transactional()
                                && transactions
                                && windows.canPlace(child)
                                && windows.innermost(child) == null) {
                            record(bypasses, frame, "@Transactional", caller, request);
                        }
                        if (caches && boundary.cacheUnobservable() != null) {
                            cacheUnassessed.add(request.requestId());
                            cacheReasons.add(boundary.cacheUnobservable());
                        } else if (!boundary.cacheNames().isEmpty()
                                && caches
                                && boundary.cacheNames().stream()
                                        .map(name -> new CacheAccess(
                                                snapshot.cacheCapture().selectedManager(boundary.cacheManager()), name))
                                        .noneMatch(cachesSeen::contains)) {
                            String unavailable =
                                    snapshot.cacheCapture().unavailable(boundary.cacheManager(), request.startMillis());
                            if (unavailable == null) {
                                frameAssessed = true;
                                record(
                                        bypasses,
                                        frame,
                                        "@Cacheable(" + String.join(", ", boundary.cacheNames()) + ")",
                                        caller,
                                        request);
                            } else {
                                cacheUnassessed.add(request.requestId());
                                cacheReasons.add(unavailable);
                            }
                        } else if (!boundary.cacheNames().isEmpty() && caches) {
                            frameAssessed = true;
                        }
                        if (boundary.async()) {
                            asyncUnassessed.add(request.requestId());
                        }
                        ranAnnotated |= frameAssessed;
                        if (frameAssessed) {
                            break;
                        }
                    }
                }
                if (ranAnnotated) {
                    annotated++;
                }
            }
            eligible += annotated;
            long routeEligible = annotated;
            bypasses.values().forEach(bypass -> findings.add(bypass.finding(route.getKey(), routeEligible)));
        }
        List<String> unassessed = new ArrayList<>();
        if (!cacheUnassessed.isEmpty()) {
            unassessed.add(cacheUnassessed.size()
                    + (cacheUnassessed.size() == 1 ? " request or execution could" : " requests or executions could")
                    + " not be assessed for cache proxy bypass. " + String.join(" ", cacheReasons));
        }
        if (!asyncUnassessed.isEmpty()) {
            unassessed.add(InsightText.counted(asyncUnassessed.size(), "request or execution", "requests or executions")
                    + " could not be assessed for @Async proxy bypass: captured thread names and kinds prove neither"
                    + " physical thread identity nor whether async advice ran. On WebFlux the request thread is"
                    + " recorded at completion, not at dispatch.");
        }
        return new Evaluation(eligible, findings, unassessed.isEmpty() ? null : String.join(" ", unassessed));
    }

    private static ProxyBoundaries.Boundary resolve(ProxyBoundaries resolver, String frame) {
        try {
            return resolver == null ? null : resolver.resolve(frame);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private record CacheAccess(String managerName, String cacheName) {}

    private static String record(
            Map<String, Bypass> bypasses, String frame, String annotation, String caller, ProjectedRequest request) {
        String method = method(frame);
        String key = method + " " + annotation;
        bypasses.computeIfAbsent(key, ignored -> new Bypass(method, annotation, caller))
                .add(
                        request,
                        caller,
                        caller != null
                                && !declaringClass(frame).isEmpty()
                                && declaringClass(frame).equals(declaringClass(caller)));
        return key;
    }

    private static String declaringClass(String frame) {
        if (frame == null) {
            return "";
        }
        int open = frame.indexOf('(');
        String qualified = open < 0 ? frame : frame.substring(0, open);
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }

    /** {@code com.example.OrderService.place(OrderService.java:42)} as {@code OrderService.place}. */
    static String method(String frame) {
        int open = frame.indexOf('(');
        String qualified = open < 0 ? frame : frame.substring(0, open);
        int methodDot = qualified.lastIndexOf('.');
        if (methodDot < 0) {
            return qualified;
        }
        String className = qualified.substring(0, methodDot);
        return className.substring(className.lastIndexOf('.') + 1) + qualified.substring(methodDot);
    }

    /** One annotated method of a route that ran without its proxy, and the requests it did so in. */
    private static final class Bypass {

        private final String method;
        private final String annotation;
        private final String caller;
        private final Map<String, List<String>> rows = new LinkedHashMap<>();
        private boolean sameClassCaller = true;

        Bypass(String method, String annotation, String caller) {
            this.method = method;
            this.annotation = annotation;
            this.caller = caller;
        }

        void add(ProjectedRequest request, String calledFrom, boolean sameClass) {
            sameClassCaller &= sameClass;
            rows.putIfAbsent(
                    request.requestId(),
                    List.of(
                            request.requestId(),
                            annotation,
                            method,
                            calledFrom == null ? "" : calledFrom,
                            request.thread() == null ? "" : request.thread()));
        }

        Finding finding(String route, long eligible) {
            return new Finding(
                    route + ":" + InsightText.stableHash(method + " " + annotation),
                    route,
                    true,
                    "`" + route + "` ran `" + method + "` without its `" + annotation + "` proxy in " + rows.size()
                            + " of " + InsightText.counted(eligible, InsightText.unit(route))
                            + " that ran an annotated method"
                            + (caller == null ? "." : ", called from `" + caller + "`."),
                    eligible,
                    rows.size(),
                    List.of(
                            sameClassCaller
                                    ? "If the captured caller invokes this method on `this`, call through the managed"
                                            + " bean or move the boundary to another bean."
                                    : "Check that the annotation's infrastructure is enabled and that the caller uses"
                                            + " a managed bean rather than an instance created with `new`.",
                            "The Architecture advisor's ARCH-SPRING-004 reports the self-invocations it can see in the"
                                    + " code. Check the method's visibility and proxy configuration too; the captured"
                                    + " caller alone does not identify the cause."),
                    rows.keySet().stream().limit(3).toList(),
                    List.of("Request", "Annotation", "Method", "Called from", "Thread"),
                    new ArrayList<>(rows.values()),
                    List.of(
                            "Methods are matched by name from their frames: overloads that declare different"
                                    + " boundaries are not judged.",
                            "Cacheable methods with sync = true or a condition are not judged: a synchronous miss"
                                    + " is recorded after its loader runs, and a condition can skip the cache.",
                            "Missing cache accesses count only for a proven synchronous cache-manager path and a"
                                    + " request inside its current continuous-capture window.",
                            "Only the four innermost application frames of each statement are read, and a transaction"
                                    + " counts only when the transaction source records its boundary."));
        }
    }
}
