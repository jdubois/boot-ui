package io.github.jdubois.bootui.engine.graalvm;

/**
 * Logical grouping for the curated native-image readiness checks so the panel can describe what
 * each check inspects.
 */
enum GraalVmCategory {
    REFLECTION("Reflection"),
    PROXIES("Dynamic proxies"),
    RESOURCES("Resources"),
    SERVICE_LOADER("Service loading"),
    SERIALIZATION("Serialization"),
    BUILD_TIME_INIT("Build-time initialization"),
    NATIVE_ACCESS("Native access"),
    CLASS_GENERATION("Class generation"),
    CLASSPATH_SCANNING("Classpath scanning"),
    SPRING_AOT("Spring AOT"),
    SECURITY_PROVIDERS("Security providers"),
    JMX("JMX");

    private final String label;

    GraalVmCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
