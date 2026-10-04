package io.github.jdubois.bootui.engine.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link RuntimeModel} within its caps: interns nodes, keeps declared and observed edges apart, and sums an
 * observed edge's occurrences with when it was first and last seen. A node or edge beyond a cap is counted, never kept.
 */
public final class RuntimeModelBuilder {

    private final int maxNodes;
    private final int maxEdges;
    private final List<ModelNode> nodes = new ArrayList<>();
    private final Map<NodeType, Map<String, ModelNode>> byKey = new EnumMap<>(NodeType.class);
    private final List<long[]> executions = new ArrayList<>();
    private final Map<EdgeKey, long[]> edges = new LinkedHashMap<>();
    private long droppedNodes;
    private long droppedEdges;

    public RuntimeModelBuilder() {
        this(RuntimeModel.MAX_NODES, RuntimeModel.MAX_EDGES);
    }

    public RuntimeModelBuilder(int maxNodes, int maxEdges) {
        this.maxNodes = maxNodes;
        this.maxEdges = maxEdges;
    }

    /** The interned id of {@code type} named {@code key}, or {@code -1} when the node cap is reached. */
    public int node(NodeType type, String key) {
        if (key == null || key.isBlank()) {
            return -1;
        }
        Map<String, ModelNode> ofType = byKey.computeIfAbsent(type, t -> new HashMap<>());
        ModelNode existing = ofType.get(key);
        if (existing != null) {
            return existing.id();
        }
        if (nodes.size() >= maxNodes) {
            droppedNodes++;
            return -1;
        }
        ModelNode created = new ModelNode(nodes.size(), type, key);
        nodes.add(created);
        executions.add(new long[1]);
        ofType.put(key, created);
        return created.id();
    }

    /** Counts one execution of an execution node, such as one request of a route. */
    public void execution(int node) {
        if (node >= 0) {
            executions.get(node)[0]++;
        }
    }

    /** Adds a declared edge, once. */
    public void declare(int from, EdgeType type, int to) {
        edge(new EdgeKey(from, type, to, Provenance.DECLARED), -1);
    }

    /** Adds one observation of an edge at {@code epochMillis}. */
    public void observe(int from, EdgeType type, int to, long epochMillis) {
        edge(new EdgeKey(from, type, to, Provenance.OBSERVED), epochMillis);
    }

    /**
     * Adds {@code count} observations of an edge at once, such as the calls Code Paths counted between two beans, with
     * when they were first and last seen unknown.
     */
    public void observeTimes(int from, EdgeType type, int to, long count) {
        if (count <= 0) {
            return;
        }
        EdgeKey key = new EdgeKey(from, type, to, Provenance.OBSERVED);
        edge(key, -1);
        long[] stats = edges.get(key);
        if (stats != null) {
            stats[0] += count - 1;
        }
    }

    /** Adds an edge inferred from shared access to a resource, once. */
    public void infer(int from, EdgeType type, int to) {
        edge(new EdgeKey(from, type, to, Provenance.INFERRED), -1);
    }

    private void edge(EdgeKey key, long epochMillis) {
        if (key.from() < 0 || key.to() < 0) {
            return;
        }
        long[] stats = edges.get(key);
        if (stats == null) {
            if (edges.size() >= maxEdges) {
                droppedEdges++;
                return;
            }
            stats = new long[] {0, -1, -1};
            edges.put(key, stats);
        }
        if (key.provenance() == Provenance.OBSERVED) {
            stats[0]++;
            if (epochMillis >= 0) {
                stats[1] = stats[1] < 0 ? epochMillis : Math.min(stats[1], epochMillis);
                stats[2] = Math.max(stats[2], epochMillis);
            }
        }
    }

    /** The model, with {@code limitations} and whatever the caps left out. */
    public RuntimeModel build(String runId, List<String> limitations) {
        List<String> all = new ArrayList<>(limitations);
        if (droppedNodes > 0) {
            all.add("The model reached its " + maxNodes + "-node cap and left out " + droppedNodes + " nodes.");
        }
        if (droppedEdges > 0) {
            all.add("The model reached its " + maxEdges + "-edge cap and left out " + droppedEdges + " edges.");
        }
        List<ModelEdge> built = new ArrayList<>(edges.size());
        edges.forEach((key, stats) -> built.add(
                new ModelEdge(key.from(), key.type(), key.to(), key.provenance(), stats[0], stats[1], stats[2])));
        long[] counts = new long[nodes.size()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = executions.get(i)[0];
        }
        Map<NodeType, Map<String, ModelNode>> index = new EnumMap<>(NodeType.class);
        byKey.forEach((type, ofType) -> index.put(type, Map.copyOf(ofType)));
        return new RuntimeModel(runId, nodes, index, counts, built, all);
    }

    private record EdgeKey(int from, EdgeType type, int to, Provenance provenance) {}
}
