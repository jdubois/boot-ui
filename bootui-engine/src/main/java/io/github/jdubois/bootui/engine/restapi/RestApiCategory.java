package io.github.jdubois.bootui.engine.restapi;

/**
 * Logical grouping for the curated REST API Advisor rules so the panel can describe what each rule
 * inspects. Categories mirror common REST API design concerns (routing, naming, responses,
 * validation, payloads, pagination, versioning, error handling &amp; documentation).
 */
enum RestApiCategory {
    ROUTING("Routing & HTTP method mapping"),
    NAMING("Naming & resource design"),
    RESPONSES("Status codes & responses"),
    VALIDATION("Input validation & binding"),
    PAYLOADS("DTO & payload contracts"),
    PAGINATION("Pagination & collections"),
    VERSIONING("Versioning & content negotiation"),
    ERROR_HANDLING("Error handling & documentation");

    private final String label;

    RestApiCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
