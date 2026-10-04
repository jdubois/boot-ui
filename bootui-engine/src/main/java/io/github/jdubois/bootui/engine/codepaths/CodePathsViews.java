package io.github.jdubois.bootui.engine.codepaths;

import io.github.jdubois.bootui.core.dto.CodePathsCallsDto;
import io.github.jdubois.bootui.core.dto.CodePathsMethodDto;
import io.github.jdubois.bootui.core.dto.CodePathsMethodTimeDto;
import io.github.jdubois.bootui.core.dto.CodePathsNodeDto;
import io.github.jdubois.bootui.core.dto.CodePathsRouteDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Builds the Code Paths DTOs from route and request trees ({@code docs/PLAN-v2.md} §5.14, M5-4b), resolving method ids
 * to {@code class#name+descriptor} keys through the given resolver. Stateless.
 */
final class CodePathsViews {

    static final int TOP_METHODS = 5;
    static final int MAX_CALLERS = 20;
    static final int MAX_ROUTES = 20;

    private CodePathsViews() {}

    static double millis(double nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    private static double percent(double part, double whole) {
        return whole <= 0 ? 0.0 : Math.round(part * 1_000.0 / whole) / 10.0;
    }

    /** The class of {@code key}, or {@code null}. */
    static String className(String key) {
        int hash = key == null ? -1 : key.indexOf('#');
        return hash <= 0 ? null : key.substring(0, hash);
    }

    /** The name of {@code key}'s method, or {@code null}. */
    static String methodName(String key) {
        int hash = key == null ? -1 : key.indexOf('#');
        if (hash < 0) {
            return null;
        }
        int paren = key.indexOf('(', hash);
        return key.substring(hash + 1, paren < 0 ? key.length() : paren);
    }

    /** {@code SimpleClass.method} for {@code key}. */
    static String label(String key) {
        String type = className(key);
        String name = methodName(key);
        if (type == null || name == null) {
            return key;
        }
        return type.substring(type.lastIndexOf('.') + 1) + "." + name;
    }

    static String kind(int method) {
        return switch (method) {
            case RequestTree.REQUEST -> "REQUEST";
            case RequestTree.ASYNC -> "ASYNC";
            case RequestTree.OTHER -> "OTHER";
            default -> "METHOD";
        };
    }

    static String phase(int phase) {
        return switch (phase) {
            case 1 -> "FILTERS";
            case 2 -> "HANDLER";
            case 3 -> "RESPONSE";
            default -> null;
        };
    }

    /** The exact {@code q} quantile of {@code values}, nearest rank; 0 when empty. */
    static long quantile(long[] values, double q) {
        if (values.length == 0) {
            return 0L;
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(q * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    // --- routes ------------------------------------------------------------------------------------------------------

    /** A route's summary. */
    static CodePathsRouteDto route(RouteTree tree, IntFunction<String> keys) {
        long warm = tree.warmRequests();
        long[] durations = tree.recentDurations();
        List<CodePathsMethodTimeDto> top = new ArrayList<>();
        if (warm > 0) {
            for (Map.Entry<Integer, Long> method : selfByMethod(tree, false, false, TOP_METHODS)) {
                String key = keys.apply(method.getKey());
                top.add(new CodePathsMethodTimeDto(
                        key,
                        className(key),
                        methodName(key),
                        millis((double) method.getValue() / warm),
                        percent(method.getValue(), tree.ownNanos())));
            }
        }
        return new CodePathsRouteDto(
                tree.route(),
                warm,
                tree.hasFirstRequest() ? millis(tree.firstRequestNanos()) : null,
                millis(quantile(durations, 0.5)),
                millis(quantile(durations, 0.95)),
                warm == 0 ? 0.0 : millis((double) tree.ownNanos() / warm),
                warm == 0 ? 0.0 : millis((double) tree.asyncNanos() / warm),
                tree.assemblyOnly(),
                tree.nodeCount(),
                top);
    }

    /**
     * Ranks routes by their warm median, slowest first, routes without a warm request last; each route's median is
     * computed once.
     */
    static List<RouteTree> ranked(List<RouteTree> trees) {
        Map<RouteTree, Long> medians = new HashMap<>();
        for (RouteTree tree : trees) {
            medians.put(tree, quantile(tree.recentDurations(), 0.5));
        }
        List<RouteTree> ranked = new ArrayList<>(trees);
        ranked.sort(Comparator.comparing((RouteTree tree) -> tree.warmRequests() == 0)
                .thenComparing(Comparator.comparingLong((RouteTree tree) -> medians.get(tree))
                        .reversed())
                .thenComparing(RouteTree::route));
        return ranked;
    }

    /**
     * The route tree's method nodes with the most self time, from every node of the tree, not a page of it: slowest
     * first, the earlier node first among equals, at most {@code max}. Asynchronous nodes count, as their own work.
     */
    static List<Integer> hottest(RouteTree tree, int max) {
        List<Integer> methods = new ArrayList<>();
        for (int node = 1; node < tree.nodeCount(); node++) {
            if (tree.method(node) >= 0) {
                methods.add(node);
            }
        }
        methods.sort(Comparator.comparingLong((Integer node) -> tree.selfNanos(node))
                .reversed()
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(methods.subList(0, Math.min(max, methods.size())));
    }

    /**
     * The route's methods by self time, slowest first, at most {@code max}: every node of a method summed, its own
     * request's work only. With {@code handlerOnly}, only the nodes entered in the handler phase; with {@code own}, by
     * own time, without the recorded calls stamped to them.
     */
    static List<Map.Entry<Integer, Long>> selfByMethod(RouteTree tree, boolean handlerOnly, boolean own, int max) {
        Map<Integer, Long> self = new HashMap<>();
        for (int node = 1; node < tree.nodeCount(); node++) {
            int method = tree.method(node);
            if (method < 0 || tree.async(node) || (handlerOnly && !tree.handlerNode(node))) {
                continue;
            }
            self.merge(method, own ? tree.ownNanos(node) : tree.selfNanos(node), Long::sum);
        }
        List<Map.Entry<Integer, Long>> ranked = new ArrayList<>(self.entrySet());
        ranked.sort(Map.Entry.<Integer, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        ranked.removeIf(entry -> entry.getValue() <= 0L);
        return ranked.subList(0, Math.min(max, ranked.size()));
    }

    /** What the route's tree says about its handler, or {@code null} when it has no warm request. */
    static HandlerMethods handler(RouteTree tree, IntFunction<String> keys) {
        if (tree.warmRequests() == 0) {
            return null;
        }
        List<HandlerMethods.Method> methods = new ArrayList<>();
        List<Map.Entry<Integer, Long>> top = selfByMethod(tree, true, true, HandlerMethods.TOP);
        List<String> methodKeys = new ArrayList<>();
        for (Map.Entry<Integer, Long> method : top) {
            methodKeys.add(keys.apply(method.getKey()));
        }
        // Overloads and same-named classes keep distinct labels, so no row of the split replaces another.
        List<String> labels = CodePathStamps.labels(methodKeys);
        for (int i = 0; i < top.size(); i++) {
            methods.add(new HandlerMethods.Method(
                    methodKeys.get(i), labels.get(i), top.get(i).getValue()));
        }
        return new HandlerMethods(
                tree.route(),
                tree.warmRequests(),
                tree.handlerOwnNanos(),
                methods,
                tree.assemblyOnly(),
                tree.stampedCalls(),
                tree.unstampedCalls(),
                tree.unstampedNanos());
    }

    // --- route tree nodes --------------------------------------------------------------------------------------------

    /** The route tree's nodes at most {@code depth} below the request, depth first, each parent's slowest child first. */
    static List<Integer> order(RouteTree tree, int depth) {
        int nodes = tree.nodeCount();
        List<List<Integer>> children = new ArrayList<>(nodes);
        for (int node = 0; node < nodes; node++) {
            children.add(new ArrayList<>());
        }
        for (int node = 1; node < nodes; node++) {
            children.get(tree.parent(node)).add(node);
        }
        Comparator<Integer> slowest = Comparator.comparingLong((Integer node) -> tree.totalNanos(node))
                .reversed()
                .thenComparing(Comparator.naturalOrder());
        List<Integer> order = new ArrayList<>();
        walk(0, children, slowest, depth, tree::depth, order);
        return order;
    }

    private static void walk(
            int node,
            List<List<Integer>> children,
            Comparator<Integer> slowest,
            int maxDepth,
            java.util.function.IntUnaryOperator depth,
            List<Integer> order) {
        // Iterative, so a deep tree never exhausts the stack.
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        stack.push(node);
        while (!stack.isEmpty()) {
            int current = stack.pop();
            if (depth.applyAsInt(current) > maxDepth) {
                continue;
            }
            order.add(current);
            List<Integer> sorted = new ArrayList<>(children.get(current));
            sorted.sort(slowest);
            for (int i = sorted.size() - 1; i >= 0; i--) {
                stack.push(sorted.get(i));
            }
        }
    }

    static CodePathsNodeDto node(RouteTree tree, int node, IntFunction<String> keys, int[] childCounts) {
        long warm = Math.max(1L, tree.warmRequests());
        int method = tree.method(node);
        String key = method >= 0 ? keys.apply(method) : null;
        long handler = tree.handlerNanos();
        Double share;
        if (tree.async(node)) {
            share = null;
        } else if (handler > 0) {
            share = tree.handlerNode(node) ? percent(tree.totalNanos(node), handler) : null;
        } else {
            share = percent(tree.totalNanos(node), tree.ownNanos());
        }
        long p50 = tree.percentileNanos(node, 0.5);
        long p95 = tree.percentileNanos(node, 0.95);
        return new CodePathsNodeDto(
                node,
                node == 0 ? null : tree.parent(node),
                tree.depth(node),
                kind(method),
                key,
                className(key),
                methodName(key),
                phase(tree.phase(node)),
                tree.async(node),
                tree.requests(node),
                Math.round(tree.calls(node) * 100.0 / warm) / 100.0,
                millis((double) tree.totalNanos(node) / warm),
                millis((double) tree.selfNanos(node) / warm),
                share,
                p50 < 0 ? null : millis(p50),
                p95 < 0 ? null : millis(p95),
                childCounts[node],
                calls(kind -> tree.ioCalls(node, kind), kind -> tree.ioNanos(node, kind), warm));
    }

    /** One entry per kind of recorded call a node issued, per {@code requests} requests. */
    static List<CodePathsCallsDto> calls(
            java.util.function.IntToLongFunction calls, java.util.function.IntToLongFunction nanos, long requests) {
        List<CodePathsCallsDto> list = new ArrayList<>();
        for (int kind = 0; kind < CodePathStamps.KINDS; kind++) {
            long count = calls.applyAsLong(kind);
            if (count == 0) {
                continue;
            }
            list.add(new CodePathsCallsDto(
                    CodePathStamps.NAMES[kind],
                    Math.round(count * 100.0 / requests) / 100.0,
                    kind == CodePathStamps.CACHE ? null : millis((double) nanos.applyAsLong(kind) / requests)));
        }
        return list;
    }

    static int[] childCounts(RouteTree tree) {
        int[] counts = new int[tree.nodeCount()];
        for (int node = 1; node < tree.nodeCount(); node++) {
            counts[tree.parent(node)]++;
        }
        return counts;
    }

    /** Where each method of {@code ids} is called from within {@code tree}, and which routes reach it. */
    static List<CodePathsMethodDto> methods(
            RouteTree tree, Set<Integer> ids, List<RouteTree> all, IntFunction<String> keys) {
        Map<Integer, Set<String>> callers = new LinkedHashMap<>();
        for (int id : ids) {
            callers.put(id, new LinkedHashSet<>());
        }
        for (int node = 1; node < tree.nodeCount(); node++) {
            Set<String> of = callers.get(tree.method(node));
            if (of != null && of.size() < MAX_CALLERS) {
                int parent = tree.method(tree.parent(node));
                of.add(parent >= 0 ? keys.apply(parent) : kind(parent));
            }
        }
        Map<Integer, Set<String>> routes = new HashMap<>();
        for (RouteTree route : all) {
            for (int node = 1; node < route.nodeCount(); node++) {
                int method = route.method(node);
                if (ids.contains(method)) {
                    Set<String> reached = routes.computeIfAbsent(method, ignored -> new LinkedHashSet<>());
                    if (reached.size() < MAX_ROUTES) {
                        reached.add(route.route());
                    }
                }
            }
        }
        List<CodePathsMethodDto> methods = new ArrayList<>();
        for (Map.Entry<Integer, Set<String>> entry : callers.entrySet()) {
            methods.add(new CodePathsMethodDto(
                    keys.apply(entry.getKey()),
                    List.copyOf(entry.getValue()),
                    List.copyOf(routes.getOrDefault(entry.getKey(), Set.of()))));
        }
        return methods;
    }

    // --- request trees -----------------------------------------------------------------------------------------------

    /** A request tree's nodes, depth first, each parent's slowest child first. */
    static List<CodePathsNodeDto> nodes(RequestTree tree, IntFunction<String> keys) {
        int count = tree.nodeCount();
        int[] depth = new int[count];
        boolean[] async = new boolean[count];
        List<List<Integer>> children = new ArrayList<>(count);
        for (int node = 0; node < count; node++) {
            children.add(new ArrayList<>());
        }
        for (int node = 1; node < count; node++) {
            int parent = tree.parent()[node];
            children.get(parent).add(node);
            depth[node] = depth[parent] + 1;
            async[node] = async[parent] || tree.method()[node] == RequestTree.ASYNC;
        }
        long handler = 0;
        for (int node = 1; node < count; node++) {
            if (!async[node] && tree.phase()[node] == 2 && tree.method()[node] != RequestTree.ASYNC) {
                handler += tree.selfNanos(node);
            }
        }
        Comparator<Integer> slowest = Comparator.comparingLong((Integer node) -> tree.total()[node])
                .reversed()
                .thenComparing(Comparator.naturalOrder());
        List<Integer> order = new ArrayList<>();
        walk(0, children, slowest, Integer.MAX_VALUE, node -> depth[node], order);
        List<CodePathsNodeDto> nodes = new ArrayList<>();
        for (int node : order) {
            int method = tree.method()[node];
            String key = method >= 0 ? keys.apply(method) : null;
            Double share;
            if (async[node]) {
                share = null;
            } else if (handler > 0) {
                share = tree.phase()[node] == 2 && node > 0 ? percent(tree.total()[node], handler) : null;
            } else {
                share = percent(tree.total()[node], tree.durationNanos());
            }
            nodes.add(new CodePathsNodeDto(
                    node,
                    node == 0 ? null : tree.parent()[node],
                    depth[node],
                    kind(method),
                    key,
                    className(key),
                    methodName(key),
                    phase(tree.phase()[node]),
                    async[node],
                    1L,
                    node == 0 ? 1.0 : tree.calls()[node],
                    millis(tree.total()[node]),
                    millis(tree.selfNanos(node)),
                    share,
                    null,
                    null,
                    children.get(node).size(),
                    calls(kind -> tree.ioCalls(node, kind), kind -> tree.ioNanos(node, kind), 1L)));
        }
        return nodes;
    }

    /** A request tree's methods with the most self time, its own work only. */
    static List<CodePathsMethodTimeDto> topMethods(RequestTree tree, IntFunction<String> keys) {
        int count = tree.nodeCount();
        boolean[] async = new boolean[count];
        Map<Integer, Long> self = new HashMap<>();
        for (int node = 1; node < count; node++) {
            async[node] = async[tree.parent()[node]] || tree.method()[node] == RequestTree.ASYNC;
            if (!async[node] && tree.method()[node] >= 0) {
                self.merge(tree.method()[node], tree.selfNanos(node), Long::sum);
            }
        }
        List<Map.Entry<Integer, Long>> ranked = new ArrayList<>(self.entrySet());
        ranked.sort(Map.Entry.<Integer, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        List<CodePathsMethodTimeDto> top = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : ranked) {
            if (top.size() == TOP_METHODS || entry.getValue() <= 0) {
                break;
            }
            String key = keys.apply(entry.getKey());
            top.add(new CodePathsMethodTimeDto(
                    key,
                    className(key),
                    methodName(key),
                    millis(entry.getValue()),
                    percent(entry.getValue(), tree.durationNanos())));
        }
        return top;
    }
}
