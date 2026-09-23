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
    QUERY_HINT("repository query method(s) whose collection-fetch pagination is not proven safe (no"
            + " org.hibernate.limitInMemory hint observed for this Hibernate version)"),
    ENTITY_GRAPH("repository query method(s) with an entity graph whose fetch plan is not reconstructed"),
    REPOSITORY_NEWNESS("repository(ies) with a non-standard entity newness strategy"),
    FACTORY_SETTING("effective persistence-unit setting(s) unavailable"),
    APPLICATION_SETTING(
            "application-level setting(s) unavailable (logging, open-in-view, or datasource" + " initialization)"),
    CONNECTION_PROVIDER("connection provider or pool guarantees unavailable"),
    POOL_GUARANTEES_BY_DESIGN(
            "pool auto-commit and resource-local guarantees, which this advisor does not observe by design", true),
    CACHE_STRATEGY_BY_DESIGN(
            "provider-selected cache access strategy and entity eligibility, which this advisor does not observe by"
                    + " design",
            true),
    HIBERNATE_VERSION("Hibernate ORM version unavailable"),
    CUSTOM_GENERATOR("entity(ies) with a custom identifier generator whose newness behaviour is not inferred"),
    ENTITY_METADATA("entity mapping(s) whose class, identifier, index, or enhancement metadata is unavailable"),
    CACHE_STRATEGY("second-level cache strategy or provider unavailable"),
    OTHER("required observation(s) unavailable");

    private final String phrase;
    private final boolean advisorLimit;

    HibernateEvidenceGap(String phrase) {
        this(phrase, false);
    }

    HibernateEvidenceGap(String phrase, boolean advisorLimit) {
        this.phrase = phrase;
        this.advisorLimit = advisorLimit;
    }

    String phrase() {
        return phrase;
    }

    /** True when the gap is a known limit of the advisor itself rather than something the application can supply. */
    boolean advisorLimit() {
        return advisorLimit;
    }
}
