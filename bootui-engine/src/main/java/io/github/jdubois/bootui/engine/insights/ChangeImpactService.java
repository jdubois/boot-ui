package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactRouteDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactSymbolDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactSymbolsDto;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.VisibleJournalEntries;
import io.github.jdubois.bootui.engine.model.AppEventPublications;
import io.github.jdubois.bootui.engine.model.EdgeType;
import io.github.jdubois.bootui.engine.model.ModelEdge;
import io.github.jdubois.bootui.engine.model.ModelNode;
import io.github.jdubois.bootui.engine.model.NodeType;
import io.github.jdubois.bootui.engine.model.ReverseClosure;
import io.github.jdubois.bootui.engine.model.RuntimeModel;
import io.github.jdubois.bootui.engine.model.RuntimeModelService;
import io.github.jdubois.bootui.engine.model.StructureSnapshot;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.sqltrace.RouteLabel;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Change impact ({@code docs/PLAN-v2.md} §5.7): resolves a symbol, such as a route, a bean, a class's simple name, a
 * repository, a table, a cache, or an outbound host, to exactly one node of the runtime model, and lists what reaches
 * it, worded as what was and was not exercised in this run. Structural reach is a count; observed execution is listed
 * apart. {@link #symbols(String)} suggests the symbols it can check as a developer types.
 */
public final class ChangeImpactService {

    public static final String RESOLVED = "RESOLVED";
    public static final String AMBIGUOUS = "AMBIGUOUS";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    private static final Set<NodeType> SYMBOLS = EnumSet.of(
            NodeType.ROUTE,
            NodeType.GRAPHQL_OPERATION,
            NodeType.BEAN,
            NodeType.REPOSITORY,
            NodeType.TABLE,
            NodeType.CACHE,
            NodeType.HOST,
            NodeType.EVENT);
    // A route is its own impact: nothing in the model reaches it, so it is listed itself, with the routes sharing
    // what it touched.
    private static final Set<NodeType> ROUTES = EnumSet.of(NodeType.ROUTE, NodeType.GRAPHQL_OPERATION);
    // An application event is shared like a table: its publishers and listeners reach it through access edges (M4-8).
    private static final Set<NodeType> RESOURCES =
            EnumSet.of(NodeType.TABLE, NodeType.CACHE, NodeType.HOST, NodeType.EVENT);
    // INVOKES (observed bean calls from Code Paths, M5-4c) is deliberately left out (design I8): a call observed in
    // this
    // run says which paths the requests sent happened to take, not what a change can reach, so the closure would change
    // with the traffic; the declared DEPENDS_ON edges already reach every bean that injects the changed one.
    public static final Set<EdgeType> CODE =
            java.util.Collections.unmodifiableSet(EnumSet.of(EdgeType.DEPENDS_ON, EdgeType.HANDLED_BY));
    private static final Set<EdgeType> ACCESS =
            EnumSet.of(EdgeType.READS, EdgeType.WRITES, EdgeType.CALLS, EdgeType.PUBLISHES, EdgeType.CONSUMES);
    private static final Set<JournalSource> IMPACT_SOURCES = EnumSet.of(
            JournalSource.SQL,
            JournalSource.CACHE,
            JournalSource.REST_CLIENT,
            JournalSource.AI,
            JournalSource.MESSAGING,
            JournalSource.SCHEDULED,
            JournalSource.WEBSOCKET,
            JournalSource.AUTHORIZATION);

    private final RuntimeJournal journal;
    private final JournalAggregates aggregates;
    private final RuntimeModelService models;
    private final Supplier<RouteTemplateResolver> routes;
    private final Predicate<String> panelEnabled;

    private volatile InsightsStack stack;

    /**
     * @param journal the journal, or {@code null} when the adapter created none
     * @param aggregates its aggregates, or {@code null}
     * @param models the run's model, read with the application's structure
     * @param routes the application's declared routes, or {@code null}
     * @param panelEnabled the live panel policy by panel id
     */
    public ChangeImpactService(
            RuntimeJournal journal,
            JournalAggregates aggregates,
            RuntimeModelService models,
            Supplier<RouteTemplateResolver> routes,
            Predicate<String> panelEnabled) {
        this.journal = journal;
        this.aggregates = aggregates;
        this.models = models;
        this.routes = routes == null ? RouteTemplateResolver::empty : routes;
        this.panelEnabled = Objects.requireNonNull(panelEnabled, "panelEnabled");
    }

    /**
     * Names the stack serving this application, so an impact it cannot fully trace says so. Without it, nothing is
     * claimed beyond what the model holds.
     */
    public void setStack(InsightsStack stack) {
        this.stack = stack;
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
                    "Name a route, a bean, a class, a repository, a table, a cache, or a host.",
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
        Map<String, Boolean> visible = visibility();
        if (!visible.getOrDefault(BootUiPanels.HTTP_EXCHANGES, false)) {
            return unavailable(
                    asked, "The HTTP Exchanges panel is disabled, so this run's route traffic is unavailable.");
        }
        RuntimeModel model = models.model(visible);
        List<JournalEntry> recorded = journal.entries();
        Set<String> omitted = omittedEvidence(recorded, visible);
        List<JournalEntry> entries = visibleEntries(recorded, visible);
        StructureSnapshot structure = models.structure();
        HandlerMethod method = HandlerMethod.parse(asked);
        if (method != null) {
            return method(asked, model, structure, method, entries, visible, omitted);
        }
        List<ModelNode> candidates = candidates(model, structure, asked);
        if (candidates.isEmpty()) {
            if (structure.beansUnavailable() != null) {
                return unavailable(asked, structure.beansUnavailable());
            }
            return new RuntimeChangeImpactDto(
                    NOT_FOUND,
                    "No route, bean, repository, table, cache, or host named `" + asked + "` is in this run's model.",
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
                    limitations(model, omitted));
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
        return resolved(asked, model, structure, candidates.get(0), entries, visible, omitted);
    }

    private RuntimeChangeImpactDto resolved(
            String asked,
            RuntimeModel model,
            StructureSnapshot structure,
            ModelNode start,
            List<JournalEntry> entries,
            Map<String, Boolean> visible,
            Set<String> omitted) {
        return resolved(asked, model, structure, start, null, entries, visible, omitted);
    }

    /**
     * What reaches {@code start}, or, for a handler method, which has no node of its own, only the routes mapped to it,
     * with {@code start} {@code null}.
     */
    private RuntimeChangeImpactDto resolved(
            String asked,
            RuntimeModel model,
            StructureSnapshot structure,
            ModelNode start,
            MappedMethod mapped,
            List<JournalEntry> entries,
            Map<String, Boolean> visible,
            Set<String> omitted) {
        boolean resource = start != null && RESOURCES.contains(start.type());
        boolean routeStart = start != null && ROUTES.contains(start.type());
        Map<Integer, Integer> closure = mapped != null
                ? mapped.reach(model)
                : ReverseClosure.of(model, start.id(), resource ? ACCESS : CODE, ReverseClosure.MAX_DEPTH);
        Map<String, RouteStats> stats = new HashMap<>();
        var aggregate = aggregates.snapshot();
        aggregate.routes().forEach(route -> stats.put(route.route(), route));
        boolean routeOverflow = aggregate.overflowed().getOrDefault("routes", 0L) > 0;
        Map<String, List<String>> exemplars = exemplars(entries);
        List<RuntimeImpactRouteDto> observed = new ArrayList<>();
        List<RuntimeImpactRouteDto> notExercised = new ArrayList<>();
        boolean undetermined = false;
        Set<Integer> observedRoutes = new LinkedHashSet<>();
        List<Integer> reached = new ArrayList<>();
        if (routeStart) {
            reached.add(start.id());
        }
        reached.addAll(closure.keySet());
        for (int id : reached) {
            ModelNode node = model.node(id);
            if (node.type() != NodeType.ROUTE && node.type() != NodeType.GRAPHQL_OPERATION) {
                continue;
            }
            RouteStats routeStats = stats.get(node.key());
            if ((routeStats != null && routeStats.requests() > 0) || model.executions(id) > 0) {
                observedRoutes.add(id);
                observed.add(row(model, node, stats, exemplars, List.of(), null, visible));
            } else if (!routeOverflow) {
                notExercised.add(row(
                        model,
                        node,
                        stats,
                        exemplars,
                        List.of(),
                        "Exercise `" + node.key() + "` before relying on this change: no request reached it in this"
                                + " run.",
                        visible));
            } else {
                undetermined = true;
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
                        && (start == null || edge.from() != start.id())) {
                    sharedBy.computeIfAbsent(edge.from(), ignored -> new LinkedHashSet<>())
                            .add(label(model.node(resourceId)));
                }
            }
        }
        sharedBy.forEach((id, resources) ->
                shared.add(row(model, model.node(id), stats, exemplars, List.copyOf(resources), null, visible)));
        Comparator<RuntimeImpactRouteDto> busiest = Comparator.comparingLong(RuntimeImpactRouteDto::requests)
                .reversed()
                .thenComparing(RuntimeImpactRouteDto::route);
        observed.sort(busiest);
        shared.sort(busiest);
        notExercised.sort(Comparator.comparing(RuntimeImpactRouteDto::route));
        List<String> limitations = new ArrayList<>(limitations(model, omitted));
        if (undetermined) {
            limitations.add("The route aggregate reached its cardinality limit: routes absent from its counts and"
                    + " the retained journal cannot be classified as not exercised.");
        }
        limitations.add("A route listed as observed ran in this run and reaches the changed code in the structure; its"
                + " traffic does not prove that a request went through it.");
        if (!resource) {
            limitations.add("Shared resources are those the observed routes touched, which may include what other code"
                    + " on those routes touched. Tables are read from the statement text.");
        }
        if (routeStart) {
            limitations.add("A route reaches no other route through code: name a bean its handler calls to see the"
                    + " routes that share that code.");
        }
        if (mapped != null) {
            limitations.add("A method is checked through the routes mapped to it as their handler: other code calling"
                    + " it is not counted, and its overloads count as one method. Name its class to check the whole"
                    + " bean.");
        }
        if (structure.beansUnavailable() != null) {
            limitations.add(structure.beansUnavailable());
        }
        String unrecordedPublications = AppEventPublications.unrecordedReason(stack);
        if (unrecordedPublications != null) {
            limitations.add(unrecordedPublications);
        }
        return new RuntimeChangeImpactDto(
                RESOLVED,
                null,
                asked,
                mapped != null ? mapped.label() : label(start),
                List.of(),
                closure.size(),
                capped(observed),
                observed.size(),
                capped(notExercised),
                notExercised.size(),
                capped(shared),
                shared.size(),
                limitations,
                undetermined);
    }

    /**
     * A handler method's impact: the routes mapped to exactly one handler class's method of that name, ambiguous when
     * classes in several packages share the simple name asked for, and never guessed when no route is mapped to it.
     */
    private RuntimeChangeImpactDto method(
            String asked,
            RuntimeModel model,
            StructureSnapshot structure,
            HandlerMethod method,
            List<JournalEntry> entries,
            Map<String, Boolean> visible,
            Set<String> omitted) {
        Map<String, List<String>> routesByHandler = new HashMap<>();
        for (StructureSnapshot.RouteHandler route : structure.routes()) {
            if (method.handles(route)) {
                routesByHandler
                        .computeIfAbsent(route.handlerClass(), ignored -> new ArrayList<>())
                        .add(route.route());
            }
        }
        if (routesByHandler.isEmpty()) {
            return new RuntimeChangeImpactDto(
                    NOT_FOUND,
                    "No route in this run is mapped to a handler method `" + method.name() + "` of `" + method.type()
                            + "`: name its class to check the whole bean.",
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
                    limitations(model, omitted));
        }
        List<String> handlers = routesByHandler.keySet().stream().sorted().toList();
        if (handlers.size() > 1) {
            return new RuntimeChangeImpactDto(
                    AMBIGUOUS,
                    "`" + asked + "` names " + handlers.size() + " handler methods: name one of them.",
                    asked,
                    null,
                    handlers.stream()
                            .limit(RuntimeChangeImpactDto.MAX_ROWS)
                            .map(handler -> new MappedMethod(handler + "#" + method.name(), List.of()).label())
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
        String handler = handlers.get(0);
        return resolved(
                asked,
                model,
                structure,
                null,
                new MappedMethod(handler + "#" + method.name(), routesByHandler.get(handler)),
                entries,
                visible,
                omitted);
    }

    /**
     * A method symbol, such as {@code ProductController#list}, {@code com.example.ProductController#list(Pageable)}, or
     * a candidate's {@code METHOD com.example.ProductController#list}.
     *
     * @param type the class as asked, fully qualified or simple
     * @param name the method's name; parameter types are not compared
     */
    private record HandlerMethod(String type, String name) {

        static final String KIND = "METHOD";

        /** The method {@code symbol} names, or {@code null} when it does not name one. */
        static HandlerMethod parse(String symbol) {
            String text = symbol.startsWith(KIND + " ")
                    ? symbol.substring(KIND.length() + 1).strip()
                    : symbol;
            int hash = text.indexOf('#');
            if (hash <= 0 || text.indexOf('#', hash + 1) >= 0) {
                return null;
            }
            String type = text.substring(0, hash);
            String name = text.substring(hash + 1);
            int parenthesis = name.indexOf('(');
            if (parenthesis >= 0) {
                if (!name.endsWith(")")) {
                    return null;
                }
                name = name.substring(0, parenthesis);
            }
            return javaName(type) && javaName(name) && !name.contains(".") ? new HandlerMethod(type, name) : null;
        }

        boolean handles(StructureSnapshot.RouteHandler route) {
            String handler = route.handlerClass();
            return handler != null
                    && name.equals(route.handlerMethod())
                    && (handler.equals(type)
                            || handler.replace('$', '.').equals(type)
                            || simpleName(handler).equals(type));
        }

        private static boolean javaName(String text) {
            if (text.isEmpty()) {
                return false;
            }
            for (String part : text.split("\\.", -1)) {
                if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                    return false;
                }
                for (int i = 1; i < part.length(); i++) {
                    if (!Character.isJavaIdentifierPart(part.charAt(i))) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /**
     * A handler method resolved to its class and the routes mapped to it.
     *
     * @param handler its fully qualified class and name, such as {@code com.example.ProductController#list}
     * @param routes the labels of the routes mapped to it
     */
    private record MappedMethod(String handler, List<String> routes) {

        String label() {
            return HandlerMethod.KIND + " " + handler;
        }

        /** The model's nodes for its routes, each one step from the method, as a closure would give them. */
        Map<Integer, Integer> reach(RuntimeModel model) {
            Map<Integer, Integer> reach = new HashMap<>();
            for (String route : routes) {
                model.node(NodeType.ROUTE, route)
                        .or(() -> model.node(NodeType.GRAPHQL_OPERATION, route))
                        .ifPresent(node -> reach.put(node.id(), 1));
            }
            return reach;
        }
    }

    private static List<ModelNode> candidates(RuntimeModel model, StructureSnapshot structure, String symbol) {
        Map<String, String> types = beanTypes(structure);
        NodeType kind = kind(symbol);
        if (kind != null) {
            // A candidate's or a suggestion's label, such as "TABLE sample_products", names exactly one node.
            String key = symbol.substring(kind.name().length()).strip();
            List<ModelNode> typed = model.nodes().stream()
                    .filter(node -> node.type() == kind && node.key().equalsIgnoreCase(key))
                    .toList();
            if (!typed.isEmpty()) {
                return typed;
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
                    || (type != null && (type.equals(symbol) || simpleName(type).equals(symbol)))
                    || (node.type() == NodeType.EVENT && simpleName(node.key()).equals(symbol));
            if (matches) {
                found.add(node);
            }
        }
        return found;
    }

    /**
     * The symbols {@link #impact(String)} can check whose name, or whose bean class, contains {@code query}, best matches
     * first: an exact name, then a name or a route's path that starts with it, then a class that does, then any name or
     * class containing it. It reads only the run's model.
     */
    public RuntimeImpactSymbolsDto symbols(String query) {
        String asked = query == null ? "" : query.strip();
        if (journal == null
                || aggregates == null
                || models == null
                || !journal.settings().enabled()) {
            return new RuntimeImpactSymbolsDto(
                    false,
                    "The runtime journal is disabled: set bootui.runtime-journal.enabled=true.",
                    asked,
                    List.of(),
                    0);
        }
        Map<String, Boolean> visible = visibility();
        if (!visible.getOrDefault(BootUiPanels.HTTP_EXCHANGES, false)) {
            return new RuntimeImpactSymbolsDto(
                    false,
                    "The HTTP Exchanges panel is disabled, so route traffic is unavailable.",
                    asked,
                    List.of(),
                    0);
        }
        RuntimeModel model = models.model(visible);
        Map<String, String> types = beanTypes(models.structure());
        String wanted = asked.toLowerCase(Locale.ROOT);
        record Match(ModelNode node, String type, int rank) {}
        List<Match> matches = new ArrayList<>();
        for (ModelNode node : model.nodes()) {
            if (!SYMBOLS.contains(node.type())) {
                continue;
            }
            String type =
                    node.type() == NodeType.BEAN || node.type() == NodeType.REPOSITORY ? types.get(node.key()) : null;
            int rank = rank(node, type, wanted);
            if (rank >= 0) {
                matches.add(new Match(node, type, rank));
            }
        }
        matches.sort(Comparator.comparingInt(Match::rank)
                .thenComparing(match -> match.node().type())
                .thenComparing(match -> match.node().key(), String.CASE_INSENSITIVE_ORDER));
        return new RuntimeImpactSymbolsDto(
                true,
                null,
                asked,
                matches.stream()
                        .limit(RuntimeImpactSymbolsDto.MAX_SYMBOLS)
                        .map(match -> new RuntimeImpactSymbolDto(
                                match.node().type().name(), match.node().key(), match.type()))
                        .toList(),
                matches.size());
    }

    /** How well {@code node} matches a lowercase query, lowest first, or {@code -1} when it does not. */
    private static int rank(ModelNode node, String type, String wanted) {
        String key = node.key().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty() || key.equals(wanted)) {
            return 0;
        }
        if (key.startsWith(wanted)
                || (ROUTES.contains(node.type())
                        && key.substring(key.indexOf(' ') + 1).startsWith(wanted))) {
            return 1;
        }
        String fullType = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (!fullType.isEmpty() && simpleName(fullType).startsWith(wanted)) {
            return 2;
        }
        if (key.contains(wanted)) {
            return 3;
        }
        return fullType.contains(wanted) ? 4 : -1;
    }

    /** The node type a symbol such as {@code TABLE sample_products} starts with, or {@code null}. */
    private static NodeType kind(String symbol) {
        int space = symbol.indexOf(' ');
        if (space < 0) {
            return null;
        }
        String prefix = symbol.substring(0, space);
        for (NodeType type : SYMBOLS) {
            if (type.name().equals(prefix)) {
                return type;
            }
        }
        return null;
    }

    private static Map<String, String> beanTypes(StructureSnapshot structure) {
        Map<String, String> types = new HashMap<>();
        for (StructureSnapshot.Bean bean : structure.beans()) {
            if (bean.type() != null) {
                types.put(bean.name(), bean.type());
            }
        }
        return types;
    }

    private RuntimeImpactRouteDto row(
            RuntimeModel model,
            ModelNode node,
            Map<String, RouteStats> stats,
            Map<String, List<String>> exemplars,
            List<String> shared,
            String check,
            Map<String, Boolean> visible) {
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
                route == null || !visible.getOrDefault(BootUiPanels.SECURITY_LOGS, false)
                        ? 0
                        : route.authorization().anonymous(),
                route == null ? 0 : route.statusClasses().get(4),
                exemplars.getOrDefault(node.key(), List.of()),
                reads,
                writes,
                shared,
                check);
    }

    /** Up to three of each route's most recent retained requests. */
    private Map<String, List<String>> exemplars(List<JournalEntry> visibleEntries) {
        RouteTemplateResolver resolver;
        try {
            resolver = routes.get();
        } catch (RuntimeException ex) {
            resolver = RouteTemplateResolver.empty();
        }
        Map<String, Deque<String>> recent = new HashMap<>();
        List<JournalEntry> entries = new ArrayList<>(visibleEntries);
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

    private Map<String, Boolean> visibility() {
        Map<String, Boolean> visible = new LinkedHashMap<>();
        for (String panel : JournalSourcePanels.owningPanels()) {
            boolean enabled;
            try {
                enabled = panelEnabled.test(panel);
            } catch (RuntimeException ex) {
                enabled = false;
            }
            visible.put(panel, enabled);
        }
        return visible;
    }

    private static List<JournalEntry> visibleEntries(List<JournalEntry> entries, Map<String, Boolean> visible) {
        return VisibleJournalEntries.of(entries, event -> {
            String panel = JournalSourcePanels.panelOf(event);
            return panel == null || visible.getOrDefault(panel, false);
        });
    }

    private Set<String> omittedEvidence(List<JournalEntry> entries, Map<String, Boolean> visible) {
        Set<String> omitted = new LinkedHashSet<>();
        for (JournalEntry entry : entries) {
            if (!IMPACT_SOURCES.contains(entry.event().source())) {
                continue;
            }
            String panel = JournalSourcePanels.panelOf(entry.event());
            if (panel != null && !visible.getOrDefault(panel, false)) {
                omitted.add(panel);
            }
        }
        if (!visible.getOrDefault(BootUiPanels.SECURITY_LOGS, false)
                && aggregates.snapshot().routes().stream()
                        .anyMatch(route -> route.authorization().decided() > 0)) {
            omitted.add(BootUiPanels.SECURITY_LOGS);
        }
        return omitted;
    }

    private static List<String> limitations(RuntimeModel model, Set<String> omitted) {
        List<String> limits = new ArrayList<>(model.limitations());
        for (String panel : JournalSourcePanels.owningPanels()) {
            if (omitted.contains(panel)) {
                limits.add(
                        "The " + panel + " panel is disabled, so its journal evidence is left out of change impact.");
            }
        }
        return limits;
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
