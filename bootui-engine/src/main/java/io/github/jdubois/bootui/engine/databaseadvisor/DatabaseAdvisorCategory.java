package io.github.jdubois.bootui.engine.databaseadvisor;

enum DatabaseAdvisorCategory {
    SCHEMA("Schema"),
    HIBERNATE_MAPPING("Hibernate mapping"),
    RUNTIME_SQL("Runtime SQL"),
    DRIVER("JDBC driver"),
    CONNECTION_POOL("Connection pool"),
    SERVER("Server");

    private final String label;

    DatabaseAdvisorCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
