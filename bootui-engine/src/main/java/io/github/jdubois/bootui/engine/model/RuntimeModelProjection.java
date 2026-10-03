package io.github.jdubois.bootui.engine.model;

import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Projects the journal's retained events and a run's structure into its {@link RuntimeModel} ({@code docs/PLAN-v2.md}
 * §5.4). Executions are the routes, GraphQL operations, scheduled jobs, and listeners that own work by request or
 * execution id, or, for AI spans exported after their request, by trace id. An event owned by no execution adds no
 * edge: a statement on an executor without context never joins a route by time. {@link ObservedEdges} names the edges
 * each event adds, as the journal's aggregates do for the whole run.
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
        Map<String, Integer> byOwner = new HashMap<>();
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
            ObservedEdges.Execution execution = ObservedEdges.execution(
                    event,
                    http -> RouteLabel.of(http.method(), http.path(), http.routeTemplate(), http.operation(), resolver)
                            .id());
            if (execution == null) {
                continue;
            }
            int node = builder.node(execution.type(), execution.key());
            builder.execution(node);
            byOwner.put(ObservedEdges.executionKey(event, execution), node);
            if (execution.request() && event.traceId() != null) {
                byTrace.put(event.traceId(), node);
            }
        }
        int owned = 0;
        int projected = 0;
        for (int i = 0; i < ordered.size() && !overBudget; i++) {
            if (i % CLOCK_EVERY == 0 && clock.getAsLong() - started > budgetNanos) {
                overBudget = true;
                break;
            }
            projected++;
            RuntimeEvent event = ordered.get(i).event();
            Integer owner = owner(event, byOwner, byTrace);
            if (owner == null) {
                continue;
            }
            owned++;
            observe(builder, owner, event);
        }
        if (overBudget) {
            // Edges come from the second pass, so it alone says how much of the journal the model reflects.
            limitations.add("The projection stopped at its " + budgetNanos / 1_000_000
                    + " ms read budget after reading the edges of " + projected + " of " + ordered.size()
                    + " retained events.");
        }
        if (evicted > 0) {
            limitations.add("The journal evicted " + evicted + " older events, so their edges are not observed.");
        }
        if (owned == 0 && !ordered.isEmpty() && !overBudget) {
            limitations.add("No retained event belongs to a request or execution yet.");
        }
        return builder.build(structure == null ? null : structure.runId(), limitations);
    }

    private static Integer owner(RuntimeEvent event, Map<String, Integer> byOwner, Map<String, Integer> byTrace) {
        String key = ObservedEdges.ownerKey(event);
        if (key != null) {
            return byOwner.get(key);
        }
        return ObservedEdges.ownedByTrace(event) ? byTrace.get(event.traceId()) : null;
    }

    private static void observe(RuntimeModelBuilder builder, int owner, RuntimeEvent event) {
        for (ObservedEdges.Target target : ObservedEdges.targets(event)) {
            builder.observe(owner, target.type(), builder.node(target.nodeType(), target.key()), event.epochMillis());
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
}
