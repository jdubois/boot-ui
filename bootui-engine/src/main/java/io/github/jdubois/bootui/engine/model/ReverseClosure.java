package io.github.jdubois.bootui.engine.model;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Change impact's walk ({@code docs/PLAN-v2.md} §5.4): every node that reaches a start node through edges of an
 * allowlisted type, walking them backwards, at most {@value #MAX_DEPTH} steps. It never walks an edge forwards, so two
 * executions that only share a table reach the table, never each other.
 */
public final class ReverseClosure {

    /** The deepest a closure walks. */
    public static final int MAX_DEPTH = 5;

    private ReverseClosure() {}

    /**
     * The nodes that reach {@code start}, each with the fewest steps it takes, in the order they were reached; the start
     * node is not included.
     *
     * @param allowed the edge types the walk may follow, and only those
     * @param maxDepth the most steps, from {@code 1} to {@value #MAX_DEPTH}
     */
    public static Map<Integer, Integer> of(RuntimeModel model, int start, Set<EdgeType> allowed, int maxDepth) {
        if (maxDepth < 1 || maxDepth > MAX_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be between 1 and " + MAX_DEPTH + ": " + maxDepth);
        }
        Map<Integer, Integer> depths = new LinkedHashMap<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {start, 0});
        while (!queue.isEmpty()) {
            int[] current = queue.removeFirst();
            if (current[1] == maxDepth) {
                continue;
            }
            for (ModelEdge edge : model.incoming(current[0])) {
                if (!allowed.contains(edge.type()) || edge.from() == start || depths.containsKey(edge.from())) {
                    continue;
                }
                depths.put(edge.from(), current[1] + 1);
                queue.addLast(new int[] {edge.from(), current[1] + 1});
            }
        }
        return Collections.unmodifiableMap(depths);
    }
}
