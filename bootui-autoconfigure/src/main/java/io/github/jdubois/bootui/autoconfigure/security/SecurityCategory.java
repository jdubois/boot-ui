package io.github.jdubois.bootui.autoconfigure.security;

enum SecurityCategory {
    AUTHENTICATION("Authentication"),
    AUTHORIZATION("Authorization"),
    CSRF("CSRF"),
    SESSION("Session management"),
    HEADERS("Transport and headers"),
    CORS("CORS"),
    METHOD_SECURITY("Method security"),
    ACTUATOR("Actuator exposure"),
    OAUTH2("OAuth2 / JWT"),
    CONFIGURATION("Configuration");

    private final String label;

    SecurityCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
