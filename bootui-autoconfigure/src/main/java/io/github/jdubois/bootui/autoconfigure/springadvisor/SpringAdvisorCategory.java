package io.github.jdubois.bootui.autoconfigure.springadvisor;

/**
 * Logical grouping for the curated Spring Advisor rules so the panel can describe what each rule
 * inspects in the running application context.
 */
enum SpringAdvisorCategory {
    BEAN_WIRING("Bean wiring"),
    CONFIGURATION("Configuration"),
    PROFILES("Profiles and environment"),
    PERFORMANCE("Performance and concurrency"),
    WEB("Web and HTTP");

    private final String label;

    SpringAdvisorCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
