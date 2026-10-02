package io.github.jdubois.bootui.engine.model;

import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Projects the journal's retained events and a run's structure into its {@link RuntimeModel} ({@code docs/PLAN-v2.md}
 * §5.4). Executions are the routes, GraphQL operations, scheduled jobs, and listeners that own work by request or
 * execution id, or, for AI spans exported after their request, by trace id. An event owned by no execution adds no
 * edge: a statement on an executor without context never joins a route by time.
 *
 * <p>The projection stops at its read budget and reports the model partial, naming how much of the journal it read.</p>
 */
public final class RuntimeModelProjection {

    /** The read budget of one projection over a full journal. */
    public static final long READ_BUDGET_NANOS = 250_000_000L;

    private static final int CLOCK_EVERY = 1_024;

    private RuntimeModelProjection() {}

    /**
     * Projects {@code entries} with the run's {@code structure}.
     *
     * @param evicted the events the journal evicted in this run, which the model cannot see
     * @param clock a monotonic clock in nanoseconds, such as {@code System::nanoTime}
     * @param budgetNanos the read budget, such as {@link #READ_BUDGET_NANOS}
     */
    public static RuntimeModel project(
            List<JournalEntry> entries,
            RouteTemplateResolver routes,
            StructureSnapshot structure,
            long evicted,
            LongSupplier clock,
            long budgetNanos) {
        long started = clock.getAsLong();
        RuntimeModelBuilder builder = new RuntimeModelBuilder();
        List<String> limitations = new ArrayList<>();
        declare(builder, structure);

        List<JournalEntry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingLong(JournalEntry::sequence));
        Map<String, Integer> byRequest = new HashMap<>();
        Map<String, Integer> byExecution = new HashMap<>();
        Map<String, Integer> byTrace = new HashMap<>();
        RouteTemplateResolver resolver = routes == null ? RouteTemplateResolver.empty() : routes;
        int read = 0;
        boolean overBudget = false;
        for (JournalEntry entry : ordered) {
            if (++read % CLOCK_EVERY == 0 && clock.getAsLong() - started > budgetNanos) {
                overBudget = true;
                break;
            }
            RuntimeEvent event = entry.event();
            if (event.payload() instanceof HttpPayload http && event.requestId() != null) {
                RouteLabel label =
                        RouteLabel.of(http.method(), http.path(), http.routeTemplate(), http.operation(), resolver);
                int node = builder.node(
                        http.operation() == null || http.operation().isBlank()
                                ? NodeType.ROUTE
                                : NodeType.GRAPHQL_OPERATION,
                        label.id());
                builder.execution(node);
                byRequest.put(event.requestId(), node);
                if (event.traceId() != null) {
                    byTrace.put(event.traceId(), node);
                }
            } else if (event.payload() instanceof ScheduledPayload job && event.executionId() != null) {
                int node = builder.node(NodeType.SCHEDULED_JOB, job.task());
                builder.execution(node);
                byExecution.put(event.executionId(), node);
            } else if (event.payload() instanceof MessagingPayload message
                    && !message.sent()
                    && event.executionId() != null) {
                String destination = destination(message);
                int node = builder.node(NodeType.LISTENER, destination);
                builder.execution(node);
                byExecution.put(event.executionId(), node);
                builder.observe(
                        node, EdgeType.CONSUMES, builder.node(NodeType.DESTINATION, destination), event.epochMillis());
            }
        }
        int owned = 0;
        for (int i = 0; i < Math.min(read, ordered.size()) && !overBudget; i++) {
            if (i % CLOCK_EVERY == 0 && clock.getAsLong() - started > budgetNanos) {
                overBudget = true;
                break;
            }
            RuntimeEvent event = ordered.get(i).event();
            Integer owner = owner(event, byRequest, byExecution, byTrace);
            if (owner == null) {
                continue;
            }
            owned++;
            observe(builder, owner, event);
        }
        if (overBudget) {
            limitations.add("The projection stopped at its " + budgetNanos / 1_000_000 + " ms read budget after "
                    + Math.min(read, ordered.size()) + " of " + ordered.size() + " retained events.");
        }
        if (evicted > 0) {
            limitations.add("The journal evicted " + evicted + " older events, so their edges are not observed.");
        }
        if (owned == 0 && !ordered.isEmpty()) {
            limitations.add("No retained event belongs to a request or execution yet.");
        }
        return builder.build(structure == null ? null : structure.runId(), limitations);
    }

    private static Integer owner(
            RuntimeEvent event,
            Map<String, Integer> byRequest,
            Map<String, Integer> byExecution,
            Map<String, Integer> byTrace) {
        if (event.requestId() != null) {
            return byRequest.get(event.requestId());
        }
        if (event.executionId() != null) {
            return byExecution.get(event.executionId());
        }
        return event.payload() instanceof AiPayload && event.traceId() != null ? byTrace.get(event.traceId()) : null;
    }

    private static void observe(RuntimeModelBuilder builder, int owner, RuntimeEvent event) {
        long at = event.epochMillis();
        Object payload = event.payload();
        if (payload instanceof SqlPayload sql) {
            EdgeType access = isWrite(sql.sql()) ? EdgeType.WRITES : EdgeType.READS;
            for (String table : SqlShapes.tables(sql.sql())) {
                builder.observe(owner, access, builder.node(NodeType.TABLE, table), at);
            }
        } else if (payload instanceof CachePayload cache) {
            String operation =
                    cache.operation() == null ? "" : cache.operation().toUpperCase(Locale.ROOT);
            EdgeType access = operation.equals("PUT") || operation.equals("EVICT") ? EdgeType.WRITES : EdgeType.READS;
            builder.observe(owner, access, builder.node(NodeType.CACHE, cache.cacheName()), at);
        } else if (payload instanceof RestClientPayload call) {
            builder.observe(owner, EdgeType.CALLS, builder.node(NodeType.HOST, call.authority()), at);
        } else if (payload instanceof AiPayload ai && ai.model() != null) {
            String model = ai.provider() == null ? ai.model() : ai.provider() + ":" + ai.model();
            builder.observe(owner, EdgeType.CALLS, builder.node(NodeType.AI_MODEL, model), at);
        } else if (payload instanceof MessagingPayload message && message.sent()) {
            builder.observe(owner, EdgeType.PUBLISHES, builder.node(NodeType.DESTINATION, destination(message)), at);
        } else if (payload instanceof ExceptionPayload exception) {
            builder.observe(owner, EdgeType.RAISES, builder.node(NodeType.EXCEPTION_GROUP, exception.groupId()), at);
        }
    }

    private static void declare(RuntimeModelBuilder builder, StructureSnapshot structure) {
        if (structure == null) {
            return;
        }
        Map<String, List<String>> beansByType = new HashMap<>();
        for (StructureSnapshot.Bean bean : structure.beans()) {
            builder.node(bean.repository() ? NodeType.REPOSITORY : NodeType.BEAN, bean.name());
            if (bean.type() != null) {
                beansByType
                        .computeIfAbsent(bean.type(), type -> new ArrayList<>())
                        .add(bean.name());
            }
        }
        Map<String, Integer> beanNodes = new HashMap<>();
        for (StructureSnapshot.Bean bean : structure.beans()) {
            beanNodes.put(
                    bean.name(), builder.node(bean.repository() ? NodeType.REPOSITORY : NodeType.BEAN, bean.name()));
        }
        for (StructureSnapshot.Bean bean : structure.beans()) {
            for (String dependency : bean.dependencies()) {
                Integer target = beanNodes.get(dependency);
                if (target != null) {
                    builder.declare(beanNodes.get(bean.name()), EdgeType.DEPENDS_ON, target);
                }
            }
        }
        for (StructureSnapshot.RouteHandler route : structure.routes()) {
            int node = builder.node(
                    route.route().contains(" (") ? NodeType.GRAPHQL_OPERATION : NodeType.ROUTE, route.route());
            for (String bean : beansByType.getOrDefault(route.handlerClass(), List.of())) {
                builder.declare(node, EdgeType.HANDLED_BY, beanNodes.get(bean));
            }
        }
    }

    private static String destination(MessagingPayload message) {
        return (message.broker() == null ? "?" : message.broker()) + ":" + message.destination();
    }

    private static boolean isWrite(String sql) {
        if (sql == null) {
            return false;
        }
        String head = sql.stripLeading().toLowerCase(Locale.ROOT);
        return head.startsWith("insert")
                || head.startsWith("update")
                || head.startsWith("delete")
                || head.startsWith("merge");
    }
}
