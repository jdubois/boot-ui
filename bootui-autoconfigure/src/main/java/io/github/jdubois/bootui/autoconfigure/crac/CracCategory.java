package io.github.jdubois.bootui.autoconfigure.crac;

/**
 * Logical grouping for the curated CRaC checkpoint/restore readiness checks so the panel can
 * describe what each check inspects.
 */
enum CracCategory {
    RESOURCES("Open I/O resources"),
    NETWORK("Network connections"),
    POOLS("Connection pools"),
    THREADS("Threads & schedulers"),
    TIME("Captured time"),
    RANDOMNESS("Randomness"),
    SECRETS("Captured secrets"),
    LIFECYCLE("CRaC lifecycle");

    private final String label;

    CracCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
