package io.github.jdubois.bootui.engine.insights;

/** The request stack serving the application, which decides where some observations apply ({@code docs/PLAN-v2.md} §5.5). */
public enum InsightsStack {

    /** Spring Boot servlet (Spring MVC). */
    SPRING_MVC("Spring MVC"),

    /** Spring Boot reactive (Spring WebFlux). */
    SPRING_WEBFLUX("Spring WebFlux"),

    /** The Quarkus extension. */
    QUARKUS("Quarkus");

    private final String label;

    InsightsStack(String label) {
        this.label = label;
    }

    /** The stack's display name. */
    public String label() {
        return label;
    }
}
