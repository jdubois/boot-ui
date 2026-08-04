package io.github.jdubois.bootui.engine.reactivesecurity;

/**
 * Grouping used to organize {@code SEC-RXF-*} reactive Spring Security advisor rules in the browser
 * UI. Mirrors the servlet advisor's category vocabulary, restricted to the categories the reactive
 * ruleset actually uses.
 */
enum ReactiveSecurityCategory {
    AUTHORIZATION("Authorization"),
    CSRF("CSRF"),
    CORS("CORS"),
    HEADERS("Transport and headers"),
    ACTUATOR("Actuator exposure"),
    OAUTH2("OAuth2 / JWT"),
    CONFIGURATION("Configuration"),
    SESSION("Session management");

    private final String label;

    ReactiveSecurityCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
