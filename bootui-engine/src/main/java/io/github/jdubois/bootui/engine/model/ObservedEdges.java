package io.github.jdubois.bootui.engine.model;

import io.github.jdubois.bootui.engine.journal.AiPayload;
import io.github.jdubois.bootui.engine.journal.CachePayload;
import io.github.jdubois.bootui.engine.journal.ExceptionPayload;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.MessagingPayload;
import io.github.jdubois.bootui.engine.journal.RestClientPayload;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.ScheduledPayload;
import io.github.jdubois.bootui.engine.journal.SqlPayload;
import io.github.jdubois.bootui.engine.sqltrace.SqlShapes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Which runtime-model nodes and observed edges one journal event names ({@code docs/PLAN-v2.md} §5.4). The model's
 * projection reads it over the retained events, and the journal's aggregates over every event as it arrives (§5.8), so
 * both name the same edges the same way.
 */
public final class ObservedEdges {

    private ObservedEdges() {}

    /**
     * The execution {@code event} completes, which owns the work recorded under its request or execution id, or
     * {@code null}: a request with an id, a scheduled job or a received message with an execution id.
     *
     * @param routeLabel names a request's route, such as {@code GET /api/orders/{id}}
     */
    public static Execution execution(RuntimeEvent event, Function<HttpPayload, String> routeLabel) {
        Object payload = event.payload();
        if (payload instanceof HttpPayload http && event.requestId() != null) {
            String label = routeLabel.apply(http);
            if (label == null) {
                return null;
            }
            return new Execution(
                    http.operation() == null || http.operation().isBlank()
                            ? NodeType.ROUTE
                            : NodeType.GRAPHQL_OPERATION,
                    label);
        }
        if (payload instanceof ScheduledPayload job && event.executionId() != null && job.task() != null) {
            return new Execution(NodeType.SCHEDULED_JOB, job.task());
        }
        if (payload instanceof MessagingPayload message
                && !message.sent()
                && event.executionId() != null
                && message.destination() != null) {
            return new Execution(NodeType.LISTENER, destination(message));
        }
        return null;
    }

    /**
     * The edges {@code event} adds from the execution that owns it: the tables a statement reads or writes, the cache it
     * reads or writes, the host or AI model it calls, the destination it publishes to or, for a received message, the
     * one its listener consumes, and the exception group it raises. Empty for any other event.
     */
    public static List<Target> targets(RuntimeEvent event) {
        Object payload = event.payload();
        if (payload instanceof SqlPayload sql) {
            Set<String> tables = SqlShapes.tables(sql.sql());
            if (tables.isEmpty()) {
                return List.of();
            }
            EdgeType access = isWrite(sql.sql()) ? EdgeType.WRITES : EdgeType.READS;
            List<Target> targets = new ArrayList<>(tables.size());
            for (String table : tables) {
                targets.add(new Target(access, NodeType.TABLE, table));
            }
            return targets;
        }
        if (payload instanceof CachePayload cache && cache.cacheName() != null) {
            String operation =
                    cache.operation() == null ? "" : cache.operation().toUpperCase(Locale.ROOT);
            EdgeType access = operation.equals("PUT") || operation.equals("EVICT") ? EdgeType.WRITES : EdgeType.READS;
            return List.of(new Target(access, NodeType.CACHE, cache.cacheName()));
        }
        if (payload instanceof RestClientPayload call && call.authority() != null) {
            return List.of(new Target(EdgeType.CALLS, NodeType.HOST, call.authority()));
        }
        if (payload instanceof AiPayload ai && ai.model() != null) {
            String model = ai.provider() == null ? ai.model() : ai.provider() + ":" + ai.model();
            return List.of(new Target(EdgeType.CALLS, NodeType.AI_MODEL, model));
        }
        if (payload instanceof MessagingPayload message && message.destination() != null) {
            return List.of(new Target(
                    message.sent() ? EdgeType.PUBLISHES : EdgeType.CONSUMES,
                    NodeType.DESTINATION,
                    destination(message)));
        }
        if (payload instanceof ExceptionPayload exception && exception.groupId() != null) {
            return List.of(new Target(EdgeType.RAISES, NodeType.EXCEPTION_GROUP, exception.groupId()));
        }
        return List.of();
    }

    /**
     * The key of the execution that owns {@code event}'s work: its request, else its execution, else {@code null}. An
     * event owned by no execution adds no edge: a statement on an executor without context never joins a route by time.
     */
    public static String ownerKey(RuntimeEvent event) {
        if (event.requestId() != null) {
            return "request:" + event.requestId();
        }
        return event.executionId() == null ? null : "execution:" + event.executionId();
    }

    /** The key under which {@code execution}, completed by {@code event}, owns the work of its request or execution. */
    public static String executionKey(RuntimeEvent event, Execution execution) {
        return execution.request() ? "request:" + event.requestId() : "execution:" + event.executionId();
    }

    /** Whether {@code event} has no execution but joins one by trace id: an AI span exported after its request. */
    public static boolean ownedByTrace(RuntimeEvent event) {
        return event.requestId() == null
                && event.executionId() == null
                && event.traceId() != null
                && event.payload() instanceof AiPayload;
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

    /** An execution node, by its type and key. */
    public record Execution(NodeType type, String key) {

        public Execution {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(key, "key");
        }

        /** Whether this execution is a request, which an AI span exported after it joins by trace id. */
        public boolean request() {
            return type == NodeType.ROUTE || type == NodeType.GRAPHQL_OPERATION;
        }

        /** The edge from this execution to {@code target}. */
        public EdgeDiff.EdgeRef to(Target target) {
            return new EdgeDiff.EdgeRef(type, key, target.type(), target.nodeType(), target.key());
        }
    }

    /** The far end of an observed edge: what the edge says and the node it reaches. */
    public record Target(EdgeType type, NodeType nodeType, String key) {

        public Target {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(nodeType, "nodeType");
            Objects.requireNonNull(key, "key");
        }
    }
}
