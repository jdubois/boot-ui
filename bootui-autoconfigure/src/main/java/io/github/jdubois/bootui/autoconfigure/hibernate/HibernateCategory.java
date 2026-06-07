package io.github.jdubois.bootui.autoconfigure.hibernate;

enum HibernateCategory {
    FETCHING("Fetching"),
    IDENTIFIERS("Identifiers"),
    MAPPING("Mapping"),
    CONFIGURATION("Configuration"),
    ENTITY_DESIGN("Entity design"),
    QUERY("Query"),
    CACHING("Caching");

    private final String label;

    HibernateCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
