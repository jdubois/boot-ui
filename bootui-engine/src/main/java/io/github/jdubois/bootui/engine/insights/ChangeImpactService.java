package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactRouteDto;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.model.EdgeType;
import io.github.jdubois.bootui.engine.model.ModelEdge;
import io.github.jdubois.bootui.engine.model.ModelNode;
import io.github.jdubois.bootui.engine.model.NodeType;
import io.github.jdubois.bootui.engine.model.ReverseClosure;
import io.github.jdubois.bootui.engine.model.RuntimeModel;
import io.github.jdubois.bootui.engine.model.RuntimeModelService;
import io.github.jdubois.bootui.engine.model.StructureSnapshot;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Change impact ({@code docs/PLAN-v2.md} §5.7): resolves a symbol, such as a bean, a class's simple name, a repository,
 * a table, a cache, or an outbound host, to exactly one node of the runtime model, and lists what reaches it, worded as
 * what was and was not exercised in this run. Structural reach is a count; observed execution is listed apart.
 */
public final class ChangeImpactService {

    public static final String RESOLVED = "RESOLVED";
    public static final String AMBIGUOUS = "AMBIGUOUS";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    private static final Set<NodeType> SYMBOLS =
            EnumSet.of(NodeType.BEAN, NodeType.REPOSITORY, NodeType.TABLE, NodeType.CACHE, NodeType.HOST);
    private static final Set<NodeType> RESOURCES = EnumSet.of(NodeType.TABLE, NodeType.CACHE, NodeType.HOST);
    private static final Set<EdgeType> CODE = EnumSet.of(EdgeType.DEPENDS_ON, EdgeType.HANDLED_BY);
    private static final Set<EdgeType> ACCESS =
            EnumSet.of(EdgeType.READS, EdgeType.WRITES, EdgeType.CALLS, EdgeType.PUBLISHES, EdgeType.CONSUMES);

    private final RuntimeJournal journal;
    private final JournalAggregates aggregates;
    private final RuntimeModelService models;
    private final Supplier<RouteTemplateResolver> routes;

    /**
     * @param journal the journal, or {@code null} when the adapter created none
     * @param aggregates its aggregates, or {@code null}
     * @param models the run's model, read with the application's structure
     * @param routes the application's declared routes, or {@code null}
     */
    public ChangeImpactService(
            RuntimeJournal journal,
            JournalAggregates aggregates,
            RuntimeModelService models,
            Supplier<RouteTemplateResolver> routes) {
        this.journal = journal;
        this.aggregates = aggregates;
        this.models = models;
        this.routes = routes == null ? RouteTemplateResolver::empty : routes;
    }

    /** What a change to {@code symbol} reaches in this run. */
    public RuntimeChangeImpactDto impact(String symbol) {
        String asked = symbol == null ? "" : symbol.strip();
        if (journal == null
                || aggregates == null
                || models == null
                || !journal.settings().enabled()) {
            return unavailable(asked, "The runtime journal is disabled: set bootui.runtime-journal.enabled=true.");
        }
        if (asked.isEmpty()) {
            return new RuntimeChangeImpactDto(
                    NOT_FOUND,
                    "Name a bean, a class, a repository, a table, a cache, or a host.",
                    asked,
                    null,
                    List.of(),
                    0,
                    List.of(),
                    0,
                    List.of(),
                    0,
                    List.of(),
                    0,
                    List.of());
        }
        RuntimeModel model = models.model();
        StructureSnapshot structure = models.structure();
        List<ModelNode> candidates = candidates(model, structure, asked);
        if (candidates.isEmpty()) {
            if (structure.beansUnavailable() != null) {
                return unavailable(asked, structure.beansUnavailable());
            }
            return new RuntimeChangeImpactDto(
                    NOT_FOUND,
                    "No bean, repository, table, cache, or host named `" + asked + "` is in this run's model.",
                    asked,
                    null,
                    List.of(),
                    0,
                    List.of(),
                    0,
                    List.of(),
                    0,
                    List.of(),
                    0,
                    model.limitations());
        }
        if (candidates.size() > 1) {
            return new RuntimeChangeImpactDto(
                    AMBIGUOUS,
                    "`" + asked + "` names " + candidates.size() + " nodes: name one of them.",
                    asked,
                    null,
                    candidates.stream()
                            .limit(RuntimeChangeImpactDto.MAX_ROWS)
                            .map(ChangeImpactService::label)
                            .toList(),
                    0,
                    List.of(),
                    0,
                    List.of(),
                    0,
                    List.of(),
                    0,
                    List.of());
        }
        return resolved(asked, model, structure, candidates.get(0));
    }

