package io.github.jdubois.bootui.engine.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The behavior diff's comparison ({@code docs/PLAN-v2.md} §5.4): the edges of one provenance that one run has and the
 * other does not, compared by their nodes' types and keys, since interned ids differ between runs.
 *
 * @param added edges the later run has and the earlier one does not
 * @param removed edges the earlier run has and the later one does not
 */
public record EdgeDiff(Set<EdgeRef> added, Set<EdgeRef> removed) {

    public EdgeDiff {
        added = Collections.unmodifiableSet(new LinkedHashSet<>(added));
        removed = Collections.unmodifiableSet(new LinkedHashSet<>(removed));
    }

    /** The edges of {@code provenance} that differ between {@code before} and {@code after}. */
    public static EdgeDiff between(RuntimeModel before, RuntimeModel after, Provenance provenance) {
        Set<EdgeRef> earlier = refs(before, provenance);
        Set<EdgeRef> later = refs(after, provenance);
        Set<EdgeRef> added = new LinkedHashSet<>(later);
        added.removeAll(earlier);
        Set<EdgeRef> removed = new LinkedHashSet<>(earlier);
        removed.removeAll(later);
        return new EdgeDiff(added, removed);
    }

    /** The edges of {@code provenance} in {@code model}, by their nodes' types and keys. */
    public static Set<EdgeRef> refs(RuntimeModel model, Provenance provenance) {
        Set<EdgeRef> refs = new LinkedHashSet<>();
        for (ModelEdge edge : model.edges()) {
            if (edge.provenance() == provenance) {
                ModelNode from = model.node(edge.from());
                ModelNode to = model.node(edge.to());
                refs.add(new EdgeRef(from.type(), from.key(), edge.type(), to.type(), to.key()));
            }
        }
        return refs;
    }

    /** An edge by its nodes' types and keys. */
    public record EdgeRef(NodeType fromType, String fromKey, EdgeType type, NodeType toType, String toKey) {}
}
