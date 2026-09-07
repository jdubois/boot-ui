package io.github.jdubois.bootui.engine.hibernate;

import java.util.List;

/** Bounded, internal query provenance. Arbitrary hints and native metadata are never retained. */
public record HibernateQueryEvidence(
        boolean verifiedQueryMethod,
        boolean declaredQuery,
        boolean derivedQueryVerified,
        boolean namedQuery,
        boolean queryRewriter,
        Class<?> returnElementType,
        boolean entityGraph,
        String entityGraphType,
        Boolean limitInMemory,
        List<String> collectionParameterBindings) {
    public HibernateQueryEvidence {
        collectionParameterBindings = List.copyOf(collectionParameterBindings);
    }

    public static HibernateQueryEvidence unknown() {
        return new HibernateQueryEvidence(false, false, false, false, false, null, false, null, null, List.of());
    }
}