    private RuntimeChangeImpactDto resolved(
            String asked, RuntimeModel model, StructureSnapshot structure, ModelNode start) {
        boolean resource = RESOURCES.contains(start.type());
        Map<Integer, Integer> closure =
                ReverseClosure.of(model, start.id(), resource ? ACCESS : CODE, ReverseClosure.MAX_DEPTH);
        Map<String, RouteStats> stats = new HashMap<>();
        aggregates.snapshot().routes().forEach(route -> stats.put(route.route(), route));
        Map<String, List<String>> exemplars = exemplars();
        List<RuntimeImpactRouteDto> observed = new ArrayList<>();
        List<RuntimeImpactRouteDto> notExercised = new ArrayList<>();
        Set<Integer> observedRoutes = new LinkedHashSet<>();
        for (int id : closure.keySet()) {
            ModelNode node = model.node(id);
            if (node.type() != NodeType.ROUTE && node.type() != NodeType.GRAPHQL_OPERATION) {
                continue;
            }
            if (model.executions(id) > 0) {
                observedRoutes.add(id);
                observed.add(row(model, node, stats, exemplars, List.of(), null));
            } else {
                notExercised.add(row(
                        model,
                        node,
                        stats,
                        exemplars,
                        List.of(),
                        "Exercise `" + node.key() + "` before relying on this change: no request reached it in this"
                                + " run."));
            }
        }
        Set<Integer> touched = new LinkedHashSet<>();
        if (resource) {
            touched.add(start.id());
        } else {
            for (int route : observedRoutes) {
                for (ModelEdge edge : model.outgoing(route)) {
                    if (ACCESS.contains(edge.type())
                            && RESOURCES.contains(model.node(edge.to()).type())) {
                        touched.add(edge.to());
                    }
                }
            }
        }
        List<RuntimeImpactRouteDto> shared = new ArrayList<>();
        Map<Integer, Set<String>> sharedBy = new HashMap<>();
        for (int resourceId : touched) {
            for (ModelEdge edge : model.incoming(resourceId)) {
                ModelNode from = model.node(edge.from());
                if (ACCESS.contains(edge.type())
                        && (from.type() == NodeType.ROUTE || from.type() == NodeType.GRAPHQL_OPERATION)
                        && !closure.containsKey(edge.from())
                        && edge.from() != start.id()) {
                    sharedBy.computeIfAbsent(edge.from(), ignored -> new LinkedHashSet<>())
                            .add(label(model.node(resourceId)));
                }
            }
        }
        sharedBy.forEach((id, resources) ->
                shared.add(row(model, model.node(id), stats, exemplars, List.copyOf(resources), null)));
        Comparator<RuntimeImpactRouteDto> busiest = Comparator.comparingLong(RuntimeImpactRouteDto::requests)
                .reversed()
                .thenComparing(RuntimeImpactRouteDto::route);
        observed.sort(busiest);
        shared.sort(busiest);
        notExercised.sort(Comparator.comparing(RuntimeImpactRouteDto::route));
        List<String> limitations = new ArrayList<>(model.limitations());
        limitations.add("A route listed as observed ran in this run and reaches the changed code in the structure; its"
                + " traffic does not prove that a request went through it.");
        if (!resource) {
            limitations.add("Shared resources are those the observed routes touched, which may include what other code"
                    + " on those routes touched. Tables are read from the statement text.");
        }
        if (structure.beansUnavailable() != null) {
            limitations.add(structure.beansUnavailable());
        }
        return new RuntimeChangeImpactDto(
                RESOLVED,
                null,
                asked,
                label(start),
                List.of(),
                closure.size(),
                capped(observed),
                observed.size(),
                capped(notExercised),
                notExercised.size(),
                capped(shared),
                shared.size(),
                limitations);
    }

