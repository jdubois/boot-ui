package io.github.jdubois.bootui.engine.model;

import io.github.jdubois.bootui.engine.journal.AiCallOwners;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Projects the journal's retained events and a run's structure into its {@link RuntimeModel} ({@code docs/PLAN-v2.md}
 * §5.4). Executions are the routes, GraphQL operations, scheduled jobs, and listeners that own work by request or
 * execution id, or, for AI spans exported after their request, by trace id and time through {@link AiCallOwners}. An event owned by no execution adds no
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
        return project(entries, routes, structure, evicted, clock, budgetNanos, traceId -> false);
    }

    /**
     * Projects {@code entries} with the run's {@code structure}, where {@code evictedRequestTraces} names the traces of
     * requests the journal evicted, whose AI calls linked only by trace may be theirs and so belong to no retained one.
     */
    public static RuntimeModel project(
            List<JournalEntry> entries,
            RouteTemplateResolver routes,
            StructureSnapshot structure,
            long evicted,
            LongSupplier clock,
            long budgetNanos,
            Predicate<String> evictedRequestTraces) {
        return project(entries, routes, structure, evicted, clock, budgetNanos, evictedRequestTraces, List.of());
    }

    /**
     * Projects {@code entries} as {@link #project(List, RouteTemplateResolver, StructureSnapshot, long, LongSupplier,
     * long, Predicate)} does, with the calls Code Paths observed between application classes, {@code invocations}, as
     * {@link EdgeType#INVOKES} edges between their beans ({@code docs/PLAN-v2.md} §5.14, M5-4c).
     */
    public static RuntimeModel project(
            List<JournalEntry> entries,
            RouteTemplateResolver routes,
            StructureSnapshot structure,
            long evicted,
            LongSupplier clock,
            long budgetNanos,
            Predicate<String> evictedRequestTraces,
            List<ClassInvocation> invocations) {
        return project(
                entries, routes, structure, evicted, clock, budgetNanos, evictedRequestTraces, invocations, List.of());
    }

    /**
     * Projects {@code entries} as {@link #project(List, RouteTemplateResolver, StructureSnapshot, long, LongSupplier,
     * long, Predicate, List)} does, with the hosts the BootUI agent's {@code network} sensor saw routes, scheduled jobs,
     * and application classes open, {@code opens}, as observed {@link EdgeType#OPENS} edges to {@link NodeType#HOST}
     * nodes keyed {@code host:port} ({@code docs/PLAN-v2.md} §5.16, M5-5b).
     */
    public static RuntimeModel project(
            List<JournalEntry> entries,
            RouteTemplateResolver routes,
            StructureSnapshot structure,
            long evicted,
            LongSupplier clock,
            long budgetNanos,
            Predicate<String> evictedRequestTraces,
            List<ClassInvocation> invocations,
            List<HostOpen> opens) {
        return project(
                entries,
                routes,
                structure,
                evicted,
                clock,
                budgetNanos,
                evictedRequestTraces,
                invocations,
                opens,
                List.of());
    }

    /**
     * Projects {@code entries} as {@link #project(List, RouteTemplateResolver, StructureSnapshot, long, LongSupplier,
     * long, Predicate, List, List)} does, with the files and environment variables Side Effects observed executions
     * access, {@code accesses}, as {@link EdgeType#OPENS} edges to {@link NodeType#FILE_PATTERN} and {@link
     * EdgeType#READS} edges to {@link NodeType#ENVIRONMENT_VARIABLE} nodes ({@code docs/PLAN-v2.md} §5.16, M5-5d).
     */
    public static RuntimeModel project(
            List<JournalEntry> entries,
            RouteTemplateResolver routes,
            StructureSnapshot structure,
            long evicted,
            LongSupplier clock,
            long budgetNanos,
            Predicate<String> evictedRequestTraces,
            List<ClassInvocation> invocations,
            List<HostOpen> opens,
            List<SideEffectAccess> accesses) {
        long started = clock.getAsLong();
        RuntimeModelBuilder builder = new RuntimeModelBuilder();
        List<String> limitations = new ArrayList<>();
        declare(builder, structure);
        invokes(builder, structure, invocations, limitations);
        opens(builder, structure, opens);
        if (accesses != null) {
            for (SideEffectAccess access : accesses) {
                builder.observeRange(
                        builder.node(access.fromType(), access.fromKey()),
                        access.type(),
                        builder.node(access.toType(), access.toKey()),
                        access.count(),
                        access.firstSeenEpochMillis(),
                        access.lastSeenEpochMillis());
            }
        }

        List<JournalEntry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingLong(JournalEntry::sequence));
        Map<String, Integer> byOwner = new HashMap<>();
        AiCallOwners aiCallOwners = new AiCallOwners(evictedRequestTraces);
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
            aiCallOwners.learn(event);
        }
        int owned = 0;
        int projected = 0;
        int ambiguousWrites = 0;
        int unverifiedPreparations = 0;
        for (int i = 0; i < ordered.size() && !overBudget; i++) {
            if (i % CLOCK_EVERY == 0 && clock.getAsLong() - started > budgetNanos) {
                overBudget = true;
                break;
            }
            projected++;
            RuntimeEvent event = ordered.get(i).event();
            Integer owner = owner(event, byOwner, aiCallOwners);
            if (owner == null) {
                continue;
            }
            owned++;
            if (event.payload() instanceof SqlPayload sql
                    && SqlShapes.writes(sql.sql()).stream().anyMatch(write -> !write.exact())) {
                ambiguousWrites++;
            }
            if (event.payload() instanceof SqlPayload sql && !sql.executed()) {
                unverifiedPreparations++;
            }
            observe(builder, owner, event);
        }
        if (unverifiedPreparations > 0) {
            limitations.add(unverifiedPreparations + " SQL event(s) did not establish execution; their"
                    + " possible read and write targets are omitted from the model because preparation alone does not"
                    + " establish database access.");
        }
        if (ambiguousWrites > 0) {
            limitations.add(ambiguousWrites + " retained SQL event(s) named ambiguous write targets; their candidate"
                    + " tables are not labelled as observed writes or reads, so change impact may omit routes that"
                    + " touched them.");
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

    private static Integer owner(RuntimeEvent event, Map<String, Integer> byOwner, AiCallOwners aiCallOwners) {
        String key = ObservedEdges.ownerKey(event);
        if (key != null) {
            return byOwner.get(key);
        }
        if (!ObservedEdges.ownedByTrace(event)) {
            return null;
        }
        String requestId = aiCallOwners.ownerOf(event);
        return requestId == null ? null : byOwner.get("request:" + requestId);
    }

    private static void observe(RuntimeModelBuilder builder, int owner, RuntimeEvent event) {
        for (ObservedEdges.Target target : ObservedEdges.targets(event)) {
            builder.observe(owner, target.type(), builder.node(target.nodeType(), target.key()), event.epochMillis());
        }
    }

    /** The calls Code Paths observed between beans, as {@link EdgeType#INVOKES} edges with their counts. */
    private static void invokes(
            RuntimeModelBuilder builder,
            StructureSnapshot structure,
            List<ClassInvocation> invocations,
            List<String> limitations) {
        if (structure == null || invocations == null || invocations.isEmpty()) {
            return;
        }
        BeanInvocations.Resolved resolved = BeanInvocations.resolve(structure.beans(), invocations);
        for (BeanInvocations.Edge edge : resolved.edges()) {
            builder.observeTimes(
                    builder.node(edge.fromRepository() ? NodeType.REPOSITORY : NodeType.BEAN, edge.from()),
                    EdgeType.INVOKES,
                    builder.node(edge.toRepository() ? NodeType.REPOSITORY : NodeType.BEAN, edge.to()),
                    edge.calls());
        }
        if (resolved.unmappedCalls() > 0) {
            limitations.add(resolved.unmappedCalls() + " calls Code Paths observed are between classes that are not"
                    + " each exactly one bean's, so they are in no invokes edge.");
        }
    }

    /**
     * The hosts routes, jobs, and application classes opened, as {@link EdgeType#OPENS} edges with their counts; a
     * class reaches the model only as the one bean of its type.
     */
    private static void opens(RuntimeModelBuilder builder, StructureSnapshot structure, List<HostOpen> opens) {
        if (opens == null || opens.isEmpty()) {
            return;
        }
        Map<String, List<StructureSnapshot.Bean>> beansByType = new HashMap<>();
        if (structure != null) {
            for (StructureSnapshot.Bean bean : structure.beans()) {
                if (bean.type() != null) {
                    beansByType
                            .computeIfAbsent(bean.type(), type -> new ArrayList<>())
                            .add(bean);
                }
            }
        }
        for (HostOpen open : opens) {
            int from = -1;
            switch (open.from()) {
                case HostOpen.ROUTE ->
                    from = builder.node(
                            open.key().contains(" (") ? NodeType.GRAPHQL_OPERATION : NodeType.ROUTE, open.key());
                case HostOpen.SCHEDULED_JOB -> from = builder.node(NodeType.SCHEDULED_JOB, open.key());
                case HostOpen.CLASS -> {
                    List<StructureSnapshot.Bean> beans = beansByType.getOrDefault(open.key(), List.of());
                    if (beans.size() == 1) {
                        StructureSnapshot.Bean bean = beans.get(0);
                        from = builder.node(bean.repository() ? NodeType.REPOSITORY : NodeType.BEAN, bean.name());
                    }
                }
                default -> from = -1;
            }
            if (from >= 0) {
                builder.observeTimes(from, EdgeType.OPENS, builder.node(NodeType.HOST, open.target()), open.count());
            }
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
