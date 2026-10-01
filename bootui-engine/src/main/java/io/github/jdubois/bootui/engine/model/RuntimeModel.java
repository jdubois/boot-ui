package io.github.jdubois.bootui.engine.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The runtime model of one run ({@code docs/PLAN-v2.md} §5.4): typed nodes and edges, with each edge's provenance and,
 * for observed edges, its counts and when it was first and last seen. Immutable once built.
 */
public final class RuntimeModel {

    /** The most nodes a model keeps. */
    public static final int MAX_NODES = 5_000;

    /** The most edges a model keeps. */
    public static final int MAX_EDGES = 30_000;

    private final String runId;
    private final List<ModelNode> nodes;
    private final Map<NodeType, Map<String, ModelNode>> byKey;
    private final long[] executions;
    private final List<ModelEdge> edges;
    private final List<List<ModelEdge>> outgoing;
    private final List<List<ModelEdge>> incoming;
    private final List<String> limitations;

    RuntimeModel(
            String runId,
            List<ModelNode> nodes,
            Map<NodeType, Map<String, ModelNode>> byKey,
            long[] executions,
            List<ModelEdge> edges,
            List<String> limitations) {
        this.runId = runId;
        this.nodes = List.copyOf(nodes);
        this.byKey = byKey;
        this.executions = executions.clone();
        this.edges = List.copyOf(edges);
        List<List<ModelEdge>> out = new ArrayList<>(nodes.size());
        List<List<ModelEdge>> in = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            out.add(new ArrayList<>());
            in.add(new ArrayList<>());
        }
        for (ModelEdge edge : this.edges) {
            out.get(edge.from()).add(edge);
            in.get(edge.to()).add(edge);
        }
        this.outgoing = out.stream().map(Collections::unmodifiableList).toList();
        this.incoming = in.stream().map(Collections::unmodifiableList).toList();
        this.limitations = List.copyOf(limitations);
    }

    /** The run the model describes, or {@code null} when unknown. */
    public String runId() {
        return runId;
    }

    /** Every node, by id. */
    public List<ModelNode> nodes() {
        return nodes;
    }

    /** Every edge. */
    public List<ModelEdge> edges() {
        return edges;
    }

    /** The node of {@code type} named {@code key}, when the model has it. */
    public Optional<ModelNode> node(NodeType type, String key) {
        Map<String, ModelNode> ofType = byKey.get(type);
        return Optional.ofNullable(ofType == null ? null : ofType.get(key));
    }

    /** The node with interned id {@code id}. */
    public ModelNode node(int id) {
        return nodes.get(id);
    }

    /** How many executions of an execution node this run observed, such as a route's requests. */
    public long executions(int id) {
        return executions[id];
    }

    /** The edges leaving {@code id}. */
    public List<ModelEdge> outgoing(int id) {
        return outgoing.get(id);
    }

    /** The edges entering {@code id}. */
    public List<ModelEdge> incoming(int id) {
        return incoming.get(id);
    }

    /**
     * Whether the model is incomplete, because a cap was reached, the read budget ran out, or the journal evicted
     * events; {@link #limitations()} says why.
     */
    public boolean partial() {
        return !limitations.isEmpty();
    }

    /** Why the model is incomplete, empty when it is not. */
    public List<String> limitations() {
        return limitations;
    }
}