    private static List<ModelNode> candidates(RuntimeModel model, StructureSnapshot structure, String symbol) {
        Map<String, String> types = new HashMap<>();
        for (StructureSnapshot.Bean bean : structure.beans()) {
            if (bean.type() != null) {
                types.put(bean.name(), bean.type());
            }
        }
        String wanted = symbol.toLowerCase(Locale.ROOT);
        List<ModelNode> found = new ArrayList<>();
        for (ModelNode node : model.nodes()) {
            if (!SYMBOLS.contains(node.type())) {
                continue;
            }
            String type = types.get(node.key());
            boolean matches = node.key().toLowerCase(Locale.ROOT).equals(wanted)
                    || (type != null && (type.equals(symbol) || simpleName(type).equals(symbol)));
            if (matches) {
                found.add(node);
            }
        }
        return found;
    }

    private RuntimeImpactRouteDto row(
            RuntimeModel model,
            ModelNode node,
            Map<String, RouteStats> stats,
            Map<String, List<String>> exemplars,
            List<String> shared,
            String check) {
        RouteStats route = stats.get(node.key());
        List<String> reads = new ArrayList<>();
        List<String> writes = new ArrayList<>();
        for (ModelEdge edge : model.outgoing(node.id())) {
            ModelNode target = model.node(edge.to());
            if (edge.type() == EdgeType.READS) {
                reads.add(label(target));
            } else if (edge.type() == EdgeType.WRITES) {
                writes.add(label(target));
            }
        }
        return new RuntimeImpactRouteDto(
                node.key(),
                route == null ? model.executions(node.id()) : route.requests(),
                route == null ? 0 : route.authorization().anonymous(),
                route == null ? 0 : route.statusClasses().get(4),
                exemplars.getOrDefault(node.key(), List.of()),
                reads,
                writes,
                shared,
                check);
    }

    /** Up to three of each route's most recent retained requests. */
    private Map<String, List<String>> exemplars() {
        RouteTemplateResolver resolver;
        try {
            resolver = routes.get();
        } catch (RuntimeException ex) {
            resolver = RouteTemplateResolver.empty();
        }
        Map<String, Deque<String>> recent = new HashMap<>();
        List<JournalEntry> entries = new ArrayList<>(journal.entries());
        entries.sort(Comparator.comparingLong(JournalEntry::sequence));
        for (JournalEntry entry : entries) {
            RuntimeEvent event = entry.event();
            if (event.payload() instanceof HttpPayload http && event.requestId() != null) {
                String label = RouteLabel.of(
                                http.method(), http.path(), http.routeTemplate(), http.operation(), resolver)
                        .id();
                Deque<String> ids = recent.computeIfAbsent(label, ignored -> new ArrayDeque<>());
                ids.addFirst(event.requestId());
                if (ids.size() > 3) {
                    ids.removeLast();
                }
            }
        }
        Map<String, List<String>> exemplars = new HashMap<>();
        recent.forEach((route, ids) -> exemplars.put(route, List.copyOf(ids)));
        return exemplars;
    }

    private static List<RuntimeImpactRouteDto> capped(List<RuntimeImpactRouteDto> rows) {
        return rows.size() <= RuntimeChangeImpactDto.MAX_ROWS ? rows : rows.subList(0, RuntimeChangeImpactDto.MAX_ROWS);
    }

    private static String label(ModelNode node) {
        return node.type().name() + " " + node.key();
    }

    private static String simpleName(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    private static RuntimeChangeImpactDto unavailable(String symbol, String reason) {
        return new RuntimeChangeImpactDto(
                UNAVAILABLE, reason, symbol, null, List.of(), 0, List.of(), 0, List.of(), 0, List.of(), 0, List.of());
    }
}
