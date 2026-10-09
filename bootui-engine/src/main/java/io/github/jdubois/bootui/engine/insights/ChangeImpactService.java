package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.core.dto.RuntimeChangeImpactDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactRouteDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactSymbolDto;
import io.github.jdubois.bootui.core.dto.RuntimeImpactSymbolsDto;
import io.github.jdubois.bootui.engine.codepaths.MethodRoutes;
import io.github.jdubois.bootui.engine.codepaths.TracedMethods;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalAggregates;
import io.github.jdubois.bootui.engine.journal.JournalAggregates.RouteStats;
import io.github.jdubois.bootui.engine.journal.JournalCompleteness;
import io.github.jdubois.bootui.engine.journal.JournalEntry;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.JournalSourcePanels;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.VisibleJournalEntries;
import io.github.jdubois.bootui.engine.model.AppEventPublications;
import io.github.jdubois.bootui.engine.model.BeanInvocations;
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
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;
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

    /** The route names a truncated list names at most beyond its rows. */
    static final int MAX_UNLISTED = 40;

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
    private volatile Supplier<AppEventCapture> appEventCapture;
    private volatile Function<Predicate<String>, MethodRoutes> codePaths;
    private volatile BiFunction<String, String, CodeInventoryService.MethodLookup> inventory;

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

    /**
     * Installs whether this application's application events are recorded ({@link AppEventCapture}), so an impact that
     * could reach code through an event says when none is recorded. Without it, they are assumed recorded.
     */
    public void setAppEventCapture(Supplier<AppEventCapture> appEventCapture) {
        this.appEventCapture = appEventCapture;
    }

    /**
     * Why a reach through an application event is not counted because this application's events are not recorded, or
     * {@code null} when they are, or the journal does not record the {@code app-event} source anyway.
     */
    private String unrecordedAppEvents() {
        Supplier<AppEventCapture> supplier = appEventCapture;
        if (supplier == null || !journal.records(JournalSource.APP_EVENT)) {
            return null;
        }
        AppEventCapture capture = AppEventCapture.read(supplier);
        if (capture.recorded()) {
            return null;
        }
        return capture.reason() + " A reach that exists only through an event is not counted.";
    }

    /**
     * Installs how the routes whose requests executed a method are read, such as {@link
     * io.github.jdubois.bootui.engine.codepaths.CodePathsService#methodRoutes}, so a method's observed routes come from
     * the route trees (M5-7a); without it, only handler methods are checked.
     */
    public void setCodePaths(Function<Predicate<String>, MethodRoutes> codePaths) {
        this.codePaths = codePaths;
    }

    /**
     * Installs how a method's overloads, status, and first route are read from Code Inventory, such as {@link
     * CodeInventoryService#lookup} (M5-7a).
     */
    public void setCodeInventory(BiFunction<String, String, CodeInventoryService.MethodLookup> inventory) {
        this.inventory = inventory;
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
                    "Name a route, a bean, a class, a method, a repository, a table, a cache, or a host.",
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
        MethodSymbol method = MethodSymbol.parse(asked, false);
        if (method != null) {
            return method(asked, model, structure, method, entries, visible, omitted, false);
        }
        List<ModelNode> candidates = candidates(model, structure, asked);
        if (candidates.isEmpty()) {
            MethodSymbol dotted = MethodSymbol.parse(asked, true);
            RuntimeChangeImpactDto byMethod =
                    dotted == null ? null : method(asked, model, structure, dotted, entries, visible, omitted, true);
            if (byMethod != null) {
                return byMethod;
            }
            // A bare name, such as applyDiscount, names that method of any class: one class resolves, several are
            // ambiguous, each candidate naming its class.
            MethodSymbol bare = MethodSymbol.anyClass(asked);
            byMethod = bare == null ? null : method(asked, model, structure, bare, entries, visible, omitted, true);
            if (byMethod != null) {
                return byMethod;
            }
            if (structure.beansUnavailable() != null) {
                return unavailable(asked, structure.beansUnavailable());
            }
            List<String> notFoundLimitations = new ArrayList<>(limitations(model, omitted));
            String unrecordedEvents = unrecordedAppEvents();
            if (unrecordedEvents != null) {
                notFoundLimitations.add(unrecordedEvents);
            }
            return new RuntimeChangeImpactDto(
                    NOT_FOUND,
                    "No route, bean, repository, table, cache, host, or method named `" + asked
                            + "` is in this run's model.",
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
                    notFoundLimitations);
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
        return resolved(asked, model, structure, start, null, entries, visible, omitted, List.of());
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
            Set<String> omitted,
            List<String> extraLimitations) {
        boolean resource = start != null && RESOURCES.contains(start.type());
        boolean routeStart = start != null && ROUTES.contains(start.type());
        Map<Integer, Integer> closure = mapped != null
                ? mapped.reach(model)
                : ReverseClosure.of(model, start.id(), resource ? ACCESS : CODE, ReverseClosure.MAX_DEPTH);
        Map<String, RouteStats> stats = new HashMap<>();
        var aggregate = aggregates.snapshot();
        JournalCompleteness.impactRoutes(aggregate).forEach(route -> stats.put(route.route(), route));
        boolean routeOverflow = aggregate.overflowed().getOrDefault("routes", 0L) > 0;
        String absenceReason = JournalCompleteness.absenceReason(journal, aggregate, "routes");
        Map<String, List<String>> exemplars = exemplars(entries);
        List<RuntimeImpactRouteDto> observed = new ArrayList<>();
        List<RuntimeImpactRouteDto> notExercised = new ArrayList<>();
        boolean undetermined = absenceReason != null;
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
            } else if (!routeOverflow && absenceReason == null) {
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
        List<RuntimeImpactRouteDto> shared =
                sharedResources(model, observedRoutes, closure, resource ? start : null, stats, exemplars, visible);
        if (start != null) {
            shared.removeIf(row -> row.route().equals(start.key()) && ROUTES.contains(start.type()));
        }
        Comparator<RuntimeImpactRouteDto> busiest = Comparator.comparingLong(RuntimeImpactRouteDto::requests)
                .reversed()
                .thenComparing(RuntimeImpactRouteDto::route);
        observed.sort(busiest);
        shared.sort(busiest);
        notExercised.sort(Comparator.comparing(RuntimeImpactRouteDto::route));
        List<String> limitations = new ArrayList<>(limitations(model, omitted));
        if (absenceReason != null) {
            limitations.add(absenceReason);
        }
        if (undetermined && routeOverflow) {
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
        limitations.addAll(extraLimitations);
        if (structure.beansUnavailable() != null) {
            limitations.add(structure.beansUnavailable());
        }
        String unrecordedPublications = AppEventPublications.unrecordedReason(stack);
        if (unrecordedPublications != null) {
            limitations.add(unrecordedPublications);
        }
        String unrecordedEvents = unrecordedAppEvents();
        if (unrecordedEvents != null) {
            limitations.add(unrecordedEvents);
        }
        addUnlisted(limitations, "observed", observed);
        addUnlisted(limitations, "not exercised", notExercised);
        addUnlisted(limitations, "sharing a resource", shared);
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
                undetermined,
                mapped != null ? RuntimeChangeImpactDto.FROM_HANDLER_MAPPING : RuntimeChangeImpactDto.FROM_STRUCTURE,
                List.of(),
                null,
                List.of(),
                0);
    }

    /**
     * The routes outside {@code closure} that touch what the observed routes touched, or, for a resource,
     * {@code resource} itself: they share a table, cache, host, or event with the changed code, not code.
     */
    private List<RuntimeImpactRouteDto> sharedResources(
            RuntimeModel model,
            Set<Integer> observedRoutes,
            Map<Integer, Integer> closure,
            ModelNode resource,
            Map<String, RouteStats> stats,
            Map<String, List<String>> exemplars,
            Map<String, Boolean> visible) {
        Set<Integer> touched = new LinkedHashSet<>();
        if (resource != null) {
            touched.add(resource.id());
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
        Map<Integer, Set<String>> sharedBy = new LinkedHashMap<>();
        for (int resourceId : touched) {
            for (ModelEdge edge : model.incoming(resourceId)) {
                ModelNode from = model.node(edge.from());
                if (ACCESS.contains(edge.type())
                        && ROUTES.contains(from.type())
                        && !closure.containsKey(edge.from())
                        && !observedRoutes.contains(edge.from())) {
                    sharedBy.computeIfAbsent(edge.from(), ignored -> new LinkedHashSet<>())
                            .add(label(model.node(resourceId)));
                }
            }
        }
        List<RuntimeImpactRouteDto> shared = new ArrayList<>();
        sharedBy.forEach((id, resources) ->
                shared.add(row(model, model.node(id), stats, exemplars, List.copyOf(resources), null, visible)));
        return shared;
    }

    // --- methods (M5-7a)
    // ----------------------------------------------------------------------------------------------

    /**
     * A method's impact ({@code docs/PLAN-v2.md} §5.7, §5.17, M5-7a). With the agent's code-paths sensor, its observed
     * routes are those whose requests' own call trees executed it, never routes merely reaching its bean, nor composed
     * from calls observed across requests; a route that reaches it and ran without its trees showing it is listed apart,
     * as not observed, unless Code Inventory saw the method never run in this run. Without the sensor, only a handler
     * method is checked, through the routes mapped to it.
     *
     * @param fallback whether {@code Class.name} was tried only because no other symbol matched: answers {@code null}
     *     instead of not found
     */
    private RuntimeChangeImpactDto method(
            String asked,
            RuntimeModel model,
            StructureSnapshot structure,
            MethodSymbol method,
            List<JournalEntry> entries,
            Map<String, Boolean> visible,
            Set<String> omitted,
            boolean fallback) {
        // Traffic first: every request it counts had ended, so its call had set Code Inventory's flag and sent its
        // fragments before either is read, and a route is never said not to have run what one of its requests ran.
        var aggregate = aggregates.snapshot();
        MethodRoutes paths = methodRoutes(method);
        CodeInventoryService.MethodLookup lookup = lookup(method);
        Map<String, Map<String, CodeInventoryService.InventoryMethod>> byClass = new TreeMap<>();
        if (lookup != null) {
            for (CodeInventoryService.InventoryMethod found : lookup.methods()) {
                if (method.name().equals(found.method().name())
                        && method.namesClass(found.method().className())
                        && method.matchesDescriptor(found.method().descriptor())) {
                    byClass.computeIfAbsent(found.method().className(), ignored -> new TreeMap<>())
                            .put(found.method().key(), found);
                }
            }
        }
        Set<String> executedKeys = new LinkedHashSet<>(paths.routesByKey().keySet());
        executedKeys.addAll(paths.unrouted().keySet());
        for (String key : executedKeys) {
            byClass.computeIfAbsent(key.substring(0, key.indexOf('#')), ignored -> new TreeMap<>())
                    .putIfAbsent(key, null);
        }
        Map<String, List<String>> handlers = new TreeMap<>();
        for (StructureSnapshot.RouteHandler route : structure.routes()) {
            if (route.handlerClass() != null
                    && method.name().equals(route.handlerMethod())
                    && method.namesClass(route.handlerClass())) {
                handlers.computeIfAbsent(route.handlerClass(), ignored -> new ArrayList<>())
                        .add(route.route());
                byClass.computeIfAbsent(route.handlerClass(), ignored -> new TreeMap<>());
            }
        }
        if (byClass.isEmpty()) {
            if (fallback) {
                return null;
            }
            if (paths.failed()) {
                return unavailable(asked, paths.unavailableReason());
            }
            List<String> overloads = overloads(method, lookup);
            if (!overloads.isEmpty()) {
                return new RuntimeChangeImpactDto(
                        NOT_FOUND,
                        "No overload of `" + method.name() + "` of `" + method.type() + "` matches `("
                                + method.parameters() + ")" + (method.returns() == null ? "" : method.returns())
                                + "`: this run's Code Inventory has " + String.join(", ", overloads)
                                + ". Name one of them.",
                        asked,
                        null,
                        overloads.stream()
                                .limit(RuntimeChangeImpactDto.MAX_ROWS)
                                .map(key -> MethodSymbol.KIND + " " + key)
                                .toList(),
                        0,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        List.of(),
                        0,
                        limitations(model, omitted));
            }
            String reason = paths.available()
                    ? "No method `" + method.name() + "` of `" + method.type() + "` is in this run's Code Inventory or"
                            + " call trees, and no route is mapped to it as its handler."
                    : "No route in this run is mapped to a handler method `" + method.name() + "` of `"
                            + method.type() + "`: without the BootUI agent's code-paths sensor, only handler methods"
                            + " can be checked (" + stripPeriod(paths.unavailableReason())
                            + "): name its class to check the whole bean.";
            return notFound(asked, reason, limitations(model, omitted));
        }
        if (byClass.size() > 1) {
            List<String> labels = byClass.keySet().stream().map(method::label).toList();
            return new RuntimeChangeImpactDto(
                    AMBIGUOUS,
                    "`" + asked + "` names methods of " + labels.size() + " classes: name one of them.",
                    asked,
                    null,
                    labels.stream().limit(RuntimeChangeImpactDto.MAX_ROWS).toList(),
                    0,
                    List.of(),
                    0,
                    List.of(),
                    0,
                    List.of(),
                    0,
                    List.of());
        }
        String className = byClass.keySet().iterator().next();
        Map<String, CodeInventoryService.InventoryMethod> keys = byClass.get(className);
        List<String> mapped = handlers.getOrDefault(className, List.of());
        if (!paths.available()) {
            if (paths.failed() && mapped.isEmpty()) {
                return unavailable(asked, paths.unavailableReason());
            }
            if (mapped.isEmpty()) {
                return notFound(
                        asked,
                        "`" + className + "#" + method.name() + "` is the handler of no route in this run: without"
                                + " the BootUI agent's code-paths sensor, only handler methods can be checked ("
                                + stripPeriod(paths.unavailableReason()) + "): name its class to check the whole bean.",
                        limitations(model, omitted));
            }
            return resolved(
                    asked,
                    model,
                    structure,
                    null,
                    new MappedMethod(className + "#" + method.name(), mapped),
                    entries,
                    visible,
                    omitted,
                    List.of("With the BootUI agent's code-paths sensor, a method's observed routes are those whose"
                            + " requests executed it, at any depth: " + paths.unavailableReason()));
        }
        return traced(
                asked, model, structure, method, className, keys, mapped, paths, lookup, aggregate, entries, visible,
                omitted);
    }

    /** A method's impact read from the route trees. */
    private RuntimeChangeImpactDto traced(
            String asked,
            RuntimeModel model,
            StructureSnapshot structure,
            MethodSymbol method,
            String className,
            Map<String, CodeInventoryService.InventoryMethod> keys,
            List<String> mapped,
            MethodRoutes paths,
            CodeInventoryService.MethodLookup lookup,
            JournalAggregates.AggregatesSnapshot aggregate,
            List<JournalEntry> entries,
            Map<String, Boolean> visible,
            Set<String> omitted) {
        Map<String, RouteStats> stats = new HashMap<>();
        JournalCompleteness.impactRoutes(aggregate).forEach(route -> stats.put(route.route(), route));
        boolean routeOverflow = aggregate.overflowed().getOrDefault("routes", 0L) > 0;
        String absenceReason = JournalCompleteness.absenceReason(journal, aggregate, "routes");
        Map<String, List<String>> exemplars = exemplars(entries);

        // Observed: the routes whose requests' own trees executed it, or Code Inventory's first request's route.
        Map<String, Long> executed = new LinkedHashMap<>();
        for (String key : keys.keySet()) {
            paths.routesByKey()
                    .getOrDefault(key, Map.of())
                    .forEach((route, count) -> executed.merge(route, count, Math::max));
        }
        Set<String> firstOnly = new LinkedHashSet<>();
        for (CodeInventoryService.InventoryMethod found : keys.values()) {
            String route = found == null ? null : found.method().firstRoute();
            if (route != null
                    && !executed.containsKey(route)
                    && (stats.containsKey(route) || routeNode(model, route) != null)) {
                executed.put(route, 1L);
                firstOnly.add(route);
            }
        }
        List<RuntimeImpactRouteDto> observed = new ArrayList<>();
        Set<Integer> observedIds = new LinkedHashSet<>();
        for (Map.Entry<String, Long> route : executed.entrySet()) {
            ModelNode node = routeNode(model, route.getKey());
            if (node != null) {
                observedIds.add(node.id());
            }
            MethodRoutes.RouteEvidence evidence = paths.routes().get(route.getKey());
            observed.add(routeRow(
                    model,
                    route.getKey(),
                    node,
                    stats,
                    exemplars,
                    List.of(),
                    null,
                    visible,
                    route.getValue(),
                    firstOnly.contains(route.getKey()) || (evidence != null && evidence.partial())));
        }

        // Declared: the routes mapped to it, and those reaching a bean of its class through the bean graph.
        Map<String, String> types = beanTypes(structure);
        Map<Integer, Integer> closure = new HashMap<>();
        boolean bean = false;
        for (ModelNode node : model.nodes()) {
            if ((node.type() == NodeType.BEAN || node.type() == NodeType.REPOSITORY)
                    && className.equals(BeanInvocations.userClass(types.get(node.key())))) {
                bean = true;
                ReverseClosure.of(model, node.id(), CODE, ReverseClosure.MAX_DEPTH)
                        .forEach((id, depth) -> closure.merge(id, depth, Math::min));
            }
        }
        Set<Integer> declared = new LinkedHashSet<>();
        for (String route : mapped) {
            ModelNode node = routeNode(model, route);
            if (node != null) {
                declared.add(node.id());
            }
        }
        for (int id : closure.keySet()) {
            if (ROUTES.contains(model.node(id).type())) {
                declared.add(id);
            }
        }

        boolean proven = neverRan(method, keys, lookup);
        List<String> silence = silence(className, method, keys, paths);
        List<RuntimeImpactRouteDto> notExercised = new ArrayList<>();
        List<RuntimeImpactRouteDto> notObserved = new ArrayList<>();
        boolean undetermined = absenceReason != null;
        for (int id : declared) {
            ModelNode node = model.node(id);
            if (executed.containsKey(node.key())) {
                continue;
            }
            RouteStats routeStats = stats.get(node.key());
            long requests = routeStats != null ? routeStats.requests() : model.executions(id);
            if (requests == 0) {
                if (routeOverflow || absenceReason != null) {
                    undetermined = true;
                } else {
                    notExercised.add(routeRow(
                            model,
                            node.key(),
                            node,
                            stats,
                            exemplars,
                            List.of(),
                            "Exercise `" + node.key() + "` before relying on this change: no request reached it in"
                                    + " this run.",
                            visible,
                            0L,
                            false));
                }
            } else if (proven) {
                notExercised.add(routeRow(
                        model,
                        node.key(),
                        node,
                        stats,
                        exemplars,
                        List.of(),
                        "`" + node.key() + "` served " + requests(requests) + " in this run, and Code Inventory saw"
                                + " the method never run: send a request of `" + node.key() + "` that reaches it.",
                        visible,
                        0L,
                        false));
            } else {
                undetermined = true;
                MethodRoutes.RouteEvidence evidence = paths.routes().get(node.key());
                List<String> why = new ArrayList<>(silence);
                if (evidence == null) {
                    why.add("none of its requests has a call tree");
                } else {
                    if (evidence.requests() < requests) {
                        why.add("only " + evidence.requests() + " of its " + requests(requests) + " have a call tree");
                    }
                    why.addAll(evidence.reasons());
                }
                notObserved.add(routeRow(
                        model,
                        node.key(),
                        node,
                        stats,
                        exemplars,
                        List.of(),
                        "`" + node.key() + "` served " + requests(requests) + " in this run without its call trees"
                                + " showing the method, which does not prove it did not run"
                                + (why.isEmpty() ? "" : ": " + String.join("; ", why)) + ".",
                        visible,
                        0L,
                        evidence == null || evidence.partial()));
            }
        }

        List<RuntimeImpactRouteDto> shared =
                sharedResources(model, observedIds, closure, null, stats, exemplars, visible);
        Comparator<RuntimeImpactRouteDto> busiest = Comparator.comparingLong(RuntimeImpactRouteDto::requests)
                .reversed()
                .thenComparing(RuntimeImpactRouteDto::route);
        observed.sort(Comparator.comparingLong(RuntimeImpactRouteDto::executedRequests)
                .reversed()
                .thenComparing(busiest));
        notObserved.sort(busiest);
        shared.sort(busiest);
        notExercised.sort(Comparator.comparing(RuntimeImpactRouteDto::route));

        List<String> limitations = new ArrayList<>(limitations(model, omitted));
        if (absenceReason != null) {
            limitations.add(absenceReason);
        }
        limitations.add("A route is observed only when its requests' own call trees executed the method, its first"
                + " request, executor work the agent followed, and fragments that arrived late included, or when Code"
                + " Inventory saw a request of it run the method first; route traffic alone never counts, and calls"
                + " observed across requests are never composed into a route.");
        if (!method.overloadNamed() && keys.size() > 1) {
            limitations.add("`" + method.name() + "` names " + keys.size() + " overloads, checked as one method: add"
                    + " its parameter types, such as `" + method.name() + "(String)`, to check one.");
        }
        if (!silence.isEmpty()) {
            limitations.add("The call trees can miss this method: " + String.join("; ", silence) + ".");
        }
        long unrouted = 0;
        for (String key : keys.keySet()) {
            unrouted += paths.unrouted().getOrDefault(key, 0L);
        }
        if (unrouted > 0) {
            limitations.add(unrouted + (unrouted == 1 ? " request tree" : " request trees") + " without a known route,"
                    + " or past the route trees' bounds, executed it.");
        }
        limitations.addAll(paths.limitations());
        if (proven) {
            limitations.add(
                    "Code Inventory saw no call of it in this run: " + CodeInventoryService.NOT_SEEN_BEFORE_CLAIM);
        } else if (lookup == null) {
            limitations.add("Code Inventory is unavailable, so a route that ran without its call trees showing the"
                    + " method is never said not to have run it.");
        }
        if (!notObserved.isEmpty()) {
            limitations.add("Executor work still running, or that the agent did not follow onto its thread, is in no"
                    + " call tree.");
        }
        if (!bean && mapped.isEmpty()) {
            limitations.add("`" + className + "` is no bean of this run, so the routes that could reach the method"
                    + " are not known: only the routes that ran it are listed.");
        }
        if (structure.beansUnavailable() != null) {
            limitations.add(structure.beansUnavailable());
        }
        if (routeOverflow && undetermined) {
            limitations.add("The route aggregate reached its cardinality limit: routes absent from its counts and"
                    + " the retained journal cannot be classified as not exercised.");
        }
        addUnlisted(limitations, "observed", observed);
        addUnlisted(limitations, "not observed", notObserved);
        addUnlisted(limitations, "not exercised", notExercised);
        addUnlisted(limitations, "sharing a resource", shared);
        List<String> methods = new ArrayList<>(keys.keySet());
        return new RuntimeChangeImpactDto(
                RESOLVED,
                null,
                asked,
                method.label(className),
                List.of(),
                closure.isEmpty() ? declared.size() : closure.size(),
                capped(observed),
                observed.size(),
                capped(notExercised),
                notExercised.size(),
                capped(shared),
                shared.size(),
                limitations,
                undetermined,
                RuntimeChangeImpactDto.FROM_ROUTE_TREES,
                methods.size() <= RuntimeChangeImpactDto.MAX_ROWS
                        ? methods
                        : methods.subList(0, RuntimeChangeImpactDto.MAX_ROWS),
                methodStatus(keys),
                capped(notObserved),
                notObserved.size());
    }

    /**
     * Whether Code Inventory proves the method never ran in this run: every overload it names has an inventory row, all
     * {@code NEVER_EXECUTED}, from a complete scan unless one overload was named.
     */
    private static boolean neverRan(
            MethodSymbol method,
            Map<String, CodeInventoryService.InventoryMethod> keys,
            CodeInventoryService.MethodLookup lookup) {
        if (lookup == null || keys.isEmpty() || (!method.overloadNamed() && !lookup.complete())) {
            return false;
        }
        for (CodeInventoryService.InventoryMethod found : keys.values()) {
            if (found == null
                    || !CodeInventoryService.NEVER_EXECUTED.equals(
                            found.method().status())) {
                return false;
            }
        }
        return true;
    }

    /** Why the call trees may not show the method even when a request ran it. */
    private static List<String> silence(
            String className,
            MethodSymbol method,
            Map<String, CodeInventoryService.InventoryMethod> keys,
            MethodRoutes paths) {
        List<String> why = new ArrayList<>();
        boolean untraced = false;
        boolean unknown = keys.isEmpty();
        for (Map.Entry<String, CodeInventoryService.InventoryMethod> key : keys.entrySet()) {
            String descriptor = key.getKey().substring(key.getKey().indexOf('('));
            int access = key.getValue() == null ? -1 : key.getValue().access();
            Boolean traced = key.getValue() == null && paths.routesByKey().containsKey(key.getKey())
                    ? Boolean.TRUE
                    : TracedMethods.traced(className, method.name(), descriptor, access, paths.beanClasses());
            if (Boolean.FALSE.equals(traced)) {
                untraced = true;
            } else if (traced == null) {
                unknown = true;
            }
        }
        if (untraced) {
            why.add(TracedMethods.NOT_TRACED);
        } else if (unknown) {
            why.add("whether the code-paths sensor times it is unknown, as Code Inventory has not read its class");
        }
        if (!paths.excluded().isEmpty()) {
            why.add("the code-paths sensor adaptively excluded it in this run, so later calls are in no call tree");
        }
        return why;
    }

    /**
     * The keys, {@code class#name+descriptor}, of the methods Code Inventory has of the asked class and name when the
     * asked parameters match none of them, or empty when no parameters were asked or none is known.
     */
    private static List<String> overloads(MethodSymbol method, CodeInventoryService.MethodLookup lookup) {
        if (!method.overloadNamed() || lookup == null) {
            return List.of();
        }
        Set<String> keys = new java.util.TreeSet<>();
        for (CodeInventoryService.InventoryMethod found : lookup.methods()) {
            if (method.name().equals(found.method().name())
                    && method.namesClass(found.method().className())) {
                keys.add(found.method().key());
            }
        }
        return List.copyOf(keys);
    }

    /** Code Inventory's word on the methods: executed if any ran, never executed if none did and all were tracked. */
    private static String methodStatus(Map<String, CodeInventoryService.InventoryMethod> keys) {
        boolean any = false;
        boolean never = true;
        boolean untracked = false;
        for (CodeInventoryService.InventoryMethod found : keys.values()) {
            if (found == null) {
                never = false;
                continue;
            }
            any = true;
            String status = found.method().status();
            if (CodeInventoryService.EXECUTED.equals(status) || CodeInventoryService.GENERATED.equals(status)) {
                return CodeInventoryService.EXECUTED;
            }
            if (!CodeInventoryService.NEVER_EXECUTED.equals(status)) {
                never = false;
                untracked |= CodeInventoryService.NOT_TRACKED.equals(status);
            }
        }
        if (!any) {
            return null;
        }
        if (never) {
            return CodeInventoryService.NEVER_EXECUTED;
        }
        return untracked ? CodeInventoryService.NOT_TRACKED : null;
    }

    /** The routes' executed methods for {@code method}, or unavailable with the reason; never throws. */
    private MethodRoutes methodRoutes(MethodSymbol method) {
        Function<Predicate<String>, MethodRoutes> source = codePaths;
        if (source == null) {
            return MethodRoutes.unavailable("the BootUI agent is not attached.");
        }
        try {
            MethodRoutes routes = source.apply(method::matchesKey);
            return routes == null ? MethodRoutes.unavailable("the BootUI agent is not attached.") : routes;
        } catch (RuntimeException ex) {
            return MethodRoutes.unavailable(
                    MethodRoutes.READ_FAILED + ex.getClass().getSimpleName() + ".");
        }
    }

    /** Code Inventory's methods of that name and class, or {@code null} when it cannot answer; never throws. */
    private CodeInventoryService.MethodLookup lookup(MethodSymbol method) {
        BiFunction<String, String, CodeInventoryService.MethodLookup> source = inventory;
        if (source == null) {
            return null;
        }
        try {
            CodeInventoryService.MethodLookup lookup = source.apply(method.type(), method.name());
            return lookup == null || lookup.unavailableReason() != null ? null : lookup;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static ModelNode routeNode(RuntimeModel model, String route) {
        return model.node(NodeType.ROUTE, route)
                .or(() -> model.node(NodeType.GRAPHQL_OPERATION, route))
                .orElse(null);
    }

    private static String requests(long count) {
        return count + (count == 1 ? " request" : " requests");
    }

    private static String stripPeriod(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.endsWith(".") ? stripped.substring(0, stripped.length() - 1) : stripped;
    }

    private static RuntimeChangeImpactDto notFound(String asked, String reason, List<String> limitations) {
        return new RuntimeChangeImpactDto(
                NOT_FOUND, reason, asked, null, List.of(), 0, List.of(), 0, List.of(), 0, List.of(), 0, limitations);
    }

    /**
     * A handler method resolved to its class and the routes mapped to it.
     *
     * @param handler its fully qualified class and name, such as {@code com.example.ProductController#list}
     * @param routes the labels of the routes mapped to it
     */
    private record MappedMethod(String handler, List<String> routes) {

        String label() {
            return MethodSymbol.KIND + " " + handler;
        }

        /** The model's nodes for its routes, each one step from the method, as a closure would give them. */
        Map<Integer, Integer> reach(RuntimeModel model) {
            Map<Integer, Integer> reach = new HashMap<>();
            for (String route : routes) {
                ModelNode node = routeNode(model, route);
                if (node != null) {
                    reach.put(node.id(), 1);
                }
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
            if (!RESOURCES.contains(target.type())) {
                // An environment variable a route read (Side Effects, M5-5d) is not one of its data reads.
                continue;
            }
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

    /** A route's row by label, its reads and writes from the model when it has a node there. */
    private RuntimeImpactRouteDto routeRow(
            RuntimeModel model,
            String route,
            ModelNode node,
            Map<String, RouteStats> stats,
            Map<String, List<String>> exemplars,
            List<String> shared,
            String check,
            Map<String, Boolean> visible,
            long executedRequests,
            boolean partial) {
        RuntimeImpactRouteDto base = node != null
                ? row(model, node, stats, exemplars, shared, check, visible)
                : new RuntimeImpactRouteDto(
                        route,
                        stats.get(route) == null ? 0 : stats.get(route).requests(),
                        stats.get(route) == null || !visible.getOrDefault(BootUiPanels.SECURITY_LOGS, false)
                                ? 0
                                : stats.get(route).authorization().anonymous(),
                        stats.get(route) == null
                                ? 0
                                : stats.get(route).statusClasses().get(4),
                        exemplars.getOrDefault(route, List.of()),
                        List.of(),
                        List.of(),
                        shared,
                        check);
        return new RuntimeImpactRouteDto(
                base.route(),
                base.requests(),
                base.anonymous(),
                base.errors(),
                base.exemplarRequestIds(),
                base.reads(),
                base.writes(),
                base.shared(),
                base.check(),
                executedRequests,
                partial);
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

    /**
     * Names the routes a list holds beyond the {@value RuntimeChangeImpactDto#MAX_ROWS} it shows, at most {@value
     * #MAX_UNLISTED} of them, so a caller sees every route without asking again.
     */
    private static void addUnlisted(List<String> limitations, String list, List<RuntimeImpactRouteDto> rows) {
        String unlisted = unlisted(list, rows);
        if (unlisted != null) {
            limitations.add(unlisted);
        }
    }

    /** The sentence naming the routes {@code rows} holds beyond those listed, or {@code null} when all are listed. */
    static String unlisted(String list, List<RuntimeImpactRouteDto> rows) {
        if (rows.size() <= RuntimeChangeImpactDto.MAX_ROWS) {
            return null;
        }
        List<RuntimeImpactRouteDto> rest = rows.subList(RuntimeChangeImpactDto.MAX_ROWS, rows.size());
        List<String> names = rest.stream()
                .limit(MAX_UNLISTED)
                .map(row -> "`" + row.route() + "`")
                .toList();
        int more = rest.size() - names.size();
        return "Listed " + RuntimeChangeImpactDto.MAX_ROWS + " of the " + rows.size() + " routes " + list
                + "; the other " + rest.size() + ": " + String.join(", ", names)
                + (more > 0 ? ", and " + more + " more" : "") + ".";
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
