package io.github.jdubois.bootui.engine.servicemap;

/**
 * Hard bounds applied to the Live Flow service map before it is serialized, so a high-cardinality
 * outbound surface can never produce an unbounded response or an unreadable graph.
 *
 * <p>The caps are intentionally small and fixed rather than configurable: the map is a
 * "what does this application talk to" orientation surface, not a metrics store, and a developer who
 * needs the full list already has the source panel one deep link away.</p>
 */
public final class ServiceMapLimits {

    /**
     * Maximum number of dependency nodes rendered. Sized for the dense end of a realistic local
     * application (a handful of pools plus a couple of dozen HTTP origins, topics, and exchanges) while
     * staying legible in a single native SVG viewport.
     */
    public static final int MAX_DEPENDENCIES = 28;

    /**
     * Maximum retained interactions carried per edge. Small on purpose: this tail only exists so the
     * browser can tell newly completed evidence from evidence it has already drawn.
     */
    public static final int MAX_RECENT_INTERACTIONS = 6;

    private ServiceMapLimits() {}
}
