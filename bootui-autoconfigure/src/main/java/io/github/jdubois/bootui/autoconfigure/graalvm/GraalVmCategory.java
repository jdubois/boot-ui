package io.github.jdubois.bootui.autoconfigure.graalvm;

/**
 * Logical grouping for the curated native-image readiness checks so the panel can describe what
 * each check inspects.
 */
enum GraalVmCategory {
    REFLECTION("Reflection"),
    PROXIES("Dynamic proxies"),
    RESOURCES("Resources"),
    SERIALIZATION("Serialization"),
    NATIVE_ACCESS("Native access");

    private final String label;

    GraalVmCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
