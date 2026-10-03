package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.ConnectionPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalTextExposure;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.TransactionPayload;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code transaction-across-remote-call} ({@code docs/PLAN-v2.md} §5.5): a physical transaction still open when a REST
 * client call starts, per route and transactional method, with the connection it held and, as a labelled estimate, the
 * request rate at which this route alone would exhaust the pool: pool size ÷ hold time.
 */
public final class TransactionAcrossRemoteCall implements Observation {

    public static final String KIND = "transaction-across-remote-call";

    static final int MIN_TRANSACTIONS = 3;

    static final long MIN_CALL_NANOS = 20_000_000;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Transactions open across remote calls";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.TRANSACTION, JournalSource.REST_CLIENT);
    }

    @Override
    public Set<JournalSource> optionalReads() {
        return Set.of(JournalSource.CONNECTION);
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        return snapshot.stack() == InsightsStack.QUARKUS
                ? "Quarkus transactions are not recorded, so calls cannot be placed in them."
                : null;
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        long eligible = 0;
        for (Map.Entry<String, List<ProjectedRequest>> route :
                snapshot.byRoute().entrySet()) {
            Map<String, Method> methods = new LinkedHashMap<>();
            long routeEligible = 0;
            for (ProjectedRequest request : route.getValue()) {
                List<RuntimeEvent> calls = request.children(JournalSource.REST_CLIENT);
                List<Window> windows = windows(request);
                if (calls.isEmpty() || windows.isEmpty()) {
                    continue;
                }
                routeEligible++;
                Set<String> seen = new LinkedHashSet<>();
                for (RuntimeEvent call : calls) {
                    if (!(call.payload() instanceof RestClientPayload payload) || payload.completedNanos() < 0) {
                        continue;
                    }
                    long callStart = payload.completedNanos() - Math.max(0, call.durationNanos());
                    Window open = outermost(windows, callStart);
                    if (open == null) {
                        continue;
                    }
                    String method = open.method();
                    Connection held = connection(request, callStart);
                    methods.computeIfAbsent(method, m -> new Method())
                            .add(request, open, call, payload, held, seen.add(method), snapshot.exposure());
                }
            }
            eligible += routeEligible;
            long routeRequests = routeEligible;
            methods.forEach(
                    (method, found) -> findings.add(finding(route.getKey(), method, found, routeRequests, snapshot)));
        }
        return new Evaluation(eligible, findings);
    }

    private Finding finding(String route, String method, Method found, long eligible, InsightsSnapshot snapshot) {
        long callMedian = RouteTimeBreakdown.median(found.callNanos());
        boolean sufficient = found.transactions >= MIN_TRANSACTIONS && callMedian >= MIN_CALL_NANOS;
        StringBuilder sentence = new StringBuilder("`" + route + "`: `" + method
                + "` kept its transaction open across a call to `" + found.firstCall + "` in "
                + found.transactions + " of " + InsightText.counted(eligible, InsightText.unit(route))
                + " that both called out and opened a transaction; the call took a median "
                + InsightText.millis(callMedian)
                + " ms");
        long[] holds = found.holdNanos();
        long holdMedian = RouteTimeBreakdown.median(holds);
        if (holds.length > 0) {
            sentence.append(", and its connection was held a median ")
                    .append(InsightText.millis(holdMedian))
                    .append(" ms");
        }
        sentence.append('.');
        List<String> limitations = new ArrayList<>();
        if (sufficient && holds.length > 0 && holdMedian > 0) {
            Optional<Integer> pool = snapshot.poolSize(found.dataSource);
            if (pool.isPresent()) {
                long perSecond = Math.max(1, Math.round(pool.get() * 1_000_000_000.0 / holdMedian));
                sentence.append(" Estimate: at about ")
                        .append(perSecond)
                        .append(" requests per second, this route alone would hold all ")
                        .append(pool.get())
                        .append(" connections of `")
                        .append(found.dataSource)
                        .append("` (pool size ÷ hold time).");
            } else {
                limitations.add("The pool size of the data source is unknown, so no exhaustion rate is estimated.");
            }
        }
        if (!sufficient) {
            sentence.append(" Reported from " + MIN_TRANSACTIONS + " transactions whose calls take "
                    + InsightText.millis(MIN_CALL_NANOS) + " ms or more.");
        }
        limitations.add("A call is placed in a transaction by time within its request; parallel work of the same"
                + " request on another thread could be placed too.");
        if (snapshot.stack() == InsightsStack.SPRING_WEBFLUX) {
            limitations.add("Only blocking transactions are recorded; a reactive transaction is not.");
        }
        return new Finding(
                route + ":" + InsightText.stableHash(method),
                route,
                sufficient,
                sentence.toString(),
                eligible,
                found.transactions,
                List.of(
                        "Make the remote call before the transaction begins or after it commits, so no connection"
                                + " waits on the network.",
                        "If the call belongs to the unit of work, consider an outbox or a compensating action"
                                + " instead."),
                found.rows.stream().map(row -> row.get(0)).distinct().limit(3).toList(),
                List.of("Request", "Call", "Call (ms)", "Transaction open (ms)", "Connection held (ms)"),
                found.rows,
                limitations);
    }

    private static List<Window> windows(ProjectedRequest request) {
        List<Window> windows = new ArrayList<>();
        for (RuntimeEvent event : request.children(JournalSource.TRANSACTION)) {
            if (event.payload() instanceof TransactionPayload transaction
                    && transaction.independent()
                    && transaction.startNanos() >= 0
                    && event.durationNanos() >= 0) {
                windows.add(new Window(
                        transaction.method() == null ? "transaction" : transaction.method(),
                        transaction.startNanos(),
                        transaction.startNanos() + event.durationNanos()));
            }
        }
        return windows;
    }

    private static Window outermost(List<Window> windows, long at) {
        Window found = null;
        for (Window window : windows) {
            if (at >= window.start() && at <= window.end() && (found == null || window.span() > found.span())) {
                found = window;
            }
        }
        return found;
    }

    private static Connection connection(ProjectedRequest request, long at) {
        for (RuntimeEvent event : request.children(JournalSource.CONNECTION)) {
            if (event.payload() instanceof ConnectionPayload connection
                    && connection.checkoutNanos() >= 0
                    && at >= connection.checkoutNanos()
                    && at <= connection.checkoutNanos() + Math.max(0, event.durationNanos())) {
                return new Connection(connection.dataSource(), Math.max(0, event.durationNanos()));
            }
        }
        return null;
    }

    private record Window(String method, long start, long end) {

        long span() {
            return end - start;
        }
    }

    private record Connection(String dataSource, long heldNanos) {}

    private static final class Method {

        private final List<List<String>> rows = new ArrayList<>();
        private final List<Long> calls = new ArrayList<>();
        private final List<Long> holds = new ArrayList<>();
        private long transactions;
        private String firstCall;
        private String dataSource;

        void add(
                ProjectedRequest request,
                Window open,
                RuntimeEvent call,
                RestClientPayload payload,
                Connection held,
                boolean newTransaction,
                JournalTextExposure text) {
            if (newTransaction) {
                transactions++;
                if (held != null) {
                    holds.add(held.heldNanos());
                    if (dataSource == null) {
                        dataSource = held.dataSource();
                    }
                }
            }
            String described = describe(payload, text);
            if (firstCall == null) {
                firstCall = described;
            }
            calls.add(Math.max(0, call.durationNanos()));
            rows.add(List.of(
                    request.requestId(),
                    described,
                    InsightText.millis(Math.max(0, call.durationNanos())),
                    InsightText.millis(open.span()),
                    held == null ? "" : InsightText.millis(held.heldNanos())));
        }

        long[] callNanos() {
            return calls.stream().mapToLong(Long::longValue).toArray();
        }

        long[] holdNanos() {
            return holds.stream().mapToLong(Long::longValue).toArray();
        }

        private static String describe(RestClientPayload payload, JournalTextExposure text) {
            return ((payload.method() == null ? "" : payload.method() + " ")
                            + (payload.authority() == null ? "" : payload.authority())
                            + (payload.path() == null ? "" : text.path(payload.path())))
                    .trim();
        }
    }
}
