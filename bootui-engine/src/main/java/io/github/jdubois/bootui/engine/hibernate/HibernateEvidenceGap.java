package io.github.jdubois.bootui.engine.hibernate;

/**
 * Controlled reason why a Hibernate rule could not reach a conclusion about part of what it inspects. Each phrase
 * names a kind of evidence only; it never carries query text, property values, or exception messages.
 */
enum HibernateEvidenceGap {
    QUERY_PROVENANCE("repository query method(s) whose query provenance is unverified (named query, query rewriter,"
            + " or unverified method)"),
    QUERY_SHAPE("repository query method(s) whose JPQL is outside the readable shape (subquery, set operation,"
            + " multiple roots, non-simple join path, or unresolved entity)"),
    QUERY_RETURN_TYPE("repository query method(s) whose return element type is unavailable"),
    DERIVED_QUERY("derived repository query method(s) that could not be verified"),
    QUERY_HINT("repository query method(s) whose pagination query hint is not observed"),
    ENTITY_GRAPH("repository query method(s) with an entity graph whose fetch plan is not reconstructed"),
    REPOSITORY_NEWNESS("repository(ies) with a non-standard entity newness strategy"),
    FACTORY_SETTING("effective persistence-unit setting(s) unavailable"),
    APPLICATION_SETTING(
            "application-level setting(s) unavailable (logging, open-in-view, or datasource" + " initialization)"),
    CONNECTION_PROVIDER("connection provider or pool guarantees unavailable"),
    HIBERNATE_VERSION("Hibernate ORM version unavailable"),
    ENTITY_METADATA("entity mapping(s) whose class, identifier, index, or enhancement metadata is unavailable"),
    CACHE_STRATEGY("second-level cache strategy or provider unavailable"),
    OTHER("required observation(s) unavailable");

    private final String phrase;

    HibernateEvidenceGap(String phrase) {
        this.phrase = phrase;
    }

    String phrase() {
        return phrase;
    }
}
