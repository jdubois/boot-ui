package io.github.jdubois.bootui.autoconfigure.spring;

/**
 * Logical grouping for the curated Spring Advisor rules so the panel can describe what each rule
 * inspects in the running application context.
 */
enum SpringCategory {
    BEAN_WIRING("Bean wiring"),
    CONFIGURATION("Configuration"),
    PROFILES("Profiles and environment"),
    PERFORMANCE("Performance and concurrency"),
    WEB("Web and HTTP"),
    PERSISTENCE("Data and persistence"),
    MANAGEMENT("Actuator and management");

    private final String label;

    SpringCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
