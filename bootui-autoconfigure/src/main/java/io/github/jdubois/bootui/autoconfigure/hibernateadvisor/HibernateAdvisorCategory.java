package io.github.jdubois.bootui.autoconfigure.hibernateadvisor;

enum HibernateAdvisorCategory {
    FETCHING("Fetching"),
    IDENTIFIERS("Identifiers"),
    MAPPING("Mapping"),
    CONFIGURATION("Configuration"),
    ENTITY_DESIGN("Entity design");

    private final String label;

    HibernateAdvisorCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
